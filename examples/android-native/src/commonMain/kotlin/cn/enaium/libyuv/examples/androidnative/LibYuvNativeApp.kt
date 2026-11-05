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

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import kotlin.math.abs
import kotlin.math.min

/** Source image size the synthetic pattern is generated at. */
private const val WIDTH = 320
private const val HEIGHT = 180

/** One drawn block covers this many source pixels. */
private const val BLOCK = 8

/** RGB colours of the eight colour bars. */
private val BARS = intArrayOf(
    0xFFFFFF, // white
    0xFFFF00, // yellow
    0x00FFFF, // cyan
    0x00FF00, // green
    0xFF00FF, // magenta
    0xFF0000, // red
    0x0000FF, // blue
    0x000000, // black
)

/**
 * The UI on top of the libyuv pipeline:
 *
 *   RGB24 pattern -> libyuv RGB24ToI420 -> optional I420Rotate -> I420ToRGBA
 *
 * The RGBA bytes are sampled into ImGui filled rectangles. That keeps the
 * example on the public libyuv API and on the ordinary ImGui draw list; no
 * renderer-specific texture upload path is involved.
 */
internal fun runLibYuvExample(frames: Int = Int.MAX_VALUE): Boolean =
    ImGuiSdlHost.run("libyuv-kmp Android native", frames) { frame ->
        renderer.draw(frame)
    }

private val renderer = NativeRenderer()

private class NativeRenderer {
    private val rgb24 = ByteArray(WIDTH * HEIGHT * 3)
    private val srcY = ByteArray(WIDTH * HEIGHT)
    private val srcU = ByteArray(((WIDTH + 1) / 2) * ((HEIGHT + 1) / 2))
    private val srcV = ByteArray(srcU.size)

    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var rgba = ByteArray(0)

    private var patternIndex = 0
    private var rotationIndex = 0
    private var animationFrame = 0
    private val paused = booleanArrayOf(false)
    private var lastRotation = -1

    fun draw(frame: Int) {
        if (!paused[0]) animationFrame++

        fillRgb24()
        LibYuv.rgb24ToI420(rgb24, srcY, srcU, srcV, WIDTH, HEIGHT)

        val rotation = Rotation.entries[rotationIndex]
        val rotated = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270
        val outWidth = if (rotated) HEIGHT else WIDTH
        val outHeight = if (rotated) WIDTH else HEIGHT
        val y: ByteArray
        val u: ByteArray
        val v: ByteArray
        if (rotation == Rotation.NONE) {
            y = srcY
            u = srcU
            v = srcV
        } else {
            if (lastRotation != rotationIndex) {
                rotatedY = ByteArray(outWidth * outHeight)
                rotatedU = ByteArray(((outWidth + 1) / 2) * ((outHeight + 1) / 2))
                rotatedV = ByteArray(rotatedU.size)
                lastRotation = rotationIndex
            }
            LibYuv.i420Rotate(
                srcY, srcU, srcV,
                rotatedY, rotatedU, rotatedV,
                WIDTH, HEIGHT, rotation,
            )
            y = rotatedY
            u = rotatedU
            v = rotatedV
        }

        val outputBytes = outWidth * outHeight * 4
        if (rgba.size != outputBytes) rgba = ByteArray(outputBytes)
        LibYuv.i420ToRgba(y, u, v, rgba, outWidth, outHeight)

        ui(outWidth, outHeight)
    }

    private fun ui(outWidth: Int, outHeight: Int) {
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
        ImGui.begin("libyuv-kmp native", null, WINDOW_FLAGS)

        ImGui.text("libyuv-kmp native example")
        ImGui.text(
            "libyuv ${LibYuv.version}  |  RGB24 ${WIDTH}x$HEIGHT  ->  " +
                "I420  ->  ${rotationName()}  ->  RGBA ${outWidth}x$outHeight",
        )
        ImGui.separator()

        if (ImGui.button("pattern: ${if (patternIndex == 0) "colour bars" else "gradient"}")) {
            patternIndex = 1 - patternIndex
        }
        ImGui.sameLine()
        if (ImGui.button("rotate: ${rotationName()}")) {
            rotationIndex = (rotationIndex + 1) % Rotation.entries.size
        }
        ImGui.sameLine()
        ImGui.checkbox("pause animation", paused)
        ImGui.separator()

        val available = ImGui.getContentRegionAvail()
        val scale = min(available.x / outWidth, available.y / outHeight).coerceAtLeast(0f)
        val drawWidth = outWidth * scale
        val drawHeight = outHeight * scale
        val origin = ImGui.getCursorScreenPos()
        val drawList = ImGui.getWindowDrawList()

        if (scale > 0f) {
            var sy = 0
            while (sy < outHeight) {
                val blockHeight = min(BLOCK, outHeight - sy)
                var sx = 0
                while (sx < outWidth) {
                    val blockWidth = min(BLOCK, outWidth - sx)
                    val pixel = ((sy * outWidth) + sx) * 4
                    drawList.DrawRectFilled(
                        ImVec2(origin.x + sx * scale, origin.y + sy * scale),
                        ImVec2(
                            origin.x + (sx + blockWidth) * scale,
                            origin.y + (sy + blockHeight) * scale,
                        ),
                        rgbaColor(pixel),
                    )
                    sx += BLOCK
                }
                sy += BLOCK
            }
            drawList.DrawRect(
                origin,
                ImVec2(origin.x + drawWidth, origin.y + drawHeight),
                0xFF666666.toInt(),
                thickness = 1f,
            )
        }
        ImGui.dummy(ImVec2(drawWidth, drawHeight))
        ImGui.end()
    }

    /** Expands the 8 px wide colour bars to [WIDTH] and adds a moving scan line. */
    private fun fillRgb24() {
        val scanX = (animationFrame * 4) % WIDTH
        var offset = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                var r: Int
                var g: Int
                var b: Int
                if (patternIndex == 0) {
                    val bar = BARS[(x * BARS.size) / WIDTH]
                    r = (bar shr 16) and 0xFF
                    g = (bar shr 8) and 0xFF
                    b = bar and 0xFF
                    if (abs(x - scanX) < 6) {
                        r = 255
                        g = 255
                        b = 255
                    }
                } else {
                    r = (x * 255) / (WIDTH - 1)
                    g = (y * 255) / (HEIGHT - 1)
                    b = animationFrame and 0xFF
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

    /** Converts libyuv's RGBA bytes to ImGui's packed IM_COL32(R, G, B, A). */
    private fun rgbaColor(offset: Int): Int {
        val r = rgba[offset].toInt() and 0xFF
        val g = rgba[offset + 1].toInt() and 0xFF
        val b = rgba[offset + 2].toInt() and 0xFF
        val a = rgba[offset + 3].toInt() and 0xFF
        return r or (g shl 8) or (b shl 16) or (a shl 24)
    }

    private companion object {
        const val WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
            ImGuiWindowFlags.NO_NAV_FOCUS
    }
}
