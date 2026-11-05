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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibYuvTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun u(value: Byte): Int = value.toInt() and 0xFF

    private fun ByteArray.argbPixel(pixel: Int): IntArray {
        val offset = pixel * 4
        return intArrayOf(u(this[offset]), u(this[offset + 1]), u(this[offset + 2]), u(this[offset + 3]))
    }

    @Test
    fun versionIsReported() {
        assertTrue(LibYuv.version > 0, "unexpected libyuv version ${LibYuv.version}")
    }

    @Test
    fun i420ToArgbConvertsLimitedRangeLuma() {
        val width = 4
        val height = 4
        val y = ByteArray(width * height) { 235.toByte() }
        val uv = ByteArray((width / 2) * (height / 2)) { 128.toByte() }
        val argb = ByteArray(width * height * 4)

        LibYuv.i420ToArgb(y, uv, uv, argb, width, height)

        repeat(width * height) { pixel ->
            assertContentEquals(intArrayOf(255, 255, 255, 255), argb.argbPixel(pixel))
        }
    }

    @Test
    fun i420ToArgbBlackIsOpaque() {
        val y = ByteArray(4) { 16.toByte() }
        val uv = ByteArray(1) { 128.toByte() }
        val argb = ByteArray(16)

        LibYuv.i420ToArgb(y, uv, uv, argb, 2, 2)

        repeat(4) { pixel ->
            assertContentEquals(intArrayOf(0, 0, 0, 255), argb.argbPixel(pixel))
        }
    }

    @Test
    fun argbToI420RoundTripsGrayscale() {
        val width = 4
        val height = 4
        val argb = ByteArray(width * height * 4)
        repeat(width * height) { pixel ->
            val offset = pixel * 4
            argb[offset] = 200.toByte()
            argb[offset + 1] = 200.toByte()
            argb[offset + 2] = 200.toByte()
            argb[offset + 3] = 255.toByte()
        }
        val y = ByteArray(width * height)
        val u = ByteArray((width / 2) * (height / 2))
        val v = ByteArray((width / 2) * (height / 2))

        LibYuv.argbToI420(argb, y, u, v, width, height)

        assertTrue(y.all { u(it) in 180..200 }, "unexpected luma ${y.map(::u)}")
        assertTrue(u.all { u(it) in 125..131 }, "unexpected U ${u.map(::u)}")
        assertTrue(v.all { u(it) in 125..131 }, "unexpected V ${v.map(::u)}")

        val roundTripped = ByteArray(argb.size)
        LibYuv.i420ToArgb(y, u, v, roundTripped, width, height)
        repeat(width * height) { pixel ->
            val rgb = roundTripped.argbPixel(pixel)
            assertTrue(rgb[0] in 197..203 && rgb[1] in 197..203 && rgb[2] in 197..203, "pixel $pixel -> ${rgb.toList()}")
            assertEquals(255, rgb[3])
        }
    }

    @Test
    fun nv12ToI420SplitsInterleavedChroma() {
        val width = 2
        val height = 2
        val nv12Y = bytes(10, 20, 30, 40)
        val nv12Uv = bytes(100, 200, 101, 201)
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)

        LibYuv.nv12ToI420(nv12Y, nv12Uv, y, u, v, width, height)

        assertContentEquals(bytes(10, 20, 30, 40), y)
        assertContentEquals(bytes(100), u)
        assertContentEquals(bytes(200), v)
    }

    @Test
    fun i420ToNv12InterleavesChroma() {
        val width = 2
        val height = 2
        val y = bytes(10, 20, 30, 40)
        val u = bytes(100)
        val v = bytes(200)
        val nv12Y = ByteArray(4)
        val nv12Uv = ByteArray(2)

        LibYuv.i420ToNv12(y, u, v, nv12Y, nv12Uv, width, height)

        assertContentEquals(bytes(10, 20, 30, 40), nv12Y)
        assertContentEquals(bytes(100, 200), nv12Uv)
    }

    @Test
    fun android420ToI420HandlesPlanarAndInterleavedChroma() {
        val width = 2
        val height = 2
        val srcY = bytes(10, 20, 30, 40)

        val planarY = ByteArray(4)
        val planarU = ByteArray(1)
        val planarV = ByteArray(1)
        LibYuv.android420ToI420(
            srcY, bytes(100), bytes(200),
            planarY, planarU, planarV,
            width, height,
            srcPixelStrideUv = 1,
        )
        assertContentEquals(srcY, planarY)
        assertContentEquals(bytes(100), planarU)
        assertContentEquals(bytes(200), planarV)

        val interleavedY = ByteArray(4)
        val interleavedU = ByteArray(1)
        val interleavedV = ByteArray(1)
        LibYuv.android420ToI420(
            srcY,
            bytes(100, 200),
            bytes(200, 100),
            interleavedY, interleavedU, interleavedV,
            width, height,
            srcStrideU = 2,
            srcStrideV = 2,
            srcPixelStrideUv = 2,
        )
        assertContentEquals(srcY, interleavedY)
        assertContentEquals(bytes(100), interleavedU)
        assertContentEquals(bytes(200), interleavedV)
    }

    @Test
    fun convertFromI420UsesFourCcStride() {
        val width = 2
        val height = 2
        val y = bytes(10, 20, 30, 40)
        val u = bytes(100)
        val v = bytes(200)
        val nv12 = ByteArray(width * height + width * (height / 2))

        LibYuv.convertFromI420(y, u, v, nv12, width, height, FourCC.NV12)

        assertContentEquals(bytes(10, 20, 30, 40, 100, 200), nv12)
    }

    @Test
    fun scaleKeepsUniformImage() {
        val srcY = ByteArray(16) { 235.toByte() }
        val srcUv = ByteArray(4) { 128.toByte() }
        val dstY = ByteArray(4)
        val dstUv = ByteArray(1)

        LibYuv.i420Scale(
            srcY, srcUv, srcUv, dstY, dstUv, dstUv,
            4, 4, 2, 2,
            filterMode = FilterMode.BILINEAR,
        )

        assertContentEquals(bytes(235, 235, 235, 235), dstY)
        assertEquals(128, u(dstUv[0]))
    }

    @Test
    fun rotate90SwapsAxes() {
        val srcY = bytes(1, 2, 3, 4)
        val uv = bytes(128)
        val dstY = ByteArray(4)
        val dstUv = ByteArray(1)

        LibYuv.i420Rotate(
            srcY, uv, uv, dstY, dstUv, dstUv,
            2, 2, Rotation.CLOCKWISE_90,
        )

        assertContentEquals(bytes(3, 1, 4, 2), dstY)
    }

    @Test
    fun mirrorFlipsHorizontally() {
        val srcY = bytes(1, 2, 3, 4)
        val uv = bytes(128)
        val dstY = ByteArray(4)
        val dstUv = ByteArray(1)

        LibYuv.i420Mirror(srcY, uv, uv, dstY, dstUv, dstUv, 2, 2)

        assertContentEquals(bytes(2, 1, 4, 3), dstY)
    }

    @Test
    fun cropCopiesSubRectangle() {
        val srcY = ByteArray(16) { it.toByte() }
        val srcUv = bytes(100, 101, 102, 103)
        val dstY = ByteArray(4)
        val dstUv = ByteArray(1)

        LibYuv.i420Crop(
            srcY, srcUv, srcUv, dstY, dstUv, dstUv,
            4, 4, 2, 2, 2, 2,
        )

        assertContentEquals(bytes(10, 11, 14, 15), dstY)
        assertContentEquals(bytes(103), dstUv)
    }

    @Test
    fun setAndCopyPlane() {
        val plane = ByteArray(6)
        LibYuv.setPlane(plane, 3, 2, 7)
        assertContentEquals(bytes(7, 7, 7, 7, 7, 7), plane)

        val copy = ByteArray(6)
        LibYuv.copyPlane(plane, copy, 3, 2)
        assertContentEquals(plane, copy)
    }

    @Test
    fun i420RectDrawsOnlyInsideRectangle() {
        val y = ByteArray(16)
        val u = ByteArray(4)
        val v = ByteArray(4)

        LibYuv.i420Rect(y, u, v, 2, 2, 2, 2, 50, 60, 70, 4)

        assertContentEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 0, 0, 50, 50), y)
        assertContentEquals(bytes(0, 0, 0, 60), u)
        assertContentEquals(bytes(0, 0, 0, 70), v)
    }

    @Test
    fun convertToI420FromNv12() {
        val nv12Y = bytes(10, 20, 30, 40)
        val nv12Uv = bytes(100, 200)
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)

        LibYuv.convertToI420(
            nv12Y + nv12Uv, y, u, v,
            srcWidth = 2, srcHeight = 2,
            fourCC = FourCC.NV12,
        )

        assertContentEquals(bytes(10, 20, 30, 40), y)
        assertContentEquals(bytes(100), u)
        assertContentEquals(bytes(200), v)
    }

    @Test
    fun rejectsSmallDestinationBuffers() {
        assertFailsWith<IllegalArgumentException> {
            LibYuv.i420ToArgb(
                ByteArray(4), ByteArray(1), ByteArray(1), ByteArray(3),
                2, 2,
            )
        }
    }

    @Test
    fun cpuFlagsCanBeQueried() {
        LibYuv.initCpuFlags()
        LibYuv.maskCpuFlags(0)
        // The result is host-specific; the call itself must not fail.
        LibYuv.testCpuFlag(CpuFlag.HAS_SSE2)
    }
}
