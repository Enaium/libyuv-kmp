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
 * Planar rows are copied in bulk. Interleaved chroma is copied row-wise and
 * converted by libyuv's `Android420ToI420`, which handles both NV12 and NV21
 * byte orders without per-pixel Kotlin loops.
 */
fun ImageProxy.toI420(): I420Frame = readYuv420(
    yPlane = planes[0].source(),
    uPlane = planes[1].source(),
    vPlane = planes[2].source(),
    width = width,
    height = height,
    rotationDegrees = imageInfo.rotationDegrees,
    presentationTimeUs = imageInfo.timestamp.let { if (it > 0) it / 1_000 else System.nanoTime() / 1_000 },
)

/** Copies an Android `YUV_420_888` [Image] into tightly packed I420 planes. */
fun Image.toI420(
    rotationDegrees: Int = 0,
    presentationTimeUs: Long = 0,
): I420Frame = readYuv420(
    yPlane = planes[0].source(),
    uPlane = planes[1].source(),
    vPlane = planes[2].source(),
    width = width,
    height = height,
    rotationDegrees = rotationDegrees,
    presentationTimeUs = presentationTimeUs,
)

/**
 * Writes tightly packed I420 planes into an encoder's writable `Image`.
 *
 * Y and planar chroma rows are copied in bulk. For interleaved (NV12/NV21)
 * chroma the codec's U/V byte order is detected once, libyuv produces the
 * packed interleaved plane, and each row is copied in bulk.
 */
fun Image.writeI420(frame: I420Frame) {
    require(width == frame.width && height == frame.height) {
        "image ${width}x$height does not match frame ${frame.width}x${frame.height}"
    }

    val yPlane = planes[0].source()
    if (yPlane.pixelStride == 1 && yPlane.rowStride >= frame.width) {
        writePackedPlane(yPlane, frame.y, frame.width, frame.height, frame.width)
    } else {
        writeScalarPlane(yPlane, frame.y, frame.width, frame.height, frame.width)
    }

    val chromaWidth = (frame.width + 1) / 2
    val chromaHeight = (frame.height + 1) / 2
    val uPlane = planes[1].source()
    val vPlane = planes[2].source()

    when {
        uPlane.pixelStride == 1 && vPlane.pixelStride == 1 &&
            uPlane.rowStride >= chromaWidth && vPlane.rowStride >= chromaWidth -> {
            writePackedPlane(uPlane, frame.u, chromaWidth, chromaHeight, chromaWidth)
            writePackedPlane(vPlane, frame.v, chromaWidth, chromaHeight, chromaWidth)
        }

        uPlane.pixelStride == 2 && vPlane.pixelStride == 2 &&
            frame.width % 2 == 0 &&
            writeInterleavedChroma(frame, uPlane, vPlane, chromaWidth, chromaHeight) -> Unit

        else -> {
            writeScalarPlane(uPlane, frame.u, chromaWidth, chromaHeight, chromaWidth)
            writeScalarPlane(vPlane, frame.v, chromaWidth, chromaHeight, chromaWidth)
        }
    }
}

private class PlaneSource(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

private fun ImageProxy.PlaneProxy.source(): PlaneSource =
    PlaneSource(buffer, rowStride, pixelStride)

private fun Image.Plane.source(): PlaneSource =
    PlaneSource(buffer, rowStride, pixelStride)

private fun readYuv420(
    yPlane: PlaneSource,
    uPlane: PlaneSource,
    vPlane: PlaneSource,
    width: Int,
    height: Int,
    rotationDegrees: Int,
    presentationTimeUs: Long,
): I420Frame {
    require(width > 0 && height > 0) { "empty image" }
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val sourceY = copyPackedPlane(yPlane, width, height)

    if (uPlane.pixelStride == 1 && vPlane.pixelStride == 1) {
        return I420Frame(
            width = width,
            height = height,
            y = sourceY,
            u = copyPackedPlane(uPlane, chromaWidth, chromaHeight),
            v = copyPackedPlane(vPlane, chromaWidth, chromaHeight),
            rotationDegrees = normalizeRotation(rotationDegrees),
            presentationTimeUs = presentationTimeUs,
        )
    }

    if (uPlane.pixelStride == 2 && vPlane.pixelStride == 2 &&
        uPlane.rowStride >= chromaWidth * 2 &&
        vPlane.rowStride >= chromaWidth * 2
    ) {
        val sourceU = copyPlaneRows(uPlane, chromaHeight)
        val sourceV = copyPlaneRows(vPlane, chromaHeight)
        val dstY = ByteArray(width * height)
        val dstU = ByteArray(chromaWidth * chromaHeight)
        val dstV = ByteArray(chromaWidth * chromaHeight)
        LibYuv.android420ToI420(
            srcY = sourceY,
            srcU = sourceU,
            srcV = sourceV,
            dstY = dstY,
            dstU = dstU,
            dstV = dstV,
            width = width,
            height = height,
            srcStrideY = width,
            srcStrideU = uPlane.rowStride,
            srcStrideV = vPlane.rowStride,
            srcPixelStrideUv = 2,
            dstStrideY = width,
            dstStrideU = chromaWidth,
            dstStrideV = chromaWidth,
        )
        return I420Frame(
            width = width,
            height = height,
            y = dstY,
            u = dstU,
            v = dstV,
            rotationDegrees = normalizeRotation(rotationDegrees),
            presentationTimeUs = presentationTimeUs,
        )
    }

    // Unusual mixed-plane layout: keep correctness, not the fast path.
    return I420Frame(
        width = width,
        height = height,
        y = sourceY,
        u = copyScalarPlane(uPlane, chromaWidth, chromaHeight),
        v = copyScalarPlane(vPlane, chromaWidth, chromaHeight),
        rotationDegrees = normalizeRotation(rotationDegrees),
        presentationTimeUs = presentationTimeUs,
    )
}

private fun normalizeRotation(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360

/** Copies a tightly packed (pixel stride 1) plane, one row at a time. */
private fun copyPackedPlane(plane: PlaneSource, width: Int, height: Int): ByteArray {
    val destination = ByteArray(width * height)
    if (plane.pixelStride == 1 && plane.rowStride >= width) {
        val source = plane.buffer.duplicate()
        val limit = plane.buffer.limit()
        for (row in 0 until height) {
            val sourcePosition = row * plane.rowStride
            val length = min(width, limit - sourcePosition)
            if (length <= 0) break
            source.position(sourcePosition)
            source.get(destination, row * width, length)
        }
    } else {
        copyScalarPlaneInto(plane, width, height, destination, width)
    }
    return destination
}

/** Copies every source row of an interleaved plane, including row padding. */
private fun copyPlaneRows(plane: PlaneSource, height: Int): ByteArray {
    val rowStride = plane.rowStride
    val destination = ByteArray(rowStride * height)
    val source = plane.buffer.duplicate()
    val limit = plane.buffer.limit()
    for (row in 0 until height) {
        val sourcePosition = row * rowStride
        val length = min(rowStride, limit - sourcePosition)
        if (length <= 0) break
        source.position(sourcePosition)
        source.get(destination, row * rowStride, length)
    }
    return destination
}

private fun copyScalarPlane(plane: PlaneSource, width: Int, height: Int): ByteArray {
    val destination = ByteArray(width * height)
    copyScalarPlaneInto(plane, width, height, destination, width)
    return destination
}

private fun copyScalarPlaneInto(
    plane: PlaneSource,
    width: Int,
    height: Int,
    destination: ByteArray,
    destinationStride: Int,
) {
    val buffer = plane.buffer
    val limit = buffer.limit()
    for (row in 0 until height) {
        val sourceBase = row * plane.rowStride
        val destinationBase = row * destinationStride
        for (column in 0 until width) {
            val sourceIndex = sourceBase + column * plane.pixelStride
            if (sourceIndex >= limit) break
            destination[destinationBase + column] = buffer.get(sourceIndex)
        }
    }
}

private fun writePackedPlane(
    plane: PlaneSource,
    source: ByteArray,
    width: Int,
    height: Int,
    sourceStride: Int,
) {
    require(plane.pixelStride == 1) { "expected pixel stride 1, was ${plane.pixelStride}" }
    require(plane.rowStride >= width) {
        "row stride ${plane.rowStride} is smaller than $width"
    }
    val target = plane.buffer.duplicate()
    for (row in 0 until height) {
        target.position(row * plane.rowStride)
        target.put(source, row * sourceStride, width)
    }
}

private fun writeScalarPlane(
    plane: PlaneSource,
    source: ByteArray,
    width: Int,
    height: Int,
    sourceStride: Int,
) {
    val buffer = plane.buffer
    for (row in 0 until height) {
        val sourceBase = row * sourceStride
        val destinationBase = row * plane.rowStride
        for (column in 0 until width) {
            buffer.put(destinationBase + column * plane.pixelStride, source[sourceBase + column])
        }
    }
}

/**
 * Detects whether an interleaved U/V pair is stored U-first (NV12) or V-first
 * (NV21). The two plane buffers share the codec memory, so a sentinel written
 * at one plane's second byte becomes visible at the other plane's first byte.
 * The sentinel is overwritten by the subsequent full chroma write.
 */
private fun detectUvOrder(uPlane: PlaneSource, vPlane: PlaneSource): UvOrder {
    if (uPlane.pixelStride != 2 || vPlane.pixelStride != 2) return UvOrder.UNKNOWN
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    if (uBuffer.limit() < 2 || vBuffer.limit() < 2) return UvOrder.UNKNOWN
    val twoChecks = uBuffer.limit() >= 4 && vBuffer.limit() >= 4
    return try {
        uBuffer.put(1, SENTINEL_A)
        if (twoChecks) uBuffer.put(3, SENTINEL_B)
        if (vBuffer.get(0) == SENTINEL_A &&
            (!twoChecks || vBuffer.get(2) == SENTINEL_B)
        ) {
            return UvOrder.U_FIRST
        }

        vBuffer.put(1, SENTINEL_A)
        if (twoChecks) vBuffer.put(3, SENTINEL_B)
        if (uBuffer.get(0) == SENTINEL_A &&
            (!twoChecks || uBuffer.get(2) == SENTINEL_B)
        ) {
            return UvOrder.V_FIRST
        }
        UvOrder.UNKNOWN
    } catch (_: Throwable) {
        UvOrder.UNKNOWN
    }
}

private fun writeInterleavedChroma(
    frame: I420Frame,
    uPlane: PlaneSource,
    vPlane: PlaneSource,
    chromaWidth: Int,
    chromaHeight: Int,
): Boolean {
    val order = detectUvOrder(uPlane, vPlane)
    if (order == UvOrder.UNKNOWN) return false
    val target = if (order == UvOrder.U_FIRST) uPlane else vPlane
    if (target.rowStride < frame.width) return false

    val scratchY = ByteArray(frame.width * frame.height)
    val interleaved = ByteArray(frame.width * chromaHeight)
    val converted = runCatching {
        if (order == UvOrder.U_FIRST) {
            LibYuv.i420ToNv12(
                frame.y, frame.u, frame.v,
                scratchY, interleaved,
                frame.width, frame.height,
            )
        } else {
            LibYuv.i420ToNv21(
                frame.y, frame.u, frame.v,
                scratchY, interleaved,
                frame.width, frame.height,
            )
        }
    }.isSuccess
    if (!converted) return false

    return try {
        val buffer = target.buffer.duplicate()
        for (row in 0 until chromaHeight) {
            buffer.position(row * target.rowStride)
            buffer.put(interleaved, row * frame.width, frame.width)
        }
        true
    } catch (_: Throwable) {
        false
    }
}

private enum class UvOrder { U_FIRST, V_FIRST, UNKNOWN }

private val SENTINEL_A: Byte = 0x5A
private val SENTINEL_B: Byte = 0xA5.toByte()
