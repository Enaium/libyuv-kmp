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

package cn.enaium.libyuv

import kotlin.js.js

// The glue is instantiated lazily: decoding and compiling the embedded module
// is the only expensive step, and it should not run for a process that never
// calls libyuv.
private val libyuvWebInitialized: Unit by lazy {
    js(LIBYUV_WEB_INIT_JS)(LIBYUV_WASM_BASE64)
}

internal actual fun webEnsureInitialized() {
    libyuvWebInitialized
}

internal actual fun webRawCall(op: Int, payload: String): String =
    js(LIBYUV_WEB_CALL_JS)(op, payload) as String

internal actual fun webRawVersion(): Int =
    js(LIBYUV_WEB_VERSION_JS)() as Int
