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

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import cn.enaium.libyuv.FourCC
import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal data class EncoderResult(
    val frames: Long,
    val bytes: Long,
    val error: Throwable?,
)

/**
 * H.264 encoder fed with I420 frames from CameraX.
 *
 * The encoder prefers a flexible color format: its `Image` input exposes the
 * codec's real row/pixel strides, so aligned input buffers are filled correctly.
 * Semi-planar and planar fallbacks are converted from I420 with libyuv. The
 * encoded samples are muxed into an MP4 with [MediaMuxer].
 */
internal class H264Encoder(
    val outputFile: File,
    private val onStats: (frames: Long, bytes: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val queue = ArrayBlockingQueue<I420Frame>(2)

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var colorFormat = 0
    private var configured = false
    private var frameCount = 0L
    private var byteCount = 0L
    private var lastPtsUs = 0L
    private var failure: Throwable? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread(::encodeLoop, "libyuv-h264-encoder").apply {
            isDaemon = true
            start()
        }
    }

    /** Queues a frame without blocking the CameraX analysis thread. */
    fun offer(frame: I420Frame): Boolean = running && queue.offer(frame)

    /** Stops the worker, drains the codec and returns the final counters. */
    fun stop(): EncoderResult {
        running = false
        val thread = worker
        try {
            thread?.join(10_000)
            if (thread?.isAlive == true) thread.interrupt()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return EncoderResult(frameCount, byteCount, failure)
    }

    private fun encodeLoop() {
        try {
            while (running || queue.isNotEmpty()) {
                val frame = if (running) queue.poll(200, TimeUnit.MILLISECONDS) else queue.poll()
                if (frame == null) {
                    if (!running) break
                    continue
                }
                val prepared = rotateIfNeeded(frame)
                if (!configured) configure(prepared)
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
        val swap = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val width = if (swap) frame.height else frame.width
        val height = if (swap) frame.width else frame.height
        val y = ByteArray(width * height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)
        LibYuv.i420Rotate(
            frame.y, frame.u, frame.v,
            y, u, v,
            frame.width, frame.height,
            rotation,
        )
        return I420Frame(width, height, y, u, v, 0, frame.presentationTimeUs)
    }

    private fun configure(frame: I420Frame) {
        val (info, selectedColorFormat) = chooseEncoder()
        colorFormat = selectedColorFormat

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
            this.codec = codec
            Log.i(
                "LibYuvCodec",
                "encoder=${info.name} colorFormat=$selectedColorFormat ${frame.width}x${frame.height}",
            )
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
            // getInputImage, which avoids guessing the input stride. Fall back
            // to the explicit layouts when the codec only advertises those.
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
        val codec = codec ?: return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return
        val pts = maxOf(frame.presentationTimeUs, lastPtsUs + 1)
        lastPtsUs = pts

        // Flexible encoders expose their real plane layout as an Image. That
        // layout may use aligned row strides, so writing the image planes is
        // the only reliable way to feed it.
        val image = runCatching { codec.getInputImage(inputIndex) }.getOrNull()
        val queued = if (image != null) {
            try {
                fillImage(image, frame)
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
            val packed = pack(frame)
            if (packed.size > input.capacity()) {
                error("encoder input buffer (${input.capacity()} bytes) is smaller than a frame (${packed.size} bytes)")
            }
            input.clear()
            input.put(packed)
            codec.queueInputBuffer(inputIndex, 0, packed.size, pts, 0)
        }

        frameCount++
        drain()
        onStats(frameCount, byteCount)
    }

    private fun fillImage(image: Image, frame: I420Frame) {
        copyToPlane(image.planes[0], frame.y, frame.width, frame.height, frame.width)
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        copyToPlane(image.planes[1], frame.u, chromaWidth, chromaHeight, chromaWidth)
        copyToPlane(image.planes[2], frame.v, chromaWidth, chromaHeight, chromaWidth)
    }

    private fun copyToPlane(
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

    private fun pack(frame: I420Frame): ByteArray {
        val packed = ByteArray(frame.width * frame.height * 3 / 2)
        val fourCC = if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            FourCC.I420
        } else {
            // Fallback only: the flexible path normally writes the codec's
            // Image planes directly. If no Image is available, semi-planar
            // and flexible encoders receive NV12.
            FourCC.NV12
        }
        LibYuv.convertFromI420(
            frame.y, frame.u, frame.v,
            packed,
            frame.width, frame.height,
            fourCC,
        )
        return packed
    }

    private fun drain(endOfStream: Boolean = false) {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        var idleSpins = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "encoder output format changed twice" }
                    val muxer = muxer ?: error("muxer is not initialized")
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleSpins > 500) return
                }

                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index) ?: error("encoder returned no output buffer")
                    if (info.size > 0 && muxerStarted &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, buffer, info)
                        byteCount += info.size.toLong()
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun finish() {
        val codec = codec
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
            drain(endOfStream = true)
        }
    }

    private fun release() {
        val muxer = muxer
        this.muxer = null
        if (muxer != null) {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            muxerStarted = false
        }
        val codec = codec
        this.codec = null
        if (codec != null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (byteCount == 0L) {
            outputFile.delete()
        }
    }
}
