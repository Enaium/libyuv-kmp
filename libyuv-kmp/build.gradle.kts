@file:Suppress("DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.io.File
import java.util.Base64
import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

val jniDir = rootProject.projectDir.resolve("jni")
val hostOs = OperatingSystem.current()

// ==================== Android NDK resolution ====================
val androidApiLevel = 21
val pinnedAndroidNdkVersion = "27.0.12077973"

fun resolveAndroidSdkDir(): File? {
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val localProps = rootProject.file("local.properties")
    if (localProps.isFile) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    return null
}

fun resolveAndroidNdkDir(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val sdk = resolveAndroidSdkDir() ?: return null
    val ndkParent = sdk.resolve("ndk")
    if (!ndkParent.isDirectory) return null
    val pinned = ndkParent.resolve(pinnedAndroidNdkVersion)
    if (pinned.isDirectory) return pinned
    return ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

val resolvedAndroidNdk = resolveAndroidNdkDir()
val androidNdkToolchain = resolvedAndroidNdk?.resolve("build/cmake/android.toolchain.cmake")

fun hasExecutable(name: String): Boolean =
    System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, name) }
        ?.any { it.isFile && it.canExecute() } == true

fun canBuildNativeTarget(targetName: String): Boolean {
    return when {
        hostOs.isMacOsX && targetName.startsWith("macos") -> true
        hostOs.isMacOsX && targetName.startsWith("ios") -> true
        hostOs.isMacOsX && targetName.startsWith("tvos") -> true
        hostOs.isMacOsX && targetName.startsWith("watchos") -> true
        hostOs.isLinux && targetName == "linuxX64" -> true
        hostOs.isLinux && targetName == "linuxArm64" ->
            System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64") ||
                hasExecutable("aarch64-linux-gnu-gcc")
        hostOs.isLinux && targetName == "mingwX64" -> hasExecutable("x86_64-w64-mingw32-gcc")
        hostOs.isWindows && targetName == "mingwX64" -> true
        targetName.startsWith("androidNative") && androidNdkToolchain?.isFile == true -> true
        else -> false
    }
}

val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

// ==================== Kotlin Multiplatform ====================
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    android {
        namespace = "cn.enaium.libyuv"
        compileSdk = 37
        minSdk = 21

        withHostTest {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // Desktop and server Kotlin/Native targets.
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Apple mobile/wearable targets.
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Kotlin/Native Android targets (apps that link libmain.so).
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // Web targets. The libyuv C sources are compiled to a standalone
    // WebAssembly module by the buildWebWasm task; the Kotlin side talks to it
    // through a tiny synchronous JS bridge.
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }

    // Keep the cinterop names identical across every native target so the
    // shared nativeMain implementation can call them.
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val canBuild = canBuildNativeTarget(targetName)
        compilations.getByName("main") {
            cinterops.create("libyuv_kmp") {
                defFile(project.file("src/nativeInterop/cinterop/libyuv_kmp.def"))
                includeDirs(
                    project.file("src/nativeInterop/cinterop"),
                    jniDir.resolve("include"),
                )
                if (canBuild) {
                    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
                    extraOpts(
                        "-libraryPath", outputDir.absolutePath,
                        "-staticLibrary", "libyuv_kmp.a",
                    )
                }
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Bundle every JNI artifact so consumers get the right native
                // binary out of the box; NativeLoader picks one at runtime.
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        getByName("androidMain") {
            // Share the JNI declarations and loader with the JVM target. The
            // AAR supplies libyuv_jni.so through jniLibs, so on Android the
            // loader falls back to System.loadLibrary.
            kotlin.srcDir("src/jvmMain/kotlin")
        }

        // Shared intermediate source sets. The default hierarchy template is
        // disabled so every edge below is explicit and deterministic.
        val commonMain = getByName("commonMain")
        val commonTest = getByName("commonTest")
        val nativeMain = maybeCreate("nativeMain").apply { dependsOn(commonMain) }
        val nativeTest = maybeCreate("nativeTest").apply { dependsOn(commonTest) }
        val webMain = maybeCreate("webMain").apply { dependsOn(commonMain) }
        val webTest = maybeCreate("webTest").apply { dependsOn(commonTest) }

        getByName("jsMain").dependsOn(webMain)
        getByName("wasmJsMain").dependsOn(webMain)
        getByName("jsTest").dependsOn(webTest)
        getByName("wasmJsTest").dependsOn(webTest)
    }
}

// Every Kotlin/Native target shares nativeMain/nativeTest. Wiring the
// dependency (rather than copying the source directory into each target) keeps
// expect/actual resolution and cinterop commonization working.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    compilations.getByName("main").defaultSourceSet.dependsOn(
        kotlin.sourceSets.getByName("nativeMain"),
    )
    compilations.getByName("test").defaultSourceSet.dependsOn(
        kotlin.sourceSets.getByName("nativeTest"),
    )
}

// ==================== WebAssembly ====================
// libyuv and the C shim are compiled to one standalone .wasm module. Its bytes
// are embedded into a generated Kotlin source file, so the published js/wasmJs
// artifact is self-contained: no resource lookup, fetch or async init.
val wasmConfigureDir = layout.buildDirectory.dir("wasm-cmake")
val wasmOutput = wasmConfigureDir.map { it.file("libyuv_wasm.wasm") }
val generatedWebDir = layout.buildDirectory.dir("generated/libyuvWeb/kotlin")

fun resolveEmsdk(): File? {
    System.getenv("EMSDK")?.takeIf { it.isNotBlank() }?.let {
        val dir = File(it)
        if (dir.isDirectory) return dir
    }
    val home = System.getProperty("user.home")
    return listOf("$home/emsdk", "$home/.emsdk").map(::File).firstOrNull { it.isDirectory }
}

fun resolveEmscripten(command: String): String {
    System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
        val file = File(dir, command)
        if (file.isFile && file.canExecute()) return file.absolutePath
    }
    val sdk = resolveEmsdk()
    val candidate = sdk?.resolve("upstream/emscripten/$command")
    if (candidate != null && candidate.isFile) return candidate.absolutePath
    return command
}

fun Exec.configureEmscripten() {
    val sdk = resolveEmsdk()
    sdk?.let {
        environment("EMSDK", it.absolutePath)
        it.resolve(".emscripten").takeIf { f -> f.isFile }?.let { f ->
            environment("EM_CONFIG", f.absolutePath)
        }
        val pythonBin = it.resolve("python").listFiles()
            ?.filter { d -> d.isDirectory }
            ?.mapNotNull { d -> d.resolve("bin") }
            ?.firstOrNull { d -> d.isDirectory }
        val pythonExe = pythonBin?.resolve("python3")?.takeIf { d -> d.isFile }
        pythonExe?.let { exe -> environment("EMSDK_PYTHON", exe.absolutePath) }
        val nodeBin = it.resolve("node").listFiles()
            ?.filter { d -> d.isDirectory }
            ?.flatMap { d -> d.listFiles()?.toList().orEmpty() }
            ?.firstOrNull { d -> d.isDirectory && d.name == "bin" }
        val path = buildString {
            pythonBin?.let { bin -> append(bin.absolutePath).append(File.pathSeparator) }
            append(it.resolve("upstream/emscripten").absolutePath)
            nodeBin?.let { bin -> append(File.pathSeparator).append(bin.absolutePath) }
            System.getenv("PATH")?.let { env -> append(File.pathSeparator).append(env) }
        }
        environment("PATH", path)
    }
}

val configureWebWasm = tasks.register<Exec>("configureWebWasm") {
    group = "build"
    description = "Configures the Emscripten build of libyuv."
    val buildDir = wasmConfigureDir.get().asFile
    workingDir = buildDir
    configureEmscripten()
    commandLine(
        resolveEmscripten("emcmake"), "cmake",
        jniDir.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_JNI=OFF",
        "-DBUILD_WASM=ON",
    )
    doFirst {
        buildDir.mkdirs()
    }
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/libyuv_kmp.cc"),
        rootProject.file("jni/include/libyuv_kmp.h"),
        rootProject.file("jni/libyuv_jni.cpp"),
        rootProject.file("jni/libyuv_wasm_entry.c"),
    )
    inputs.dir(rootProject.file("libyuv/source"))
    inputs.dir(rootProject.file("libyuv/include"))
    outputs.file(wasmConfigureDir.map { it.file("build.ninja") })
}

val buildWebWasm = tasks.register<Exec>("buildWebWasm") {
    group = "build"
    description = "Compiles libyuv and the KMP shim to a standalone WebAssembly module."
    dependsOn(configureWebWasm)
    val buildDir = wasmConfigureDir.get().asFile
    workingDir = buildDir
    configureEmscripten()
    commandLine(cmakeExecutable, "--build", ".", "--target", "libyuv_wasm", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/libyuv_kmp.cc"),
        rootProject.file("jni/include/libyuv_kmp.h"),
        rootProject.file("jni/libyuv_jni.cpp"),
        rootProject.file("jni/libyuv_wasm_entry.c"),
    )
    inputs.dir(rootProject.file("libyuv/source"))
    inputs.dir(rootProject.file("libyuv/include"))
    outputs.file(wasmOutput)
}

val generateWebWasmData = tasks.register<DefaultTask>("generateWebWasmData") {
    group = "build"
    description = "Embeds the compiled WebAssembly module into a Kotlin source file."
    dependsOn(buildWebWasm)
    val wasmBytes = wasmOutput
    val outputDir = generatedWebDir
    inputs.file(wasmBytes)
    outputs.dir(outputDir)
    doLast {
        val bytes = wasmBytes.get().asFile.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val chunkSize = 4096
        val chunks = base64.chunked(chunkSize)
        val file = outputDir.get().asFile.resolve("cn/enaium/libyuv/LibYuvWasmData.kt")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("// Generated by :libyuv:generateWebWasmData. Do not edit.")
                appendLine("package cn.enaium.libyuv")
                appendLine()
                appendLine("internal const val LIBYUV_WASM_BASE64_SIZE = ${base64.length}")
                appendLine("internal val LIBYUV_WASM_BASE64: String by lazy {")
                appendLine("    buildString(LIBYUV_WASM_BASE64_SIZE) {")
                chunks.forEachIndexed { index, chunk ->
                    appendLine("        append(WASM_CHUNK_$index)")
                }
                appendLine("    }")
                appendLine("}")
                chunks.forEachIndexed { index, chunk ->
                    appendLine("private const val WASM_CHUNK_$index = \"$chunk\"")
                }
            },
        )
    }
}

kotlin.sourceSets.getByName("webMain").kotlin.srcDir(generatedWebDir)

tasks.matching {
    (it.name.startsWith("compileKotlinJs") ||
        it.name.startsWith("compileKotlinWasmJs") ||
        it.name.startsWith("compileTestKotlinJs") ||
        it.name.startsWith("compileTestKotlinWasmJs")) &&
        !it.name.contains("Metadata")
}.configureEach {
    dependsOn(generateWebWasmData)
}

// ==================== Native static libraries ====================
fun registerNativeBuildTasks(targetName: String, cmakeFlags: List<String> = emptyList()) {
    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-$targetName").get().asFile

    val configureTask = tasks.register<Exec>("configureNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            listOf(
                cmakeExecutable, jniDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_JNI=OFF",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${outputDir.absolutePath}",
            ) + cmakeFlags,
        )
    }

    val buildTask = tasks.register<Exec>("buildNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--target", "yuv", "--config", "Release")
    }

    tasks.matching {
        it.name.startsWith("cinteropLibyuv_kmp") &&
            it.name.endsWith(targetName.replaceFirstChar { c -> c.uppercase() })
    }.configureEach {
        dependsOn(buildTask)
    }
}

if (hostOs.isMacOsX) {
    registerNativeBuildTasks("macosArm64", listOf(
        "-DCMAKE_TOOLCHAIN_FILE=${jniDir.resolve("toolchains/darwin-arm64.cmake").absolutePath}",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
    ))
    registerNativeBuildTasks("macosX64", listOf(
        "-DCMAKE_TOOLCHAIN_FILE=${jniDir.resolve("toolchains/darwin-x86_64.cmake").absolutePath}",
        "-DCMAKE_OSX_ARCHITECTURES=x86_64",
    ))
    registerNativeBuildTasks("iosArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=iphoneos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("iosX64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS",
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
        "-DCMAKE_OSX_ARCHITECTURES=x86_64",
        "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("iosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("tvosArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=tvOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=appletvos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("tvosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=tvOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=appletvsimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("watchosArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=watchOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=watchos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
    ))
    registerNativeBuildTasks("watchosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=watchOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=watchsimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
    ))
    registerNativeBuildTasks("watchosDeviceArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=watchOS",
        "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=watchos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
    ))
} else if (hostOs.isLinux) {
    registerNativeBuildTasks("linuxX64")
    if (System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64")) {
        registerNativeBuildTasks("linuxArm64", listOf(
            "-DCAN_COMPILE_SME=FALSE",
        ))
    } else {
        registerNativeBuildTasks("linuxArm64", listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
            "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
            "-DCAN_COMPILE_SME=FALSE",
        ))
    }
    registerNativeBuildTasks("mingwX64", listOf(
        "-DCMAKE_SYSTEM_NAME=Windows",
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
        "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
        "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
        "-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres",
    ))
} else if (hostOs.isWindows) {
    registerNativeBuildTasks("mingwX64", listOf(
        "-G", if (System.getenv("MSYSTEM") != null) "MSYS Makefiles" else "MinGW Makefiles",
    ))
}

androidNdkToolchain?.takeIf { it.isFile }?.let { toolchain ->
    listOf(
        "androidNativeArm64" to "arm64-v8a",
        "androidNativeArm32" to "armeabi-v7a",
        "androidNativeX64" to "x86_64",
        "androidNativeX86" to "x86",
    ).forEach { (targetName, abi) ->
        registerNativeBuildTasks(targetName, listOf(
            "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-$androidApiLevel",
            // NDK clang accepts libyuv's SME probe but cannot compile the
            // ZA-register asm in rotate_sme.cc.
            "-DCAN_COMPILE_SME=FALSE",
        ))
    }
}

// ==================== Android JNI shared libraries ====================
val androidJniAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val androidJniLibsDir = layout.buildDirectory.dir("jniLibs")
val buildAndroidJniLibs = tasks.register<DefaultTask>("buildAndroidJniLibs") {
    group = "build"
    description = "Builds libyuv_jni.so for all Android ABIs."
}

androidJniAbis.forEach { abi ->
    val outputDir = layout.buildDirectory.dir("jniLibs/$abi")
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-android-$abi").get().asFile

    val configureTask = tasks.register<Exec>("configureAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.get().asFile.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            cmakeExecutable, jniDir.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_JNI=ON",
            "-DCMAKE_TOOLCHAIN_FILE=${androidNdkToolchain?.absolutePath ?: ""}",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-$androidApiLevel",
            "-DCAN_COMPILE_SME=FALSE",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outputDir.get().asFile.absolutePath}",
        )
    }

    val buildTask = tasks.register<Exec>("buildAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--target", "libyuv_jni", "--config", "Release")
    }

    buildAndroidJniLibs.configure { dependsOn(buildTask) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(androidJniLibsDir.get().asFile.absolutePath)
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildAndroidJniLibs) }

// ==================== Android host tests ====================
// Android host-test targets (unit tests on the JVM) call System.loadLibrary
// directly, so they need a flat directory containing libyuv_jni.{so,dylib}.
// Reuse the host's :jni-jvm-* subproject build output.
val hostJniProjectName = run {
    val arch = System.getProperty("os.arch").lowercase()
    val archClassifier = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> null
    }
    val osClassifier = when {
        hostOs.isLinux -> "linux"
        hostOs.isMacOsX -> "darwin"
        hostOs.isWindows -> "windows"
        else -> null
    }
    if (osClassifier != null && archClassifier != null) ":jni-jvm-$osClassifier-$archClassifier" else null
}

if (hostJniProjectName != null) {
    val hostJniProject = project(hostJniProjectName)
    val hostNativeDir = hostJniProject.layout.buildDirectory.dir(
        "jni-native/${hostJniProjectName.removePrefix(":jni-jvm-")}",
    )
    tasks.withType<Test>().configureEach {
        if (name.contains("AndroidHostTest", ignoreCase = true)) {
            dependsOn("$hostJniProjectName:buildJniLibrary")
            systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
        }
    }
}

// ==================== Publishing ====================
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "libyuv-kmp",
        version = null,
    )

    pom {
        name.set("libyuv-kmp")
        description.set(
            "Kotlin Multiplatform bindings for libyuv: YUV conversion, scaling, rotation and mirroring on JVM, Android, Kotlin/Native and the web.",
        )
        url.set("https://github.com/Enaium/libyuv-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("BSD-3-Clause")
                url.set("https://opensource.org/licenses/BSD-3-Clause")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/libyuv-kmp")
            connection.set("scm:git:git@github.com:Enaium/libyuv-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/libyuv-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/libyuv-kmp/issues")
        }
    }
}
