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

/**
 * One I420 frame backed by direct ByteBuffers.
 *
 * Direct planes let libyuv read from camera planes and write into MediaCodec
 * `Image` planes without a Kotlin-side per-plane copy.
 */
class I420Frame internal constructor(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val rotationDegrees: Int,
    val presentationTimeUs: Long,
) {
    val chromaWidth: Int = (width + 1) / 2
    val chromaHeight: Int = (height + 1) / 2

    /** Rotates to a newly allocated direct I420 frame. */
    fun rotate(rotation: Rotation): I420Frame {
        if (rotation == Rotation.NONE) return this
        val swap = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270
        val destination = allocateDirect(
            width = if (swap) height else width,
            height = if (swap) width else height,
            rotationDegrees = 0,
            presentationTimeUs = presentationTimeUs,
        )
        invokeDirect(
            Op.I420Rotate,
            intArrayOf(
                width, height,
                width, chromaWidth, chromaWidth,
                destination.width, destination.chromaWidth, destination.chromaWidth,
                rotation.degrees,
            ),
            arrayOf(y, u, v, destination.y, destination.u, destination.v),
        )
        return destination
    }

    /**
     * Packs this frame into a direct buffer for a MediaCodec ByteBuffer input.
     *
     * This is the fallback path for codecs that do not expose a writable input
     * `Image`; the primary path writes directly into the codec's Image planes.
     */
    fun toPackedBuffer(fourCC: FourCC): ByteBuffer {
        val chroma = chromaWidth * chromaHeight
        return when (fourCC) {
            FourCC.I420 -> {
                val packed = ByteBuffer.allocateDirect(width * height + chroma * 2)
                val dstY = packed.sliceAt(0, width * height)
                val dstU = packed.sliceAt(width * height, chroma)
                val dstV = packed.sliceAt(width * height + chroma, chroma)
                invokeDirect(
                    Op.I420Copy,
                    intArrayOf(
                        width, height,
                        width, chromaWidth, chromaWidth,
                        width, chromaWidth, chromaWidth,
                    ),
                    arrayOf(y, u, v, dstY, dstU, dstV),
                )
                packed.clear()
                packed
            }

            FourCC.NV12, FourCC.NV21 -> {
                val uv = width * chromaHeight
                val packed = ByteBuffer.allocateDirect(width * height + uv)
                val dstY = packed.sliceAt(0, width * height)
                val dstUv = packed.sliceAt(width * height, uv)
                val op = if (fourCC == FourCC.NV12) Op.I420ToNv12 else Op.I420ToNv21
                invokeDirect(
                    op,
                    intArrayOf(
                        width, height,
                        width, chromaWidth, chromaWidth,
                        width, width,
                    ),
                    arrayOf(y, u, v, dstY, dstUv),
                )
                packed.clear()
                packed
            }

            else -> error("Unsupported packed I420 target: $fourCC")
        }
    }

    companion object {
        /** Allocates direct, tightly packed I420 planes. */
        fun allocateDirect(
            width: Int,
            height: Int,
            rotationDegrees: Int = 0,
            presentationTimeUs: Long = 0,
        ): I420Frame {
            require(width > 0 && height > 0) { "empty frame" }
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            return I420Frame(
                width = width,
                height = height,
                y = ByteBuffer.allocateDirect(width * height),
                u = ByteBuffer.allocateDirect(chromaWidth * chromaHeight),
                v = ByteBuffer.allocateDirect(chromaWidth * chromaHeight),
                rotationDegrees = normalizeRotation(rotationDegrees),
                presentationTimeUs = presentationTimeUs,
            )
        }
    }
}

/**
 * Converts a CameraX `YUV_420_888` frame directly into a direct I420 frame.
 *
 * Planar and interleaved chroma are both supported by libyuv; the source
 * ImageProxy buffers are used by pointer, not copied into Kotlin byte arrays.
 * The returned frame owns newly allocated direct planes, so it is safe to use
 * after the ImageProxy is closed.
 */
fun ImageProxy.toI420(): I420Frame {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    require(uPlane.pixelStride == vPlane.pixelStride) {
        "U/V pixel strides differ: ${uPlane.pixelStride}/${vPlane.pixelStride}"
    }
    return sourceToI420(
        srcY = yPlane.buffer,
        srcStrideY = yPlane.rowStride,
        srcU = uPlane.buffer,
        srcStrideU = uPlane.rowStride,
        srcV = vPlane.buffer,
        srcStrideV = vPlane.rowStride,
        srcPixelStrideUv = uPlane.pixelStride,
        width = width,
        height = height,
        rotationDegrees = imageInfo.rotationDegrees,
        presentationTimeUs = imageInfo.timestamp.let { if (it > 0) it / 1_000 else System.nanoTime() / 1_000 },
    )
}

/** Converts an Android `YUV_420_888` [Image] directly into a direct I420 frame. */
fun Image.toI420(
    rotationDegrees: Int = 0,
    presentationTimeUs: Long = 0,
): I420Frame {
    val uPlane = planes[1]
    val vPlane = planes[2]
    require(uPlane.pixelStride == vPlane.pixelStride) {
        "U/V pixel strides differ: ${uPlane.pixelStride}/${vPlane.pixelStride}"
    }
    return sourceToI420(
        srcY = planes[0].buffer,
        srcStrideY = planes[0].rowStride,
        srcU = uPlane.buffer,
        srcStrideU = uPlane.rowStride,
        srcV = vPlane.buffer,
        srcStrideV = vPlane.rowStride,
        srcPixelStrideUv = uPlane.pixelStride,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        presentationTimeUs = presentationTimeUs,
    )
}

/**
 * Writes an I420 frame into a writable MediaCodec input `Image`.
 *
 * Planar images use I420Copy; interleaved images use I420ToNV12/NV21 with the
 * detected U/V order. Source and destination are direct buffers, so libyuv
 * performs the transfer without a Kotlin-side plane copy.
 */
fun Image.writeI420(frame: I420Frame) {
    require(width == frame.width && height == frame.height) {
        "image ${width}x$height does not match frame ${frame.width}x${frame.height}"
    }
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    require(yPlane.pixelStride == 1) { "unsupported Y pixel stride ${yPlane.pixelStride}" }

    when {
        uPlane.pixelStride == 1 && vPlane.pixelStride == 1 -> {
            invokeDirect(
                Op.I420Copy,
                intArrayOf(
                    frame.width, frame.height,
                    frame.width, frame.chromaWidth, frame.chromaWidth,
                    yPlane.rowStride, uPlane.rowStride, vPlane.rowStride,
                ),
                arrayOf(frame.y, frame.u, frame.v, yPlane.buffer, uPlane.buffer, vPlane.buffer),
            )
        }

        uPlane.pixelStride == 2 && vPlane.pixelStride == 2 -> {
            val order = detectUvOrder(uPlane.buffer, vPlane.buffer)
            val target = when (order) {
                UvOrder.U_FIRST -> uPlane
                UvOrder.V_FIRST -> vPlane
                UvOrder.UNKNOWN -> error("Cannot determine NV12/NV21 order of the encoder Image")
            }
            val op = if (order == UvOrder.U_FIRST) Op.I420ToNv12 else Op.I420ToNv21
            invokeDirect(
                op,
                intArrayOf(
                    frame.width, frame.height,
                    frame.width, frame.chromaWidth, frame.chromaWidth,
                    yPlane.rowStride, target.rowStride,
                ),
                arrayOf(frame.y, frame.u, frame.v, yPlane.buffer, target.buffer),
            )
        }

        else -> error(
            "unsupported chroma pixel strides ${uPlane.pixelStride}/${vPlane.pixelStride}",
        )
    }
}

private fun sourceToI420(
    srcY: ByteBuffer,
    srcStrideY: Int,
    srcU: ByteBuffer,
    srcStrideU: Int,
    srcV: ByteBuffer,
    srcStrideV: Int,
    srcPixelStrideUv: Int,
    width: Int,
    height: Int,
    rotationDegrees: Int,
    presentationTimeUs: Long,
): I420Frame {
    require(width > 0 && height > 0) { "empty frame" }
    require(srcY.isDirect && srcU.isDirect && srcV.isDirect) {
        "ImageProxy/Image YUV planes must be direct ByteBuffers"
    }
    val frame = I420Frame.allocateDirect(width, height, rotationDegrees, presentationTimeUs)
    when (srcPixelStrideUv) {
        1 -> invokeDirect(
            Op.I420Copy,
            intArrayOf(
                width, height,
                srcStrideY, srcStrideU, srcStrideV,
                frame.width, frame.chromaWidth, frame.chromaWidth,
            ),
            arrayOf(srcY, srcU, srcV, frame.y, frame.u, frame.v),
        )

        2 -> invokeDirect(
            Op.Android420ToI420,
            intArrayOf(
                width, height,
                srcStrideY, srcStrideU, srcStrideV, 2,
                frame.width, frame.chromaWidth, frame.chromaWidth,
            ),
            arrayOf(srcY, srcU, srcV, frame.y, frame.u, frame.v),
        )

        else -> error("unsupported chroma pixel stride $srcPixelStrideUv")
    }
    return frame
}

private fun invokeDirect(op: Op, args: IntArray, regions: Array<ByteBuffer>) {
    regions.forEach { buffer ->
        require(buffer.isDirect) { "direct ByteBuffer required for ${op.name}" }
    }
    val result = JniLibYuv.nativeCallDirect(op.code, regions, args)
    if (result != 0) throw LibYuvException(op.name, result)
}

private fun ByteBuffer.sliceAt(offset: Int, length: Int): ByteBuffer {
    val duplicate = duplicate()
    duplicate.position(offset)
    duplicate.limit(offset + length)
    return duplicate.slice()
}

private enum class UvOrder { U_FIRST, V_FIRST, UNKNOWN }

/**
 * Detects whether an interleaved U/V pair is U-first (NV12) or V-first (NV21).
 * The U and V plane buffers share the codec memory, so a sentinel written at
 * one plane's second byte becomes visible at the other plane's first byte.
 * The sentinel is overwritten by the subsequent full chroma write.
 */
private fun detectUvOrder(uBuffer: ByteBuffer, vBuffer: ByteBuffer): UvOrder {
    if (!uBuffer.isDirect || !vBuffer.isDirect) return UvOrder.UNKNOWN
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

private fun normalizeRotation(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360

private val SENTINEL_A: Byte = 0x5A
private val SENTINEL_B: Byte = 0xA5.toByte()
