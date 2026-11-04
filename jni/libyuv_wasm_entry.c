/*
 * Placeholder translation unit for the standalone WebAssembly executable.
 * Emscripten is invoked with --no-entry, so no main() is required; all exported
 * symbols live in libyuv_kmp.cc. Keeping a real source here makes CMake treat
 * the target as an ordinary executable.
 */

static const int libyuv_wasm_entry_placeholder = 0;
