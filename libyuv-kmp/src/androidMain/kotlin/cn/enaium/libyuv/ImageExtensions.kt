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

import android.media.Image
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * One tightly packed I420 frame.
 *
 * The plane arrays are sized for [width] x [height] luma and
 * `ceil(width / 2) x ceil(height / 2)` chroma, tightly packed.
 */
class I420Frame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val rotationDegrees: Int = 0,
    val presentationTimeUs: Long = 0,
)

/**
 * Copies a CameraX `YUV_420_888` frame into tightly packed I420 planes.
 *
 * CameraX may expose planar or semi-planar chroma; reading each chroma sample
 * through its own plane and pixel stride handles both layouts without having to
 * guess between NV12 and NV21.
 */
fun ImageProxy.toI420(): I420Frame {
    val frameWidth = width
    val frameHeight = height
    require(frameWidth > 0 && frameHeight > 0) { "empty camera frame" }
    val chromaWidth = (frameWidth + 1) / 2
    val chromaHeight = (frameHeight + 1) / 2

    val y = ByteArray(frameWidth * frameHeight)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)

    copyPlane(planes[0], frameWidth, frameHeight, y, frameWidth)
    copyPlane(planes[1], chromaWidth, chromaHeight, u, chromaWidth)
    copyPlane(planes[2], chromaWidth, chromaHeight, v, chromaWidth)

    val timestamp = imageInfo.timestamp
    return I420Frame(
        width = frameWidth,
        height = frameHeight,
        y = y,
        u = u,
        v = v,
        rotationDegrees = ((imageInfo.rotationDegrees % 360) + 360) % 360,
        presentationTimeUs = if (timestamp > 0) timestamp / 1_000 else System.nanoTime() / 1_000,
    )
}

/** Copies an Android `YUV_420_888` [Image] into tightly packed I420 planes. */
fun Image.toI420(
    rotationDegrees: Int = 0,
    presentationTimeUs: Long = 0,
): I420Frame {
    require(width > 0 && height > 0) { "empty image" }
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2

    val y = ByteArray(width * height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)

    copyPlane(planes[0], width, height, y, width)
    copyPlane(planes[1], chromaWidth, chromaHeight, u, chromaWidth)
    copyPlane(planes[2], chromaWidth, chromaHeight, v, chromaWidth)

    return I420Frame(
        width = width,
        height = height,
        y = y,
        u = u,
        v = v,
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        presentationTimeUs = presentationTimeUs,
    )
}

/**
 * Writes tightly packed I420 planes into an encoder's writable `Image`.
 *
 * The image's row and pixel strides are respected, so codecs that require
 * aligned input strides receive a correctly laid out buffer.
 */
fun Image.writeI420(frame: I420Frame) {
    require(width == frame.width && height == frame.height) {
        "image ${width}x$height does not match frame ${frame.width}x${frame.height}"
    }
    copyPlane(planes[0], frame.y, frame.width, frame.height, frame.width)
    val chromaWidth = (frame.width + 1) / 2
    val chromaHeight = (frame.height + 1) / 2
    copyPlane(planes[1], frame.u, chromaWidth, chromaHeight, chromaWidth)
    copyPlane(planes[2], frame.v, chromaWidth, chromaHeight, chromaWidth)
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    destination: ByteArray,
    destinationStride: Int,
) {
    copyPlane(
        buffer = plane.buffer,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        width = width,
        height = height,
        destination = destination,
        destinationStride = destinationStride,
    )
}

private fun copyPlane(
    plane: Image.Plane,
    width: Int,
    height: Int,
    destination: ByteArray,
    destinationStride: Int,
) {
    copyPlane(
        buffer = plane.buffer,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        width = width,
        height = height,
        destination = destination,
        destinationStride = destinationStride,
    )
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    destination: ByteArray,
    destinationStride: Int,
) {
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

private fun copyPlane(
    plane: Image.Plane,
    source: ByteArray,
    width: Int,
    height: Int,
    sourceStride: Int,
) {
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    for (row in 0 until height) {
        val sourceBase = row * sourceStride
        val destinationBase = row * rowStride
        for (column in 0 until width) {
            buffer.put(destinationBase + column * pixelStride, source[sourceBase + column])
        }
    }
}
