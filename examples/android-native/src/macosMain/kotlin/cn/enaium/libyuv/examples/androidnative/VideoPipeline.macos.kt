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

package cn.enaium.libyuv.examples.androidnative

import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import kotlin.math.abs

private const val WIDTH = 640
private const val HEIGHT = 360

private val BARS = intArrayOf(
    0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00,
    0xFF00FF, 0xFF0000, 0x0000FF, 0x000000,
)

/**
 * Desktop preview pipeline.
 *
 * Android's MediaCodec is not available on macOS, so this target only exercises
 * the same UI and the libyuv half of the loop with a generated source. It is
 * what lets the example be run and inspected without an Android device.
 */
private class DesktopVideoPipeline : VideoPipeline {
    private val rgb24 = ByteArray(WIDTH * HEIGHT * 3)
    private val sourceY = ByteArray(WIDTH * HEIGHT)
    private val sourceU = ByteArray(((WIDTH + 1) / 2) * ((HEIGHT + 1) / 2))
    private val sourceV = ByteArray(sourceU.size)
    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var rgba = ByteArray(0)
    private var latest: VideoFrame? = null
    private var animation = 0
    private var rotationIndex = 0
    private var lastRotation = -1

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = "synthetic ${WIDTH}x$HEIGHT RGB24 pattern (desktop preview)",
            path = "RGB24 -> I420 -> (rotate) -> RGBA; MediaCodec is Android-only",
            rotation = rotationName(),
            encodedFrames = 0,
            decodedFrames = animation.toLong(),
            error = null,
        )

    override fun pump() {
        animation++
        fillRgb24()
        LibYuv.rgb24ToI420(rgb24, sourceY, sourceU, sourceV, WIDTH, HEIGHT)

        val rotation = Rotation.entries[rotationIndex]
        val rotated = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270
        val outWidth = if (rotated) HEIGHT else WIDTH
        val outHeight = if (rotated) WIDTH else HEIGHT
        val y: ByteArray
        val u: ByteArray
        val v: ByteArray
        if (rotation == Rotation.NONE) {
            y = sourceY
            u = sourceU
            v = sourceV
        } else {
            if (lastRotation != rotationIndex) {
                rotatedY = ByteArray(outWidth * outHeight)
                rotatedU = ByteArray(((outWidth + 1) / 2) * ((outHeight + 1) / 2))
                rotatedV = ByteArray(rotatedU.size)
                lastRotation = rotationIndex
            }
            LibYuv.i420Rotate(
                sourceY, sourceU, sourceV,
                rotatedY, rotatedU, rotatedV,
                WIDTH, HEIGHT, rotation,
            )
            y = rotatedY
            u = rotatedU
            v = rotatedV
        }

        val size = outWidth * outHeight * 4
        if (rgba.size != size) rgba = ByteArray(size)
        LibYuv.i420ToRgba(y, u, v, rgba, outWidth, outHeight)
        latest = VideoFrame(outWidth, outHeight, rgba)
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        animation = 0
        rotationIndex = 0
        lastRotation = -1
        latest = null
    }

    override fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % Rotation.entries.size
    }

    override fun close() = Unit

    private fun fillRgb24() {
        val scanX = (animation * 4) % WIDTH
        var offset = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val bar = BARS[(x * BARS.size) / WIDTH]
                var r = (bar shr 16) and 0xFF
                var g = (bar shr 8) and 0xFF
                var b = bar and 0xFF
                if (abs(x - scanX) < 6) {
                    r = 255
                    g = 255
                    b = 255
                }
                rgb24[offset++] = r.toByte()
                rgb24[offset++] = g.toByte()
                rgb24[offset++] = b.toByte()
            }
        }
    }

    private fun rotationName(): String = when (Rotation.entries[rotationIndex]) {
        Rotation.NONE -> "0 deg"
        Rotation.CLOCKWISE_90 -> "90 deg"
        Rotation.ROTATE_180 -> "180 deg"
        Rotation.CLOCKWISE_270 -> "270 deg"
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = DesktopVideoPipeline()
