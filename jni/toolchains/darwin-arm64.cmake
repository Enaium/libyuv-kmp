# Pins the Apple Silicon architecture explicitly. libyuv selects its NEON/SVE
# objects from CMAKE_SYSTEM_PROCESSOR; leaving it to CMake on a cross-build can
# make it disagree with CMAKE_OSX_ARCHITECTURES.
set(CMAKE_SYSTEM_NAME Darwin)
set(CMAKE_SYSTEM_PROCESSOR arm64)
set(CMAKE_OSX_ARCHITECTURES arm64)
