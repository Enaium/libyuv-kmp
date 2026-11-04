#
# Builds libyuv plus the KMP C shim as one static archive, and (optionally) the
# JNI shared library or the WebAssembly module.
#
#   cmake -S jni -B build -DBUILD_JNI=ON               desktop JVM / Android JNI
#   cmake -S jni -B build -DBUILD_JNI=OFF              static archive only
#   emcmake cmake -S jni -B build-wasm                 WebAssembly
#
# The archive is embedded into every Kotlin/Native cinterop klib. The JNI and
# WebAssembly targets both link the same Dispatch() implementation, so all
# platforms execute identical argument handling.
#
cmake_minimum_required(VERSION 3.20)

project(libyuv_kmp C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(BUILD_JNI "Build the JNI shared library" OFF)
option(BUILD_WASM "Build the standalone WebAssembly module" OFF)

set(LIBYUV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../libyuv")
if(NOT EXISTS "${LIBYUV_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "libyuv submodule is missing at ${LIBYUV_DIR}")
endif()

# libyuv probes for a system libjpeg and, when found, compiles its MJPEG
# decoder with JPEG references. The binding does not expose MJPEG, and CI hosts
# may have libjpeg installed while our JNI/wasm link lines do not, so keep the
# archive self-contained by disabling the optional dependency everywhere.
set(LIBYUV_DISABLE_JPEG ON CACHE BOOL "Disable libyuv JPEG support" FORCE)

# EXCLUDE_FROM_ALL keeps libyuv's shared library and command line tools out of
# the build; only the static `yuv` archive that we extend is required.
add_subdirectory("${LIBYUV_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/libyuv" EXCLUDE_FROM_ALL)

target_sources(yuv PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/libyuv_kmp.cc")
target_include_directories(yuv
  PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
  PUBLIC "${LIBYUV_DIR}/include"
)

# The archive name is what the cinterop `-staticLibrary` flag and the JNI link
# line expect: libyuv_kmp.a on Unix/MinGW, yuv_kmp.lib with MSVC.
set_target_properties(yuv PROPERTIES OUTPUT_NAME yuv_kmp)

if(BUILD_JNI)
  add_library(libyuv_jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/libyuv_jni.cpp")
  target_link_libraries(libyuv_jni PRIVATE yuv)
  set_target_properties(libyuv_jni PROPERTIES OUTPUT_NAME yuv_jni)

  if(WIN32)
    # MinGW names shared libraries lib*.dll by default; the Kotlin loader uses
    # the unprefixed yuv_jni.dll.
    set_target_properties(libyuv_jni PROPERTIES PREFIX "")
  endif()

  if(ANDROID)
    find_library(log-lib log)
    if(log-lib)
      target_link_libraries(libyuv_jni PRIVATE ${log-lib})
    endif()
    # 16 KB page-size devices (Android 15+).
    target_link_options(libyuv_jni PRIVATE
      "-Wl,-z,max-page-size=16384"
      "-Wl,-z,common-page-size=16384"
    )
  else()
    if(JNI_INCLUDE_DIR)
      target_include_directories(libyuv_jni PRIVATE "${JNI_INCLUDE_DIR}")
    endif()
    if(JNI_INCLUDE_DIR_PLATFORM)
      target_include_directories(libyuv_jni PRIVATE "${JNI_INCLUDE_DIR_PLATFORM}")
    endif()
    if(NOT JNI_INCLUDE_DIR)
      find_package(JNI REQUIRED)
      target_include_directories(libyuv_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
  endif()
endif()

if(BUILD_WASM OR EMSCRIPTEN)
  add_executable(libyuv_wasm "${CMAKE_CURRENT_SOURCE_DIR}/libyuv_wasm_entry.c")
  target_link_libraries(libyuv_wasm PRIVATE yuv)
  set_target_properties(libyuv_wasm PROPERTIES SUFFIX ".wasm")
  target_link_options(libyuv_wasm PRIVATE
    "-sSTANDALONE_WASM=1"
    "-sALLOW_MEMORY_GROWTH=1"
    "-sINITIAL_MEMORY=33554432"
    "-sERROR_ON_UNDEFINED_SYMBOLS=1"
    "-sEXPORTED_FUNCTIONS=_lky_version,_lky_call,_lky_call_packed,_lky_alloc,_lky_free"
    "--no-entry"
  )
endif()
