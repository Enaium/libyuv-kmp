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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.libyuv

import kotlinx.cinterop.*
import libyuv_kmp.lky_call
import libyuv_kmp.lky_version

actual fun libyuvVersion(): Int = lky_version()

actual fun libyuvCall(op: Int, regions: Array<ByteArray>, args: IntArray): Int {
    val pinned = ArrayList<Pinned<ByteArray>>(regions.size)
    try {
        for (region in regions) {
            pinned.add(region.pin())
        }
        return memScoped {
            val count = regions.size
            val slotCount = if (count == 0) 1 else count
            val pointers = allocArray<CPointerVar<UByteVar>>(slotCount)
            val lengths = allocArray<IntVar>(slotCount)
            for (index in 0 until count) {
                pointers[index] = pinned[index].addressOf(0).reinterpret()
                lengths[index] = regions[index].size
            }
            if (args.isEmpty()) {
                lky_call(op, pointers, lengths, null, count)
            } else {
                args.usePinned { argsPinned ->
                    lky_call(op, pointers, lengths, argsPinned.addressOf(0), count)
                }
            }
        }
    } finally {
        for (entry in pinned) {
            entry.unpin()
        }
    }
}
