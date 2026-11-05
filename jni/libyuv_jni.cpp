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

#include <jni.h>

#include <cstdint>

#include <vector>

#include "libyuv_kmp.h"

// ============================================================================
// Version
// ============================================================================

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_libyuv_JniLibYuv_nativeVersion(JNIEnv* env, jclass clazz) {
  return static_cast<jint>(lky_version());
}

// ============================================================================
// Generic operation bridge
//
// Kotlin packs every image plane into a ByteArray region and every scalar into
// an IntArray; this method pins the regions and forwards them to the shared C
// dispatch. Array release mode 0 copies results back (JNI_ABORT would discard
// them), which also means an input array is copied back unchanged - harmless
// and cheaper than tracking per-region direction here.
// ============================================================================

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_libyuv_JniLibYuv_nativeCall(
    JNIEnv* env,
    jclass clazz,
    jint op,
    jobjectArray regions,
    jintArray args) {
  if (regions == nullptr) {
    return -2;
  }

  const jsize region_count = env->GetArrayLength(regions);
  if (region_count < 0 || region_count > LKY_REGIONS_MAX) {
    return -2;
  }

  std::vector<jbyteArray> arrays(region_count, nullptr);
  std::vector<jbyte*> pinned(region_count, nullptr);
  std::vector<uint8_t*> pointers(region_count, nullptr);
  std::vector<int32_t> lengths(region_count, 0);

  jint result = -2;
  jint* args_data = nullptr;
  jsize args_count = 0;
  if (args != nullptr) {
    args_count = env->GetArrayLength(args);
    if (args_count < 0 || args_count > LKY_ARGS_MAX) {
      return -2;
    }
    if (args_count > 0) {
      args_data = env->GetIntArrayElements(args, nullptr);
      if (args_data == nullptr) {
        return -2;
      }
    }
  }

  bool ok = true;
  for (jsize i = 0; i < region_count; ++i) {
    arrays[i] =
        static_cast<jbyteArray>(env->GetObjectArrayElement(regions, i));
    if (arrays[i] == nullptr) {
      ok = false;
      break;
    }
    pinned[i] = env->GetByteArrayElements(arrays[i], nullptr);
    if (pinned[i] == nullptr) {
      ok = false;
      break;
    }
    pointers[i] = reinterpret_cast<uint8_t*>(pinned[i]);
    lengths[i] = static_cast<int32_t>(env->GetArrayLength(arrays[i]));
  }

  if (ok) {
    // int32_t and jint are both 32-bit on every JNI platform.
    result = static_cast<jint>(lky_call(
        static_cast<int>(op), pointers.data(), lengths.data(),
        reinterpret_cast<const int32_t*>(args_data),
        static_cast<int>(region_count)));
  }

  for (jsize i = 0; i < region_count; ++i) {
    if (pinned[i] != nullptr) {
      env->ReleaseByteArrayElements(arrays[i], pinned[i], 0);
    }
    if (arrays[i] != nullptr) {
      env->DeleteLocalRef(arrays[i]);
    }
  }

  if (args_data != nullptr) {
    env->ReleaseIntArrayElements(args, args_data, JNI_ABORT);
  }

  return result;
}
// ============================================================================
// Direct ByteBuffer bridge
//
// CameraX ImageProxy and MediaCodec Image planes are direct ByteBuffers. Passing
// their addresses straight to libyuv avoids copying a frame into Java arrays.
// ============================================================================
extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_libyuv_JniLibYuv_nativeCallDirect(
    JNIEnv* env,
    jclass clazz,
    jint op,
    jobjectArray buffers,
    jintArray args) {
  if (buffers == nullptr) {
    return -2;
  }
  const jsize count = env->GetArrayLength(buffers);
  if (count < 0 || count > LKY_REGIONS_MAX) {
    return -2;
  }

  std::vector<uint8_t*> pointers(count, nullptr);
  std::vector<int32_t> lengths(count, 0);
  bool ok = true;
  for (jsize i = 0; i < count; ++i) {
    jobject buffer = env->GetObjectArrayElement(buffers, i);
    if (buffer == nullptr) {
      ok = false;
      break;
    }
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    env->DeleteLocalRef(buffer);
    if (address == nullptr || capacity < 0 || capacity > INT32_MAX) {
      ok = false;
      break;
    }
    pointers[i] = static_cast<uint8_t*>(address);
    lengths[i] = static_cast<int32_t>(capacity);
  }

  jint* args_data = nullptr;
  if (ok && args != nullptr) {
    const jsize args_count = env->GetArrayLength(args);
    if (args_count < 0 || args_count > LKY_ARGS_MAX) {
      ok = false;
    } else if (args_count > 0) {
      args_data = env->GetIntArrayElements(args, nullptr);
      if (args_data == nullptr) {
        ok = false;
      }
    }
  }

  jint result = -2;
  if (ok) {
    result = static_cast<jint>(lky_call(
        static_cast<int>(op), pointers.data(), lengths.data(),
        reinterpret_cast<const int32_t*>(args_data), static_cast<int>(count)));
  }
  if (args_data != nullptr) {
    env->ReleaseIntArrayElements(args, args_data, JNI_ABORT);
  }
  return result;
}

