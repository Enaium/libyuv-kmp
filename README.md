# libyuv-kmp

Kotlin Multiplatform bindings for [libyuv](https://chromium.googlesource.com/libyuv/libyuv):
YUV conversion, scaling, rotation, mirroring and CPU-feature dispatch on JVM,
Android, Kotlin/Native and the web.

`libyuv` is included as a Git submodule and compiled from source for every
target. The same C API shim drives JNI, Kotlin/Native cinterop and the
WebAssembly build, so all platforms share one argument layout and one set of
buffer validations.

## Supported targets

| Platform | Kotlin target | Native artifact |
|---|---|---|
| JVM | `jvm` | `libyuv-kmp-jni-jvm-{linux,darwin}-{x86_64,aarch64}`, `libyuv-kmp-jni-jvm-windows-x86_64` |
| Android | `android` | `libyuv-kmp` AAR with `libyuv_jni.so` for every ABI |
| Linux | `linuxX64`, `linuxArm64` | static archive embedded in the cinterop klib |
| Windows | `mingwX64` | static archive embedded in the cinterop klib |
| macOS | `macosX64`, `macosArm64` | static archive embedded in the cinterop klib |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | static archive embedded in the cinterop klib |
| tvOS | `tvosArm64`, `tvosSimulatorArm64` | static archive embedded in the cinterop klib |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | static archive embedded in the cinterop klib |
| Android Native | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | static archive embedded in the cinterop klib |
| Kotlin/JS | `js` | embedded WebAssembly module |
| Kotlin/Wasm | `wasmJs` | embedded WebAssembly module |

The web targets compile libyuv to a standalone `.wasm` module with
Emscripten, embed the binary into the published klib and instantiate it
synchronously through a small JS bridge. No resource lookup or async
initialization is required at runtime.

## Using the library

```kotlin
dependencies {
    implementation("cn.enaium.libyuv:libyuv-kmp:1.0.0")
}
```

The Android AAR and the JVM artifacts contain the native libraries. JVM
consumers automatically receive the per-OS/arch JNI artifacts and
`NativeLoader` extracts the matching one from the classpath.

### I420 to ARGB

```kotlin
import cn.enaium.libyuv.LibYuv

val width = 1280
val height = 720
val i420Y = ByteArray(width * height)
val i420U = ByteArray((width + 1) / 2 * ((height + 1) / 2))
val i420V = ByteArray(i420U.size)
val argb = ByteArray(width * height * 4)

LibYuv.i420ToArgb(i420Y, i420U, i420V, argb, width, height)
```

Every function has explicit `ByteArray` arguments and optional byte strides:

```kotlin
LibYuv.i420ToArgb(
    srcY = y, srcU = u, srcV = v,
    dstArgb = argb,
    width = width, height = height,
    srcStrideY = width,
    srcStrideU = (width + 1) / 2,
    srcStrideV = (width + 1) / 2,
    dstStrideArgb = width * 4,
)
```

### Scaling, rotation, mirroring

```kotlin
val scaledY = ByteArray(dstWidth * dstHeight)
val scaledU = ByteArray((dstWidth + 1) / 2 * ((dstHeight + 1) / 2))
val scaledV = ByteArray(scaledU.size)

LibYuv.i420Scale(
    y, u, v, scaledY, scaledU, scaledV,
    width, height, dstWidth, dstHeight,
    filterMode = FilterMode.BILINEAR,
)

LibYuv.i420Rotate(
    y, u, v, rotatedY, rotatedU, rotatedV,
    width, height,
    rotation = Rotation.CLOCKWISE_90,
)
```

### Android `ImageProxy` / `Image` helpers

The Android artifact includes CameraX/MediaCodec interop extensions:

```kotlin
import cn.enaium.libyuv.I420Frame
import cn.enaium.libyuv.toI420
import cn.enaium.libyuv.writeI420

val fromCamera: I420Frame = imageProxy.toI420()
val fromMedia: I420Frame = image.toI420()
image.writeI420(fromCamera) // MediaCodec encoder input Image
```

`I420Frame` and these extensions live in `libyuv-kmp`'s Android source set; the
CameraX `camera-core` dependency is exported for the `ImageProxy` signature.

> These helpers were added after the 1.0.0 Maven Central release; they are part
> of the current source and will be available in the next published version.

### FourCC conversion

```kotlin
LibYuv.convertToI420(
    nv12,
    dstY, dstU, dstV,
    srcWidth = width, srcHeight = height,
    fourCC = FourCC.NV12,
)

LibYuv.convertFromI420(
    y, u, v,
    nv21,
    width, height,
    fourCC = FourCC.NV21,
)
```

`FourCC` covers I420, J420, H420, U420, I422, I444, NV12, NV21, YUY2, UYVY,
RGB24, RGB32 (ARGB/BGRA/ABGR/RGBA), RGB565, RAW, I400 and the 10/12-bit
planar/semi-planar variants that libyuv accepts.

### CPU dispatch

```kotlin
LibYuv.initCpuFlags()
if (LibYuv.testCpuFlag(CpuFlag.HAS_SSE2)) {
    // ...
}
LibYuv.maskCpuFlags(0) // disable all optional CPU features
```

## Android example

`examples/codec` is a Compose camera app that demonstrates the full
encode/decode loopback with CameraX and MediaCodec:

1. CameraX `ImageAnalysis` produces `YUV_420_888` frames.
2. The library's `ImageProxy.toI420()` extension copies the planes into I420
   (planar and semi-planar chroma layouts are both handled).
3. libyuv rotates the frame according to `ImageInfo.rotationDegrees`.
4. The encoder prefers a flexible AVC color format and fills the codec's
   `Image` planes through the library's `Image.writeI420()`, which preserves
   the codec's aligned row strides. When no `Image` input is available, libyuv converts
   I420 to NV12 (or I420 for a planar encoder).
5. The encoded H.264 samples are fed straight into a second MediaCodec
   instance configured with the `TextureView`'s `Surface`. The decoder's
   output is rendered to that `TextureView`; nothing is written to disk.

Run it with:

```bash
./gradlew :examples:codec:installDebug
```

The UI reports the encoded and decoded frame counts, and the
`TextureView` shows the decoded stream.

## Building

Requirements:

- JDK 21+
- CMake 3.20+ and Ninja
- Android SDK with NDK `27.0.12077973` for the Android/Android Native targets
- Emscripten (`emsdk`) for the `js` and `wasmJs` targets
- Cross-compilers for targets built from another host:
  - `aarch64-linux-gnu-gcc/g++` for `linuxArm64`
  - `x86_64-w64-mingw32-gcc/g++` for `mingwX64` from Linux

```bash
git clone --recursive https://github.com/Enaium/libyuv-kmp.git
cd libyuv-kmp

./gradlew :libyuv-kmp:jvmTest
./gradlew :libyuv-kmp:macosArm64Test
./gradlew :libyuv-kmp:jsNodeTest :libyuv-kmp:wasmJsNodeTest
./gradlew :libyuv-kmp:testAndroidHostTest
./gradlew :examples:codec:assembleDebug
```

The Emscripten build is generated on demand by `:libyuv-kmp:buildWebWasm`;
the resulting module is embedded into a generated Kotlin source file before
the web targets compile.

## Publishing

Local publication:

```bash
./gradlew :libyuv-kmp:publishToMavenLocal
./gradlew :jni-jvm-darwin-aarch64:publishToMavenLocal
```

Maven Central publication is driven by
`.github/workflows/publish.yml`. Each host publishes the artifacts it can
build: macOS publishes the JVM/Apple/web klibs and both darwin JNI libraries,
Linux publishes the Android AAR, Linux/MinGW/Android-Native klibs and the
Linux JNI libraries, and Windows publishes the MinGW JNI library. Signing
uses the standard `signing.keyId`, `signing.secretKeyRingFile` and
`signing.password` Gradle properties.

## License

The binding code is MIT licensed. libyuv itself is BSD-3-Clause; see
`libyuv/LICENSE`.
