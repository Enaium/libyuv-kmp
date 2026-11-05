/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#ifndef LIBYUV_KMP_H_
#define LIBYUV_KMP_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * One C ABI shared by the JNI bridge, the Kotlin/Native cinterop and the
 * WebAssembly build. Every operation takes its image planes as an array of
 * region pointers plus a fixed-size integer argument block, so the same
 * dispatch code drives all platforms and the Kotlin side owns argument
 * packing/validation.
 */

#define LKY_ARGS_MAX 16
#define LKY_REGIONS_MAX 8

/* Packed packet used by the WebAssembly bridge. */
#define LKY_PACKED_MAGIC 0x4C4B5950 /* "PYKL" */
#define LKY_PACKED_HEADER_SIZE 144
#define LKY_PACKED_ALIGNMENT 16

enum lky_op {
  LKY_OP_I420_COPY = 1,
  LKY_OP_I422_COPY = 2,
  LKY_OP_I444_COPY = 3,
  LKY_OP_NV12_COPY = 4,
  LKY_OP_NV21_COPY = 5,

  LKY_OP_I420_TO_ARGB = 6,
  LKY_OP_I420_TO_BGRA = 7,
  LKY_OP_I420_TO_ABGR = 8,
  LKY_OP_I420_TO_RGBA = 9,
  LKY_OP_I420_TO_RGB24 = 10,
  LKY_OP_I420_TO_RGB565 = 11,
  LKY_OP_I420_TO_NV12 = 12,
  LKY_OP_I420_TO_NV21 = 13,
  LKY_OP_I420_TO_YUY2 = 14,
  LKY_OP_I420_TO_UYVY = 15,
  LKY_OP_I420_TO_I422 = 16,
  LKY_OP_I420_TO_I444 = 17,

  LKY_OP_NV12_TO_I420 = 18,
  LKY_OP_NV21_TO_I420 = 19,
  LKY_OP_NV12_TO_ARGB = 20,
  LKY_OP_NV21_TO_ARGB = 21,
  LKY_OP_NV12_TO_RGB24 = 22,
  LKY_OP_NV21_TO_RGB24 = 23,

  LKY_OP_ARGB_TO_I420 = 24,
  LKY_OP_BGRA_TO_I420 = 25,
  LKY_OP_ABGR_TO_I420 = 26,
  LKY_OP_RGBA_TO_I420 = 27,
  LKY_OP_ARGB_TO_RGB24 = 28,
  LKY_OP_ARGB_TO_RGB565 = 29,
  LKY_OP_ARGB_TO_NV12 = 30,
  LKY_OP_ARGB_TO_NV21 = 31,

  LKY_OP_RGB24_TO_I420 = 32,
  LKY_OP_RGB24_TO_ARGB = 33,
  LKY_OP_RGB565_TO_ARGB = 34,
  LKY_OP_YUY2_TO_I420 = 35,
  LKY_OP_UYVY_TO_I420 = 36,

  LKY_OP_CONVERT_TO_I420 = 37,
  LKY_OP_CONVERT_FROM_I420 = 38,

  LKY_OP_I420_SCALE = 39,
  LKY_OP_ARGB_SCALE = 40,
  LKY_OP_SCALE_PLANE = 41,
  LKY_OP_NV12_SCALE = 42,

  LKY_OP_I420_ROTATE = 43,
  LKY_OP_ARGB_ROTATE = 44,
  LKY_OP_ROTATE_PLANE = 45,

  LKY_OP_I420_MIRROR = 46,
  LKY_OP_ARGB_MIRROR = 47,
  LKY_OP_PLANE_MIRROR = 48,
  LKY_OP_RGB24_MIRROR = 49,

  LKY_OP_I420_RECT = 50,
  LKY_OP_SET_PLANE = 51,
  LKY_OP_COPY_PLANE = 52,
  LKY_OP_I420_CROP = 53,

  LKY_OP_MASK_CPU_FLAGS = 54,
  LKY_OP_INIT_CPU_FLAGS = 55,
  LKY_OP_TEST_CPU_FLAG = 56,

  LKY_OP_ANDROID420_TO_I420 = 57,
  LKY_OP_I420_TO_RGBA_BT709 = 58,
};

/* libyuv version (LIBYUV_VERSION). */
int lky_version(void);

/*
 * Runs one operation.
 *
 * regions[i] points at the first byte of image region i and lengths[i] is that
 * region's writable size in bytes. args holds up to LKY_ARGS_MAX signed 32-bit
 * arguments. Returns 0 on success, a negative value when libyuv rejects the
 * operation, or the operation's own non-negative result for the CPU flag ops.
 */
int lky_call(int op,
             uint8_t* const* regions,
             const int32_t* lengths,
             const int32_t* args,
             int region_count);

/*
 * WebAssembly entry point: the same operation, with regions packed into one
 * buffer. The buffer starts with an LKY_PACKED_MAGIC header:
 *
 *   int32 magic
 *   int32 args_count
 *   int32 region_count
 *   int32 reserved
 *   int32 args[LKY_ARGS_MAX]
 *   int32 offsets[LKY_REGIONS_MAX]
 *   int32 lengths[LKY_REGIONS_MAX]
 *   ... 16-byte aligned to LKY_PACKED_HEADER_SIZE, then region data
 *
 * offsets are relative to the start of the buffer. Returns the lky_call result.
 */
int lky_call_packed(int op, uint8_t* data, int size);

/* Allocation helpers (used by the WebAssembly bridge; also available to JNI). */
void* lky_alloc(size_t size);
void lky_free(void* ptr);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif  /* LIBYUV_KMP_H_ */
