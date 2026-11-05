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

package cn.enaium.libyuv.examples.androidnative

import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import cn.enaium.sdl.SDLCameraPosition
import cn.enaium.sdl.SDLCameras
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.nativePtr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.plus
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import cnames.structs.AMediaCodec
import platform.media.AMediaCodecBufferInfo
import platform.media.AMediaCodec_configure
import platform.media.AMediaCodec_createDecoderByType
import platform.media.AMediaCodec_createEncoderByType
import platform.media.AMediaCodec_dequeueInputBuffer
import platform.media.AMediaCodec_dequeueOutputBuffer
import platform.media.AMediaCodec_delete
import platform.media.AMediaCodec_getInputBuffer
import platform.media.AMediaCodec_getOutputBuffer
import platform.media.AMediaCodec_getOutputFormat
import platform.media.AMediaCodec_queueInputBuffer
import platform.media.AMediaCodec_releaseOutputBuffer
import platform.media.AMediaCodec_start
import platform.media.AMediaCodec_stop
import cnames.structs.AMediaFormat
import platform.media.AMediaFormat_delete
import platform.media.AMediaFormat_getInt32
import platform.media.AMediaFormat_new
import platform.media.AMediaFormat_setInt32
import platform.media.AMediaFormat_setString
import platform.media.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
import platform.media.AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
import platform.media.AMEDIACODEC_CONFIGURE_FLAG_ENCODE
import platform.media.AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED
import platform.media.AMEDIACODEC_INFO_TRY_AGAIN_LATER
import platform.posix.memcpy
import platform.posix.size_tVar
import sdl3.SDL_AcquireCameraFrame
import cnames.structs.SDL_Camera
import sdl3.SDL_CameraSpec
import sdl3.SDL_CloseCamera
import sdl3.SDL_GetCameraFormat
import sdl3.SDL_GetCameraSupportedFormats
import sdl3.SDL_OpenCamera
import sdl3.SDL_ReleaseCameraFrame
import sdl3.SDL_Surface
import sdl3.SDL_free
import kotlin.math.max

private const val MIME_AVC = "video/avc"

/** MediaCodecInfo.CodecCapabilities color formats, from the Android SDK. */
private const val COLOR_YUV420_PLANAR = 19
private const val COLOR_YUV420_SEMI_PLANAR = 21
private const val COLOR_YUV420_FLEXIBLE = 0x7F420888

/** SDL3 fourCC pixel formats the camera may produce. */
private const val SDL_NV12 = 0x3231564E
private const val SDL_NV21 = 0x3132564E
private const val SDL_YV12 = 0x32315659
private const val SDL_IYUV = 0x56555949
private const val SDL_YUY2 = 0x32595559
private const val SDL_UYVY = 0x59565955

/** Microseconds between two encoder frames at 30 fps. */
private const val FRAME_INTERVAL_US = 33_333L

/** A bounded queue of encoder output waiting for a decoder input buffer. */
private data class EncodedPacket(val data: ByteArray, val flags: Int, val ptsUs: Long)

/**
 * The Android native loopback:
 *
 *   SDL camera (YUV/RGB) -> libyuv -> I420 -> libyuv -> NV12/I420
 *     -> MediaCodec H.264 encoder -> MediaCodec H.264 decoder
 *     -> libyuv -> RGBA -> ImGui draw list
 *
 * All calls happen on the SDL thread. Camera acquisition and both codecs are
 * non-blocking: a frame that is not ready is simply skipped.
 */
private class AndroidVideoPipeline : VideoPipeline {
    private var camera: CPointer<SDL_Camera>? = null
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var cameraFormat = 0
    private var encoder: CPointer<AMediaCodec>? = null
    private var decoder: CPointer<AMediaCodec>? = null
    private var encoderColor = 0
    private var decoderColor = COLOR_YUV420_SEMI_PLANAR
    private var decoderStride = 0
    private var decoderSliceHeight = 0
    private var encodedWidth = 0
    private var encodedHeight = 0

    private var sourceY = ByteArray(0)
    private var sourceU = ByteArray(0)
    private var sourceV = ByteArray(0)
    private var scaledY = ByteArray(0)
    private var scaledU = ByteArray(0)
    private var scaledV = ByteArray(0)
    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var encoderY = ByteArray(0)
    private var encoderUv = ByteArray(0)
    private var encoderU = ByteArray(0)
    private var encoderV = ByteArray(0)
    private var decodedY = ByteArray(0)
    private var decodedU = ByteArray(0)
    private var decodedV = ByteArray(0)

    private var latest: VideoFrame? = null
    private var started = false
    private var retryAt = 0uL
    private var error: String? = null
    private var encodedFrames = 0L
    private var decodedFrames = 0L
    private var presentationTimeUs = 0L
    private var rotationIndex = 0
    private val packets = ArrayDeque<EncodedPacket>()

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = if (camera != null) {
                "camera ${cameraWidth}x$cameraHeight ${formatName(cameraFormat)}"
            } else {
                "camera not open"
            },
            path = "camera -> I420 -> ${frameWidth}x$frameHeight -> H.264 encode " +
                "(${encoderName()}) -> H.264 decode -> I420 -> RGBA",
            rotation = rotationName(),
            encodedFrames = encodedFrames,
            decodedFrames = decodedFrames,
            error = error,
        )

    override fun pump() {
        if (!started) {
            val now = SDL.getTicks()
            if (now < retryAt) return
            if (!start()) {
                retryAt = SDL.getTicks() + 2_000u
                return
            }
        }
        try {
            pumpCamera()
            drainEncoder()
            feedDecoder()
            drainDecoder()
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        close()
        latest = null
        encodedFrames = 0
        decodedFrames = 0
        presentationTimeUs = 0
        retryAt = 0u
        error = null
    }

    override fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % Rotation.entries.size
        restart()
    }

    override fun close() {
        camera?.let { SDL_CloseCamera(it) }
        camera = null
        closeCodecs()
        started = false
    }

    private fun start(): Boolean {
        return try {
            startCamera()
            chooseTargetSize()
            encodedWidth = if (rotated()) frameHeight else frameWidth
            encodedHeight = if (rotated()) frameWidth else frameHeight
            allocateSourceBuffers()
            allocateFrameBuffers()
            startEncoder()
            startDecoder()
            started = true
            error = null
            true
        } catch (t: Throwable) {
            close()
            error = t.message ?: t.toString()
            false
        }
    }

    private fun startCamera() {
        if (SDL.wasInit(SDLInitFlags.CAMERA) == 0) {
            if (!SDL.initSubSystem(SDLInitFlags.CAMERA)) {
                throw IllegalStateException("SDL camera init failed: ${SDL.error()}")
            }
        }
        val devices = SDLCameras.devices
        if (devices.isEmpty()) throw IllegalStateException("no camera found")
        val device = devices.firstOrNull {
            SDLCameras.getDevicePosition(it) == SDLCameraPosition.BACK_FACING
        } ?: devices.first()

        var opened: CPointer<SDL_Camera>? = null
        memScoped {
            val count = alloc<IntVar>()
            val formats = SDL_GetCameraSupportedFormats(device.toUInt(), count.ptr)
            if (formats != null) {
                var chosen: CPointer<SDL_CameraSpec>? = null
                for (i in 0 until count.value) {
                    val candidate = formats[i] ?: continue
                    val spec = candidate.pointed
                    val format = spec.format.toInt()
                    if (format == SDL_NV12 && spec.width == 640 && spec.height == 480) {
                        chosen = candidate
                        break
                    }
                    if (chosen == null && format == SDL_NV12 &&
                        spec.width <= 1280 && spec.height <= 720
                    ) {
                        chosen = candidate
                    }
                }
                if (chosen != null) {
                    opened = SDL_OpenCamera(device.toUInt(), chosen)
                }
                SDL_free(formats)
            }
        }
        if (opened == null) {
            opened = SDL_OpenCamera(device.toUInt(), null)
        }
        val cam = opened ?: throw IllegalStateException("SDL_OpenCamera failed: ${SDL.error()}")
        camera = cam

        memScoped {
            val spec = alloc<SDL_CameraSpec>()
            if (SDL_GetCameraFormat(cam, spec.ptr)) {
                cameraWidth = spec.width
                cameraHeight = spec.height
                cameraFormat = spec.format.toInt()
            } else {
                throw IllegalStateException("SDL_GetCameraFormat failed: ${SDL.error()}")
            }
        }
    }

    /** Picks an even-sized preview target no larger than 640 px on its long side. */
    private fun chooseTargetSize() {
        val longest = max(cameraWidth, cameraHeight)
        val scale = if (longest > 640) 640.0 / longest else 1.0
        frameWidth = even((cameraWidth * scale).toInt())
        frameHeight = even((cameraHeight * scale).toInt())
    }

    private fun even(value: Int): Int = max(2, value and -2)

    private fun allocateSourceBuffers() {
        val w = cameraWidth
        val h = cameraHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        sourceY = ByteArray(w * h)
        sourceU = ByteArray(cw * ch)
        sourceV = ByteArray(cw * ch)
    }

    private fun allocateFrameBuffers() {
        val w = frameWidth
        val h = frameHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        scaledY = ByteArray(w * h)
        scaledU = ByteArray(cw * ch)
        scaledV = ByteArray(cw * ch)
    }

    private fun startEncoder() {
        var lastError = "no AVC encoder"
        for (color in intArrayOf(COLOR_YUV420_SEMI_PLANAR, COLOR_YUV420_PLANAR, COLOR_YUV420_FLEXIBLE)) {
            val codec = AMediaCodec_createEncoderByType(MIME_AVC) ?: continue
            val format = AMediaFormat_new()
            if (format == null) {
                AMediaCodec_delete(codec)
                continue
            }
            try {
                AMediaFormat_setString(format, "mime", MIME_AVC)
                AMediaFormat_setInt32(format, "width", encodedWidth)
                AMediaFormat_setInt32(format, "height", encodedHeight)
                AMediaFormat_setInt32(format, "color-format", color)
                AMediaFormat_setInt32(format, "bitrate", 4_000_000)
                AMediaFormat_setInt32(format, "frame-rate", 30)
                AMediaFormat_setInt32(format, "i-frame-interval", 1)
                if (AMediaCodec_configure(
                        codec,
                        format,
                        null,
                        null,
                        AMEDIACODEC_CONFIGURE_FLAG_ENCODE.toUInt(),
                    ) == 0 && AMediaCodec_start(codec) == 0
                ) {
                    encoder = codec
                    encoderColor = color
                    allocateEncoderBuffers()
                    return
                }
                lastError = "encoder rejected color format $color"
            } finally {
                AMediaFormat_delete(format)
            }
            AMediaCodec_delete(codec)
        }
        throw IllegalStateException(lastError)
    }

    private fun allocateEncoderBuffers() {
        val w = encodedWidth
        val h = encodedHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        if (encoderColor == COLOR_YUV420_SEMI_PLANAR || encoderColor == COLOR_YUV420_FLEXIBLE) {
            encoderY = ByteArray(w * h)
            encoderUv = ByteArray(w * ch)
        } else {
            encoderY = ByteArray(w * h)
            encoderU = ByteArray(cw * ch)
            encoderV = ByteArray(cw * ch)
        }
    }

    private fun startDecoder() {
        val codec = AMediaCodec_createDecoderByType(MIME_AVC)
            ?: throw IllegalStateException("no AVC decoder")
        val format = AMediaFormat_new() ?: run {
            AMediaCodec_delete(codec)
            throw IllegalStateException("AMediaFormat_new failed")
        }
        try {
            AMediaFormat_setString(format, "mime", MIME_AVC)
            AMediaFormat_setInt32(format, "width", encodedWidth)
            AMediaFormat_setInt32(format, "height", encodedHeight)
            if (AMediaCodec_configure(codec, format, null, null, 0u) != 0 ||
                AMediaCodec_start(codec) != 0
            ) {
                throw IllegalStateException("decoder configure/start failed")
            }
            decoder = codec
            decoderColor = COLOR_YUV420_SEMI_PLANAR
            decoderStride = encodedWidth
            decoderSliceHeight = encodedHeight
            allocateDecoderBuffers()
        } catch (t: Throwable) {
            AMediaCodec_delete(codec)
            throw t
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun allocateDecoderBuffers() {
        val w = encodedWidth
        val h = encodedHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        decodedY = ByteArray(w * h)
        decodedU = ByteArray(cw * ch)
        decodedV = ByteArray(cw * ch)
    }

    private fun pumpCamera() {
        val cam = camera ?: return
        val frame = SDL_AcquireCameraFrame(cam, null) ?: return
        try {
            val surface = frame.pointed
            if (surface.w != cameraWidth || surface.h != cameraHeight) {
                throw IllegalStateException(
                    "camera size changed to ${surface.w}x${surface.h} " +
                        "(configured ${cameraWidth}x$cameraHeight)",
                )
            }
            val format = surface.format.toInt()
            val out = when {
                format == SDL_NV12 && cameraWidth == frameWidth && cameraHeight == frameHeight -> {
                    // The camera already produces the encoder size; a single
                    // libyuv NV12->I420 conversion is much faster than sampling
                    // hundreds of thousands of pixels from Kotlin.
                    convertCameraFrame(frame)
                    Triple(sourceY, sourceU, sourceV)
                }

                format == SDL_NV12 -> {
                    sampleNv12(frame)
                    Triple(scaledY, scaledU, scaledV)
                }

                else -> {
                    convertCameraFrame(frame)
                    scaledI420()
                }
            }
            val pts = presentationTimeUs
            presentationTimeUs += FRAME_INTERVAL_US
            encodeFrame(out.first, out.second, out.third, pts)
        } finally {
            SDL_ReleaseCameraFrame(cam, frame)
        }
    }

    /**
     * Samples the camera's NV12 planes straight into the encoder-sized I420
     * frame. The default Android camera format is 4080x3060; converting all 12
     * megapixels through a temporary I420 buffer and scaling it costs several
     * hundred milliseconds. This pass touches only the pixels the preview uses.
     */
    private fun sampleNv12(frame: CPointer<SDL_Surface>) {
        val surface = frame.pointed
        val base: CPointer<ByteVar> = surface.pixels?.reinterpret()
            ?: throw IllegalStateException("camera surface has no pixels")
        val sw = surface.w
        val sh = surface.h
        val pitch = surface.pitch
        val dw = frameWidth
        val dh = frameHeight
        val uvBase = pitch * sh
        for (y in 0 until dh) {
            val srcRow = (y * sh / dh) * pitch
            val dstRow = y * dw
            for (x in 0 until dw) {
                scaledY[dstRow + x] = base[srcRow + x * sw / dw]
            }
        }
        val scw = (sw + 1) / 2
        val sch = (sh + 1) / 2
        val dcw = (dw + 1) / 2
        val dch = (dh + 1) / 2
        for (y in 0 until dch) {
            val srcRow = uvBase + (y * sch / dch) * pitch
            val dstRow = y * dcw
            for (x in 0 until dcw) {
                val uv = srcRow + (x * scw / dcw) * 2
                scaledU[dstRow + x] = base[uv]
                scaledV[dstRow + x] = base[uv + 1]
            }
        }
    }

    private fun convertCameraFrame(frame: CPointer<SDL_Surface>) {
        val surface = frame.pointed
        val w = surface.w
        val h = surface.h
        val pitch = surface.pitch
        val chromaHeight = (h + 1) / 2
        // SDL_Surface.pixels is only the luma plane for planar formats; the
        // chroma planes live after it in the allocation. Read the complete
        // NV12/YV12/I420 buffer from the surface pointer instead.
        val format = surface.format.toInt()
        val bytes = when (format) {
            SDL_NV12, SDL_NV21, SDL_IYUV, SDL_YV12 -> pitch * h * 3 / 2
            else -> pitch * h
        }
        val pixels = surface.pixels?.readFast(bytes)
            ?: throw IllegalStateException("camera surface has no pixels")

        fun plane(offset: Int, length: Int): ByteArray {
            val end = max(offset, minOf(pixels.size, offset + length))
            return pixels.copyOfRange(offset, end)
        }

        when (format) {
            SDL_NV12 -> {
                val y = plane(0, pitch * h)
                val uv = plane(pitch * h, pitch * chromaHeight)
                LibYuv.nv12ToI420(
                    y, uv, sourceY, sourceU, sourceV,
                    w, h, pitch, pitch,
                )
            }

            SDL_NV21 -> {
                val y = plane(0, pitch * h)
                val vu = plane(pitch * h, pitch * chromaHeight)
                LibYuv.nv21ToI420(
                    y, vu, sourceY, sourceU, sourceV,
                    w, h, pitch, pitch,
                )
            }

            SDL_IYUV -> {
                val y = plane(0, pitch * h)
                val u = plane(pitch * h, (pitch / 2) * chromaHeight)
                val v = plane(pitch * h + (pitch / 2) * chromaHeight, (pitch / 2) * chromaHeight)
                LibYuv.i420Copy(
                    y, u, v, sourceY, sourceU, sourceV,
                    w, h, pitch, pitch / 2, pitch / 2,
                )
            }

            SDL_YV12 -> {
                val y = plane(0, pitch * h)
                val v = plane(pitch * h, (pitch / 2) * chromaHeight)
                val u = plane(pitch * h + (pitch / 2) * chromaHeight, (pitch / 2) * chromaHeight)
                LibYuv.i420Copy(
                    y, u, v, sourceY, sourceU, sourceV,
                    w, h, pitch, pitch / 2, pitch / 2,
                )
            }

            SDL_YUY2 -> LibYuv.yuy2ToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)
            SDL_UYVY -> LibYuv.uyvyToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)
            SDLPixelFormat.RGB24 ->
                LibYuv.rgb24ToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)

            SDLPixelFormat.ARGB8888 ->
                LibYuv.argbToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)

            SDLPixelFormat.BGRA8888 ->
                LibYuv.bgraToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)

            SDLPixelFormat.ABGR8888 ->
                LibYuv.abgrToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)

            SDLPixelFormat.RGBA8888 ->
                LibYuv.rgbaToI420(pixels, sourceY, sourceU, sourceV, w, h, pitch)

            else -> throw IllegalStateException(
                "unsupported camera format ${formatName(format)}",
            )
        }
    }

    private fun scaledI420(): Triple<ByteArray, ByteArray, ByteArray> {
        val sw = cameraWidth
        val sh = cameraHeight
        val dw = frameWidth
        val dh = frameHeight
        if (sw == dw && sh == dh) {
            return Triple(sourceY, sourceU, sourceV)
        }
        // libyuv's general scaler is pathologically slow for a 4080x3060
        // camera frame on some Android ARM builds. The preview only needs a
        // small, stable target, so sample it directly; this is a few hundred
        // thousand reads instead of a 12 megapixel filtered scale.
        for (y in 0 until dh) {
            val srcRow = (y * sh / dh) * sw
            val dstRow = y * dw
            for (x in 0 until dw) {
                scaledY[dstRow + x] = sourceY[srcRow + x * sw / dw]
            }
        }
        val scw = (sw + 1) / 2
        val sch = (sh + 1) / 2
        val dcw = (dw + 1) / 2
        val dch = (dh + 1) / 2
        for (y in 0 until dch) {
            val srcRow = (y * sch / dch) * scw
            val dstRow = y * dcw
            for (x in 0 until dcw) {
                val srcCol = x * scw / dcw
                scaledU[dstRow + x] = sourceU[srcRow + srcCol]
                scaledV[dstRow + x] = sourceV[srcRow + srcCol]
            }
        }
        return Triple(scaledY, scaledU, scaledV)
    }

    private fun rotatedI420(
        frame: Triple<ByteArray, ByteArray, ByteArray>,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val rotation = Rotation.entries[rotationIndex]
        if (rotation == Rotation.NONE) return frame
        val w = encodedWidth
        val h = encodedHeight
        if (rotatedY.size != w * h) {
            rotatedY = ByteArray(w * h)
            rotatedU = ByteArray(((w + 1) / 2) * ((h + 1) / 2))
            rotatedV = ByteArray(rotatedU.size)
        }
        LibYuv.i420Rotate(
            frame.first, frame.second, frame.third,
            rotatedY, rotatedU, rotatedV,
            frameWidth, frameHeight, rotation,
        )
        return Triple(rotatedY, rotatedU, rotatedV)
    }

    private fun encodeFrame(y: ByteArray, u: ByteArray, v: ByteArray, ptsUs: Long) {
        val codec = encoder ?: return
        val index = AMediaCodec_dequeueInputBuffer(codec, 0)
        if (index < 0) return
        val inputIndex = index.toULong()

        memScoped {
            val capacity = alloc<size_tVar>()
            val buffer = AMediaCodec_getInputBuffer(codec, inputIndex, capacity.ptr)
            if (buffer == null) {
                throw IllegalStateException("AMediaCodec_getInputBuffer returned null")
            }
            val required = encodedWidth * encodedHeight * 3 / 2
            if (capacity.value.toLong() < required) {
                throw IllegalStateException(
                    "encoder input ${capacity.value} < frame $required",
                )
            }

            if (encoderColor == COLOR_YUV420_SEMI_PLANAR || encoderColor == COLOR_YUV420_FLEXIBLE) {
                LibYuv.i420ToNv12(
                    y, u, v, encoderY, encoderUv,
                    encodedWidth, encodedHeight,
                )
                buffer.write(encoderY, encoderY.size)
                buffer.plus(encoderY.size)?.write(encoderUv, encoderUv.size)
            } else {
                LibYuv.i420Copy(
                    y, u, v, encoderY, encoderU, encoderV,
                    encodedWidth, encodedHeight,
                )
                var offset = 0
                buffer.write(encoderY, encoderY.size)
                offset += encoderY.size
                buffer.plus(offset)?.write(encoderU, encoderU.size)
                offset += encoderU.size
                buffer.plus(offset)?.write(encoderV, encoderV.size)
            }

            val result = AMediaCodec_queueInputBuffer(
                codec,
                inputIndex,
                0,
                required.toULong(),
                ptsUs.toULong(),
                0u,
            )
            if (result != 0) throw IllegalStateException("encoder queueInputBuffer=$result")
        }
        encodedFrames++
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) continue
                if (index < 0) break
                val size = info.size
                if (size > 0) {
                    val buffer = AMediaCodec_getOutputBuffer(codec, index.toULong(), bufferSize.ptr)
                    if (buffer != null) {
                        packets.addLast(
                            EncodedPacket(
                                buffer.read(info.offset, size),
                                info.flags.toInt(),
                                info.presentationTimeUs,
                            ),
                        )
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.toULong(), false)
                if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
        while (packets.size > 60) packets.removeFirst()
    }

    private fun feedDecoder() {
        val codec = decoder ?: return
        while (packets.isNotEmpty()) {
            val index = AMediaCodec_dequeueInputBuffer(codec, 0)
            if (index < 0) return
            val packet = packets.removeFirst()
            memScoped {
                val capacity = alloc<size_tVar>()
                val buffer = AMediaCodec_getInputBuffer(codec, index.toULong(), capacity.ptr)
                    ?: throw IllegalStateException("decoder input buffer is null")
                if (packet.data.size > capacity.value.toLong()) {
                    error = "decoder input ${capacity.value} < packet ${packet.data.size}"
                    return
                }
                buffer.write(packet.data, packet.data.size)
                val result = AMediaCodec_queueInputBuffer(
                    codec,
                    index.toULong(),
                    0,
                    packet.data.size.toULong(),
                    packet.ptsUs.toULong(),
                    packet.flags.toUInt(),
                )
                if (result != 0) throw IllegalStateException("decoder queueInputBuffer=$result")
            }
        }
    }

    private fun drainDecoder() {
        val codec = decoder ?: return
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) {
                    readDecoderFormat(codec)
                    continue
                }
                if (index < 0) break
                if (info.size > 0 &&
                    (info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    val buffer = AMediaCodec_getOutputBuffer(codec, index.toULong(), bufferSize.ptr)
                    if (buffer != null) {
                        latest = decodeOutput(buffer.read(info.offset, info.size))
                        decodedFrames++
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.toULong(), false)
                if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun readDecoderFormat(codec: CPointer<AMediaCodec>) {
        val format = AMediaCodec_getOutputFormat(codec) ?: return
        memScoped {
            val value = alloc<IntVar>()
            if (AMediaFormat_getInt32(format, "color-format", value.ptr)) decoderColor = value.value
            if (AMediaFormat_getInt32(format, "stride", value.ptr)) decoderStride = value.value
            if (AMediaFormat_getInt32(format, "slice-height", value.ptr)) decoderSliceHeight = value.value
        }
        if (decoderStride <= 0) decoderStride = encodedWidth
        if (decoderSliceHeight <= 0) decoderSliceHeight = encodedHeight
    }

    private fun decodeOutput(data: ByteArray): VideoFrame {
        val w = encodedWidth
        val h = encodedHeight
        val stride = if (decoderStride > 0) decoderStride else w
        val sliceHeight = if (decoderSliceHeight > 0) decoderSliceHeight else h
        val ySize = stride * sliceHeight
        if (data.size < ySize) {
            throw IllegalStateException("decoder output ${data.size} < luma $ySize")
        }
        val rgbaOut = ByteArray(w * h * 4)
        val chromaHeight = (sliceHeight + 1) / 2

        if (decoderColor == COLOR_YUV420_PLANAR) {
            val chromaStride = (stride + 1) / 2
            val uSize = chromaStride * chromaHeight
            val uOffset = ySize
            val vOffset = uOffset + uSize
            if (data.size < vOffset + uSize) {
                throw IllegalStateException("decoder output is smaller than an I420 frame")
            }
            val y = data.copyOfRange(0, ySize)
            val u = data.copyOfRange(uOffset, uOffset + uSize)
            val v = data.copyOfRange(vOffset, vOffset + uSize)
            LibYuv.i420ToRgba(
                y, u, v, rgbaOut, w, h,
                stride, chromaStride, chromaStride,
            )
        } else {
            val chromaStride = stride
            val uvSize = chromaStride * chromaHeight
            val uvOffset = ySize
            if (data.size < uvOffset + uvSize) {
                throw IllegalStateException("decoder output is smaller than an NV12 frame")
            }
            val y = data.copyOfRange(0, ySize)
            val uv = data.copyOfRange(uvOffset, uvOffset + uvSize)
            LibYuv.nv12ToI420(
                y, uv, decodedY, decodedU, decodedV,
                w, h, stride, chromaStride,
            )
            LibYuv.i420ToRgba(decodedY, decodedU, decodedV, rgbaOut, w, h)
        }
        return VideoFrame(w, h, rgbaOut)
    }

    private fun closeCodecs() {
        encoder?.let {
            AMediaCodec_stop(it)
            AMediaCodec_delete(it)
        }
        decoder?.let {
            AMediaCodec_stop(it)
            AMediaCodec_delete(it)
        }
        encoder = null
        decoder = null
        packets.clear()
    }

    private fun fail(message: String) {
        error = message
        close()
        retryAt = SDL.getTicks() + 2_000u
    }

    private fun rotated(): Boolean =
        Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_90 ||
            Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_270

    private fun rotationName(): String = when (Rotation.entries[rotationIndex]) {
        Rotation.NONE -> "0 deg"
        Rotation.CLOCKWISE_90 -> "90 deg"
        Rotation.ROTATE_180 -> "180 deg"
        Rotation.CLOCKWISE_270 -> "270 deg"
    }

    private fun formatName(format: Int): String =
        SDL.getPixelFormatName(format) ?: "0x${format.toString(16)}"

    private fun encoderName(): String = when (encoderColor) {
        COLOR_YUV420_SEMI_PLANAR -> "NV12"
        COLOR_YUV420_PLANAR -> "I420"
        COLOR_YUV420_FLEXIBLE -> "NV12/flexible"
        else -> "not started"
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = AndroidVideoPipeline()

/**
 * Writes [bytes] to the start of a direct MediaCodec buffer.
 */
private fun CPointer<UByteVar>.write(bytes: ByteArray, length: Int) {
    if (length == 0) return
    val dst = interpretCPointer<ByteVar>(rawValue) ?: return
    bytes.usePinned { memcpy(dst, it.addressOf(0), length.convert()) }
}

/**
 * Reads [length] bytes starting at [offset] from a direct MediaCodec buffer.
 */
private fun CPointer<UByteVar>.read(offset: Int, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val base = interpretCPointer<ByteVar>(rawValue)
        ?: throw IllegalStateException("null MediaCodec buffer")
    return base.plus(offset)?.readFast(length) ?: ByteArray(0)
}

/** Bulk-copies [length] bytes from a native buffer with a single memcpy. */
private fun CPointer<*>.readFast(length: Int): ByteArray {
    val out = ByteArray(length)
    if (length == 0) return out
    out.usePinned { memcpy(it.addressOf(0), this, length.convert()) }
    return out
}
