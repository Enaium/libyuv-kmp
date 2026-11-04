# Cross-compiles the C static archive/JNI library for Intel macOS on an
# Apple Silicon host. Without an explicit CMAKE_SYSTEM_PROCESSOR here, CMake
# derives it from the host and libyuv emits its AArch64 SVE objects with
# -march=armv8.5-a, which the x86_64 target compiler rejects.
set(CMAKE_SYSTEM_NAME Darwin)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_OSX_ARCHITECTURES x86_64)
