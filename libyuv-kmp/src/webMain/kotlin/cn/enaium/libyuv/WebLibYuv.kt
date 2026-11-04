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

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.libyuv

import kotlin.io.encoding.Base64

/**
 * Web bridge shared by the Kotlin/JS and Kotlin/Wasm targets.
 *
 * Each call packs the image regions into the same header the native C shim
 * parses (see `jni/libyuv_kmp.h`), base64-encodes the packet and hands it to a
 * synchronous JavaScript function that owns the WebAssembly instance. Using one
 * packet keeps the JavaScript glue tiny and lets the argument layout stay in
 * Kotlin, shared with the native and JNI bridges.
 */

internal const val LIBYUV_PACKED_HEADER_SIZE = 144
internal const val LIBYUV_PACKED_ALIGNMENT = 16
internal const val LIBYUV_PACKED_MAGIC = 0x4C4B5950

internal actual fun libyuvVersion(): Int = webVersion()

internal actual fun libyuvCall(op: Int, regions: Array<ByteArray>, args: IntArray): Int {
    require(args.size <= 16) { "at most 16 arguments per libyuv operation" }
    require(regions.size <= 8) { "at most 8 image regions per libyuv operation" }

    val offsets = IntArray(regions.size)
    var totalLong = LIBYUV_PACKED_HEADER_SIZE.toLong()
    for (index in regions.indices) {
        totalLong = align16(totalLong)
        offsets[index] = totalLong.toInt()
        totalLong += regions[index].size
    }
    totalLong = align16(totalLong)
    if (totalLong > MAX_PACKET_SIZE) {
        throw LibYuvException("web packet", LIBYUV_BRIDGE_ERROR)
    }
    val total = totalLong.toInt()

    val packet = ByteArray(total)
    writeInt(packet, 0, LIBYUV_PACKED_MAGIC)
    writeInt(packet, 4, args.size)
    writeInt(packet, 8, regions.size)
    // Offset 12 is reserved.
    args.forEachIndexed { index, value -> writeInt(packet, 16 + index * 4, value) }
    offsets.forEachIndexed { index, value -> writeInt(packet, 80 + index * 4, value) }
    regions.forEachIndexed { index, region -> writeInt(packet, 112 + index * 4, region.size) }
    regions.forEachIndexed { index, region -> region.copyInto(packet, offsets[index]) }

    webEnsureInitialized()
    val response = webRawCall(op, Base64.Default.encode(packet))
    val separator = response.indexOf(':')
    if (separator < 0) return LIBYUV_BRIDGE_ERROR
    val code = response.substring(0, separator).toIntOrNull() ?: return LIBYUV_BRIDGE_ERROR
    if (code != 0) return code

    val output = Base64.Default.decode(response.substring(separator + 1))
    if (output.size < total) return LIBYUV_BRIDGE_ERROR
    regions.forEachIndexed { index, region ->
        output.copyInto(region, 0, offsets[index], offsets[index] + region.size)
    }
    return 0
}

private fun webVersion(): Int {
    webEnsureInitialized()
    return webRawVersion()
}

private fun align16(value: Long): Long = (value + 15L) and 15L.inv()

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

internal expect fun webEnsureInitialized()

internal expect fun webRawCall(op: Int, payload: String): String

internal expect fun webRawVersion(): Int

/**
 * JavaScript initializer. It decodes the embedded module, instantiates it
 * synchronously (the module imports only a no-op memory-growth callback) and
 * publishes a call/version API on `globalThis`.
 */
internal const val LIBYUV_WEB_INIT_JS: String = """
(b64) => {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const module = new WebAssembly.Module(bytes);
  const instance = new WebAssembly.Instance(module, {
    env: { emscripten_notify_memory_growth() {} }
  });
  const exports = instance.exports;
  if (exports._initialize) exports._initialize();
  const decode = (text) => {
    const raw = atob(text);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  };
  const encode = (data) => {
    let text = "";
    const chunk = 0x8000;
    for (let i = 0; i < data.length; i += chunk) {
      text += String.fromCharCode.apply(null, data.subarray(i, i + chunk));
    }
    return btoa(text);
  };
  globalThis.__libyuvWeb = {
    call(op, payload) {
      const raw = decode(payload);
      const pointer = exports.lky_alloc(raw.length);
      if (!pointer) return "-2:";
      new Uint8Array(exports.memory.buffer, pointer, raw.length).set(raw);
      const result = exports.lky_call_packed(op, pointer, raw.length);
      const output = new Uint8Array(exports.memory.buffer, pointer, raw.length).slice();
      exports.lky_free(pointer);
      return result + ":" + encode(output);
    },
    version() {
      return exports.lky_version();
    }
  };
}
"""

internal const val LIBYUV_WEB_CALL_JS: String =
    "(op, payload) => globalThis.__libyuvWeb.call(op, payload)"

internal const val LIBYUV_WEB_VERSION_JS: String =
    "() => globalThis.__libyuvWeb.version()"
