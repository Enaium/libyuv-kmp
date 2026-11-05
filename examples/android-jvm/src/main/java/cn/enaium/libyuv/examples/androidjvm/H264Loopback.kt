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

package cn.enaium.libyuv.examples.androidjvm

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import cn.enaium.libyuv.FourCC
import cn.enaium.libyuv.I420Frame
import cn.enaium.libyuv.Rotation
import cn.enaium.libyuv.writeI420
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal data class EncoderResult(
    val encodedFrames: Long,
    val decodedFrames: Long,
    val error: Throwable?,
)

/**
 * H.264 encode/decode loopback fed with I420 frames from CameraX.
 *
 * The encoder prefers a flexible color format: its `Image` input exposes the
 * codec's real row/pixel strides, so aligned input buffers are filled correctly.
 * Semi-planar and planar fallbacks are converted from I420 with libyuv. The
 * produced H.264 samples are handed to a MediaCodec decoder whose output surface
 * is the example's [TextureView], so the encoded/decoded stream is rendered
 * directly to the screen without a file or muxer.
 */
internal class H264Loopback(
    private val outputSurface: Surface,
    private val onStats: (encoded: Long, decoded: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val queue = ArrayBlockingQueue<I420Frame>(2)

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var colorFormat = 0
    private var configured = false
    private var frameWidth = 0
    private var frameHeight = 0
    private var encodedFrames = 0L
    private var decodedFrames = 0L
    private var lastPtsUs = 0L
    private var failure: Throwable? = null

    fun start() {
        if (running) return
        require(outputSurface.isValid) { "The decoder output surface is not valid" }
        running = true
        worker = Thread(::loop, "libyuv-h264-loopback").apply {
            isDaemon = true
            start()
        }
    }

    /** Queues a frame without blocking the CameraX analysis thread. */
    fun offer(frame: I420Frame): Boolean = running && queue.offer(frame)

    /** Stops the worker, drains both codecs and returns the final counters. */
    fun stop(): EncoderResult {
        running = false
        val thread = worker
        try {
            thread?.join(10_000)
            if (thread?.isAlive == true) thread.interrupt()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return EncoderResult(encodedFrames, decodedFrames, failure)
    }

    private fun loop() {
        try {
            while (running || queue.isNotEmpty()) {
                val frame = if (running) queue.poll(200, TimeUnit.MILLISECONDS) else queue.poll()
                if (frame == null) {
                    if (!running) break
                    continue
                }
                val prepared = rotateIfNeeded(frame)
                if (!configured) configureEncoder(prepared)
                encode(prepared)
            }
            finish()
        } catch (error: Throwable) {
            failure = error
            running = false
            onError(error)
        } finally {
            release()
        }
    }

    private fun rotateIfNeeded(frame: I420Frame): I420Frame {
        val rotation = when (frame.rotationDegrees) {
            90 -> Rotation.CLOCKWISE_90
            180 -> Rotation.ROTATE_180
            270 -> Rotation.CLOCKWISE_270
            else -> return frame
        }
        return frame.rotate(rotation)
    }

    private fun configureEncoder(frame: I420Frame) {
        val (info, selectedColorFormat) = chooseEncoder()
        colorFormat = selectedColorFormat
        frameWidth = frame.width
        frameHeight = frame.height

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, frame.width, frame.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                (frame.width.toLong() * frame.height * 3).coerceIn(1_000_000, 8_000_000).toInt(),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frame.width * frame.height * 3 / 2)
        }

        val codec = MediaCodec.createByCodecName(info.name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            Log.i(
                "LibYuvCodec",
                "encoder=${info.name} colorFormat=$selectedColorFormat ${frame.width}x${frame.height}",
            )
            configured = true
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw error
        }
    }

    private fun chooseEncoder(): Pair<MediaCodecInfo, Int> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = list.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val ordered = encoders.sortedByDescending { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated) 1 else 0
        }
        for (info in ordered) {
            val formats = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet()
            // Flexible exposes the codec's real plane strides through
            // getInputImage, which avoids guessing the input stride.
            val selected = when {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> null
            }
            if (selected != null) return info to selected
        }
        error("No AVC encoder supports byte-buffer YUV420 input on this device")
    }

    private fun encode(frame: I420Frame) {
        val codec = encoder ?: return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val pts = maxOf(frame.presentationTimeUs, lastPtsUs + 1)
        lastPtsUs = pts

        val image = runCatching { codec.getInputImage(inputIndex) }.getOrNull()
        val queued = if (image != null) {
            try {
                image.writeI420(frame)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    frame.width * frame.height * 3 / 2,
                    pts,
                    0,
                )
                true
            } catch (error: Throwable) {
                Log.w("LibYuvCodec", "Image input failed; falling back to ByteBuffer", error)
                false
            }
        } else {
            false
        }

        if (!queued) {
            val input = codec.getInputBuffer(inputIndex) ?: return
            val packed = frame.toPackedBuffer(
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    FourCC.I420
                } else {
                    FourCC.NV12
                },
            )
            if (packed.capacity() > input.capacity()) {
                error(
                    "encoder input buffer (${input.capacity()} bytes) is smaller than a frame " +
                        "(${packed.capacity()} bytes)",
                )
            }
            input.clear()
            input.put(packed)
            codec.queueInputBuffer(inputIndex, 0, packed.capacity(), pts, 0)
        }

        encodedFrames++
        drainEncoder()
    }

    private fun drainEncoder(endOfStream: Boolean = false) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        var idleSpins = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> configureDecoder(codec.outputFormat)

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleSpins > 500) return
                }

                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index) ?: error("encoder returned no output buffer")
                    val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEndOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!isCodecConfig && (info.size > 0 || isEndOfStream)) {
                        feedDecoder(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (isEndOfStream) return
                }
            }
        }
    }

    private fun configureDecoder(format: MediaFormat) {
        if (decoder != null) return
        val csd0 = format.getByteBuffer("csd-0")?.duplicate()?.apply { position(0) }
            ?: error("AVC encoder output has no csd-0")
        val decoderFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            frameWidth,
            frameHeight,
        ).apply {
            setByteBuffer("csd-0", csd0)
            format.getByteBuffer("csd-1")?.duplicate()?.apply { position(0) }?.let {
                setByteBuffer("csd-1", it)
            }
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(decoderFormat, outputSurface, null, 0)
            codec.start()
            decoder = codec
            Log.i("LibYuvCodec", "decoder=${codec.name} surface=$outputSurface")
        } catch (error: Throwable) {
            runCatching { codec.release() }
            throw error
        }
    }

    private fun feedDecoder(source: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        val codec = decoder ?: return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val input = codec.getInputBuffer(inputIndex) ?: return
        input.clear()
        if (info.size > 0) {
            source.position(info.offset)
            source.limit(info.offset + info.size)
            input.put(source)
        }
        val flags = if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        } else {
            0
        }
        codec.queueInputBuffer(inputIndex, 0, info.size, info.presentationTimeUs, flags)
        drainDecoder()
    }

    private fun drainDecoder(endOfStream: Boolean = false) {
        val codec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        var idleSpins = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleSpins > 500) return
                }

                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                index >= 0 -> {
                    // true releases the buffer to the Surface (TextureView).
                    codec.releaseOutputBuffer(index, true)
                    decodedFrames++
                    onStats(encodedFrames, decodedFrames)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun finish() {
        val codec = encoder
        if (codec != null && configured) {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    maxOf(lastPtsUs, 1),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drainEncoder(endOfStream = true)
            drainDecoder(endOfStream = true)
        }
    }

    private fun release() {
        val decoder = decoder
        this.decoder = null
        if (decoder != null) {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        val encoder = encoder
        this.encoder = null
        if (encoder != null) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }
}
