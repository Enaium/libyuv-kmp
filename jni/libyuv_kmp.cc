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

#include "libyuv_kmp.h"

#include <stdlib.h>
#include <string.h>

#include "libyuv.h"

namespace {

struct PacketHeader {
  int32_t magic;
  int32_t args_count;
  int32_t region_count;
  int32_t reserved;
  int32_t args[LKY_ARGS_MAX];
  int32_t offsets[LKY_REGIONS_MAX];
  int32_t lengths[LKY_REGIONS_MAX];
};

static_assert(sizeof(PacketHeader) == LKY_PACKED_HEADER_SIZE,
              "packed header size must match the Kotlin protocol");

inline const uint8_t* Src(const uint8_t* const* regions, int index) {
  return regions[index];
}

inline uint8_t* Dst(uint8_t* const* regions, int index) {
  return regions[index];
}

int Dispatch(int op,
             uint8_t* const* r,
             const int32_t* lengths,
             const int32_t* a) {
  switch (op) {
    /* ------------------------------------------------------------------
     * Copies
     * ------------------------------------------------------------------ */
    case LKY_OP_I420_COPY:
      return libyuv::I420Copy(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                              a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                              Dst(r, 5), a[7], a[0], a[1]);
    case LKY_OP_I422_COPY:
      return libyuv::I422Copy(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                              a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                              Dst(r, 5), a[7], a[0], a[1]);
    case LKY_OP_I444_COPY:
      return libyuv::I444Copy(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                              a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                              Dst(r, 5), a[7], a[0], a[1]);
    case LKY_OP_NV12_COPY:
      return libyuv::NV12Copy(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                              a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_NV21_COPY:
      return libyuv::NV21Copy(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                              a[4], Dst(r, 3), a[5], a[0], a[1]);

    /* ------------------------------------------------------------------
     * I420 -> packed / planar  (args: w,h,srcY,srcU,srcV,dstStride)
     * ------------------------------------------------------------------ */
    case LKY_OP_I420_TO_ARGB:
      return libyuv::I420ToARGB(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_BGRA:
      return libyuv::I420ToBGRA(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_ABGR:
      return libyuv::I420ToABGR(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_RGBA:
      return libyuv::I420ToRGBA(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_RGB24:
      return libyuv::I420ToRGB24(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                 a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_RGB565:
      return libyuv::I420ToRGB565(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                  a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_YUY2:
      return libyuv::I420ToYUY2(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_I420_TO_UYVY:
      return libyuv::I420ToUYVY(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);

    /* I420 -> NV12/NV21/I422/I444:
     * args: w,h,srcY,srcU,srcV,dstY,dstU,dstV; regions srcY,U,V,dstY,U,V. */
    case LKY_OP_I420_TO_NV12:
      return libyuv::I420ToNV12(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                a[0], a[1]);
    case LKY_OP_I420_TO_NV21:
      return libyuv::I420ToNV21(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                a[0], a[1]);
    case LKY_OP_I420_TO_I422:
      return libyuv::I420ToI422(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                Dst(r, 5), a[7], a[0], a[1]);
    case LKY_OP_I420_TO_I444:
      return libyuv::I420ToI444(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                Dst(r, 5), a[7], a[0], a[1]);

    /* ------------------------------------------------------------------
     * NV12/NV21 -> planar (args: w,h,srcY,srcUV,dstY,dstU,dstV)
     * ------------------------------------------------------------------ */
    case LKY_OP_NV12_TO_I420:
      return libyuv::NV12ToI420(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                a[0], a[1]);
    case LKY_OP_NV21_TO_I420:
      return libyuv::NV21ToI420(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                a[0], a[1]);

    /* NV12/NV21 -> packed (args: w,h,srcY,srcUV,dstStride). */
    case LKY_OP_NV12_TO_ARGB:
      return libyuv::NV12ToARGB(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                                a[4], a[0], a[1]);
    case LKY_OP_NV21_TO_ARGB:
      return libyuv::NV21ToARGB(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                                a[4], a[0], a[1]);
    case LKY_OP_NV12_TO_RGB24:
      return libyuv::NV12ToRGB24(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                                 a[4], a[0], a[1]);
    case LKY_OP_NV21_TO_RGB24:
      return libyuv::NV21ToRGB24(Src(r, 0), a[2], Src(r, 1), a[3], Dst(r, 2),
                                 a[4], a[0], a[1]);

    /* ------------------------------------------------------------------
     * Packed -> I420 (args: w,h,srcStride,dstY,dstU,dstV)
     * ------------------------------------------------------------------ */
    case LKY_OP_ARGB_TO_I420:
      return libyuv::ARGBToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_BGRA_TO_I420:
      return libyuv::BGRAToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_ABGR_TO_I420:
      return libyuv::ABGRToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_RGBA_TO_I420:
      return libyuv::RGBAToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_RGB24_TO_I420:
      return libyuv::RGB24ToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                 a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_YUY2_TO_I420:
      return libyuv::YUY2ToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);
    case LKY_OP_UYVY_TO_I420:
      return libyuv::UYVYToI420(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], Dst(r, 3), a[5], a[0], a[1]);

    /* Packed -> packed (args: w,h,srcStride,dstStride). */
    case LKY_OP_ARGB_TO_RGB24:
      return libyuv::ARGBToRGB24(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);
    case LKY_OP_ARGB_TO_RGB565:
      return libyuv::ARGBToRGB565(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);
    case LKY_OP_RGB24_TO_ARGB:
      return libyuv::RGB24ToARGB(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);
    case LKY_OP_RGB565_TO_ARGB:
      return libyuv::RGB565ToARGB(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);

    /* ARGB -> NV12/NV21 (args: w,h,srcStride,dstY,dstUV). */
    case LKY_OP_ARGB_TO_NV12:
      return libyuv::ARGBToNV12(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], a[0], a[1]);
    case LKY_OP_ARGB_TO_NV21:
      return libyuv::ARGBToNV21(Src(r, 0), a[2], Dst(r, 1), a[3], Dst(r, 2),
                                a[4], a[0], a[1]);

    /* ------------------------------------------------------------------
     * FourCC conversions
     * ------------------------------------------------------------------ */
    case LKY_OP_CONVERT_TO_I420:
      return libyuv::ConvertToI420(
          Src(r, 0), static_cast<size_t>(lengths[0]), Dst(r, 1), a[0],
          Dst(r, 2), a[1], Dst(r, 3), a[2], a[3], a[4], a[5], a[6], a[7],
          a[8], static_cast<libyuv::RotationMode>(a[9]),
          static_cast<uint32_t>(a[10]));
    case LKY_OP_CONVERT_FROM_I420:
      return libyuv::ConvertFromI420(Src(r, 0), a[2], Src(r, 1), a[3],
                                     Src(r, 2), a[4], Dst(r, 3), a[5], a[0],
                                     a[1], static_cast<uint32_t>(a[6]));

    /* ------------------------------------------------------------------
     * Scaling
     * ------------------------------------------------------------------ */
    case LKY_OP_I420_SCALE:
      return libyuv::I420Scale(
          Src(r, 0), a[4], Src(r, 1), a[5], Src(r, 2), a[6], a[0], a[1],
          Dst(r, 3), a[7], Dst(r, 4), a[8], Dst(r, 5), a[9], a[2], a[3],
          static_cast<libyuv::FilterMode>(a[10]));
    case LKY_OP_ARGB_SCALE:
      return libyuv::ARGBScale(Src(r, 0), a[4], a[0], a[1], Dst(r, 1), a[5],
                               a[2], a[3],
                               static_cast<libyuv::FilterMode>(a[6]));
    case LKY_OP_SCALE_PLANE:
      return libyuv::ScalePlane(Src(r, 0), a[4], a[0], a[1], Dst(r, 1), a[5],
                                a[2], a[3],
                                static_cast<libyuv::FilterMode>(a[6]));
    case LKY_OP_NV12_SCALE:
      return libyuv::NV12Scale(Src(r, 0), a[4], Src(r, 1), a[5], a[0], a[1],
                               Dst(r, 2), a[6], Dst(r, 3), a[7], a[2], a[3],
                               static_cast<libyuv::FilterMode>(a[8]));

    /* ------------------------------------------------------------------
     * Rotation
     * ------------------------------------------------------------------ */
    case LKY_OP_I420_ROTATE:
      return libyuv::I420Rotate(
          Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2), a[4], Dst(r, 3), a[5],
          Dst(r, 4), a[6], Dst(r, 5), a[7], a[0], a[1],
          static_cast<libyuv::RotationMode>(a[8]));
    case LKY_OP_ARGB_ROTATE:
      return libyuv::ARGBRotate(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1],
                                static_cast<libyuv::RotationMode>(a[4]));
    case LKY_OP_ROTATE_PLANE:
      return libyuv::RotatePlane(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1],
                                 static_cast<libyuv::RotationMode>(a[4]));

    /* ------------------------------------------------------------------
     * Mirroring
     * ------------------------------------------------------------------ */
    case LKY_OP_I420_MIRROR:
      return libyuv::I420Mirror(Src(r, 0), a[2], Src(r, 1), a[3], Src(r, 2),
                                a[4], Dst(r, 3), a[5], Dst(r, 4), a[6],
                                Dst(r, 5), a[7], a[0], a[1]);
    case LKY_OP_ARGB_MIRROR:
      return libyuv::ARGBMirror(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);
    case LKY_OP_PLANE_MIRROR:
      return libyuv::I400Mirror(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);
    case LKY_OP_RGB24_MIRROR:
      return libyuv::RGB24Mirror(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);

    /* ------------------------------------------------------------------
     * Plane utilities
     * ------------------------------------------------------------------ */
    case LKY_OP_I420_RECT:
      return libyuv::I420Rect(Dst(r, 0), a[4], Dst(r, 1), a[5], Dst(r, 2),
                              a[6], a[0], a[1], a[2], a[3], a[7], a[8], a[9]);
    case LKY_OP_SET_PLANE:
      libyuv::SetPlane(Dst(r, 0), a[2], a[0], a[1], a[3]);
      return 0;
    case LKY_OP_COPY_PLANE:
      libyuv::CopyPlane(Src(r, 0), a[2], Dst(r, 1), a[3], a[0], a[1]);
      return 0;
    case LKY_OP_I420_CROP: {
      const int crop_x = a[0];
      const int crop_y = a[1];
      const int crop_w = a[2];
      const int crop_h = a[3];
      if ((crop_x | crop_y) & 1) {
        return -1;
      }
      const uint8_t* src_y = Src(r, 0) + crop_y * a[4] + crop_x;
      const uint8_t* src_u = Src(r, 1) + (crop_y / 2) * a[5] + crop_x / 2;
      const uint8_t* src_v = Src(r, 2) + (crop_y / 2) * a[6] + crop_x / 2;
      return libyuv::I420Copy(src_y, a[4], src_u, a[5], src_v, a[6], Dst(r, 3),
                              a[7], Dst(r, 4), a[8], Dst(r, 5), a[9], crop_w,
                              crop_h);
    }

    /* ------------------------------------------------------------------
     * CPU dispatch
     * ------------------------------------------------------------------ */
    case LKY_OP_MASK_CPU_FLAGS:
      return libyuv::MaskCpuFlags(a[0]);
    case LKY_OP_INIT_CPU_FLAGS:
      libyuv::InitCpuFlags();
      return 0;
    case LKY_OP_TEST_CPU_FLAG:
      return libyuv::TestCpuFlag(a[0]) ? 1 : 0;

    default:
      return -2;
  }
}

}  // namespace

extern "C" {

int lky_version(void) {
  return LIBYUV_VERSION;
}

int lky_call(int op,
             uint8_t* const* regions,
             const int32_t* lengths,
             const int32_t* args,
             int region_count) {
  if (region_count < 0 || region_count > LKY_REGIONS_MAX) {
    return -2;
  }
  if (region_count > 0 && (!regions || !lengths)) {
    return -2;
  }
  for (int i = 0; i < region_count; ++i) {
    if (lengths[i] < 0) {
      return -2;
    }
  }
  return Dispatch(op, regions, lengths, args);
}

int lky_call_packed(int op, uint8_t* data, int size) {
  if (!data || size < LKY_PACKED_HEADER_SIZE) {
    return -2;
  }
  PacketHeader header;
  memcpy(&header, data, sizeof(header));
  if (header.magic != LKY_PACKED_MAGIC || header.args_count < 0 ||
      header.args_count > LKY_ARGS_MAX || header.region_count < 0 ||
      header.region_count > LKY_REGIONS_MAX) {
    return -2;
  }

  uint8_t* regions[LKY_REGIONS_MAX] = {nullptr};
  int32_t lengths[LKY_REGIONS_MAX] = {0};
  for (int i = 0; i < header.region_count; ++i) {
    const int offset = header.offsets[i];
    const int length = header.lengths[i];
    if (offset < LKY_PACKED_HEADER_SIZE || length < 0 ||
        offset > size - length) {
      return -2;
    }
    regions[i] = data + offset;
    lengths[i] = length;
  }
  return Dispatch(op, regions, lengths, header.args);
}

void* lky_alloc(size_t size) {
  return malloc(size);
}

void lky_free(void* ptr) {
  free(ptr);
}

}  /* extern "C" */
