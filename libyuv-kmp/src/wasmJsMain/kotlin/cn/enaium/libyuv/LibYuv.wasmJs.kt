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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.libyuv

import kotlin.JsFun

private var libyuvWebInitialized = false

internal actual fun webEnsureInitialized() {
    if (!libyuvWebInitialized) {
        initLibyuvWeb(LIBYUV_WASM_BASE64)
        libyuvWebInitialized = true
    }
}

@JsFun(LIBYUV_WEB_INIT_JS)
private external fun initLibyuvWeb(wasmBase64: String)

@JsFun(LIBYUV_WEB_CALL_JS)
private external fun callLibyuvWeb(op: Int, payload: String): String

@JsFun(LIBYUV_WEB_VERSION_JS)
private external fun versionLibyuvWeb(): Int

internal actual fun webRawCall(op: Int, payload: String): String =
    callLibyuvWeb(op, payload)

internal actual fun webRawVersion(): Int =
    versionLibyuvWeb()
