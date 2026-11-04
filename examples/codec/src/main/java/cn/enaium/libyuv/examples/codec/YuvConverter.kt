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

package cn.enaium.libyuv.examples.codec

import androidx.camera.core.ImageProxy
import kotlin.math.min

/** One tightly packed I420 frame captured from CameraX. */
internal class I420Frame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val rotationDegrees: Int,
    val presentationTimeUs: Long,
)

/**
 * Copies a `YUV_420_888` [ImageProxy] into tightly packed I420 planes.
 *
 * CameraX may expose planar or semi-planar chroma; reading each chroma sample
 * through its own plane and pixel stride handles both layouts without having to
 * guess between NV12 and NV21.
 */
internal object YuvConverter {
    fun toI420(image: ImageProxy): I420Frame {
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0) { "empty camera frame" }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        val y = ByteArray(width * height)
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)

        copyPlane(image.planes[0], width, height, y, width)
        copyPlane(image.planes[1], chromaWidth, chromaHeight, u, chromaWidth)
        copyPlane(image.planes[2], chromaWidth, chromaHeight, v, chromaWidth)

        val timestamp = image.imageInfo.timestamp
        return I420Frame(
            width = width,
            height = height,
            y = y,
            u = u,
            v = v,
            rotationDegrees = ((image.imageInfo.rotationDegrees % 360) + 360) % 360,
            presentationTimeUs = if (timestamp > 0) timestamp / 1_000 else System.nanoTime() / 1_000,
        )
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationStride: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(pixelStride > 0) { "invalid pixel stride $pixelStride" }
        val lastRow = min(height, if (rowStride > 0) buffer.limit() / rowStride + 1 else height)
        for (row in 0 until lastRow) {
            val sourceBase = row * rowStride
            val destinationBase = row * destinationStride
            for (column in 0 until width) {
                val sourceIndex = sourceBase + column * pixelStride
                if (sourceIndex >= buffer.limit()) break
                destination[destinationBase + column] = buffer.get(sourceIndex)
            }
        }
    }
}
