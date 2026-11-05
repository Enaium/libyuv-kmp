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

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cn.enaium.libyuv.toI420
import java.util.concurrent.Executors

data class CaptureStatus(
    val running: Boolean = false,
    val message: String = "Idle",
    val encodedFrames: Long = 0,
    val decodedFrames: Long = 0,
)

/**
 * Owns the CameraX ImageAnalysis use case and the H.264 encode/decode loopback.
 *
 * Frames are converted to I420 on the CameraX analysis executor, queued without
 * blocking, rotated by libyuv when the sensor orientation requires it, encoded
 * with MediaCodec, and immediately decoded by another MediaCodec instance whose
 * output surface is the example's TextureView.
 */
class CaptureController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libyuv-camera-analysis").apply { isDaemon = true }
    }

    var status by mutableStateOf(CaptureStatus())
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var loopback: H264Loopback? = null
    private var stopThread: Thread? = null
    private var released = false

    fun start(owner: LifecycleOwner, outputSurface: Surface) {
        if (status.running || released || loopback != null) return
        if (!outputSurface.isValid) {
            updateStatus { it.copy(message = "Decoder surface is not ready") }
            return
        }
        updateStatus { it.copy(message = "Starting camera…") }

        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()

                val newLoopback = H264Loopback(
                    outputSurface = outputSurface,
                    onStats = { encoded, decoded ->
                        updateStatus {
                            it.copy(encodedFrames = encoded, decodedFrames = decoded)
                        }
                    },
                    onError = { error -> fail(error) },
                )
                loopback = newLoopback

                imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        newLoopback.offer(image.toI420())
                    } catch (error: Throwable) {
                        fail(error)
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis,
                )
                analysis = imageAnalysis
                newLoopback.start()
                updateStatus {
                    CaptureStatus(
                        running = true,
                        message = "Encoding and decoding…",
                    )
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun stop() {
        val loopback = loopback ?: return
        this.loopback = null
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()

        updateStatus { it.copy(running = false, message = "Stopping…") }
        val thread = Thread({
            val result = loopback.stop()
            updateStatus { status ->
                status.copy(
                    running = false,
                    message = stopMessage(result),
                    encodedFrames = result.encodedFrames,
                    decodedFrames = result.decodedFrames,
                )
            }
        }, "libyuv-loopback-finalizer")
        stopThread = thread
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Stops the loopback and waits for the decoder to release its Surface.
     * Called from the TextureView's surface-destroyed callback; releasing the
     * Surface while MediaCodec still owns it would crash the decoder.
     */
    fun stopBlocking() {
        val loopback = loopback
        this.loopback = null
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()

        val result = loopback?.stop()
        stopThread?.let { thread ->
            try {
                thread.join(12_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (result != null) {
            updateStatus { status ->
                status.copy(
                    running = false,
                    message = stopMessage(result),
                    encodedFrames = result.encodedFrames,
                    decodedFrames = result.decodedFrames,
                )
            }
        }
    }

    private fun stopMessage(result: EncoderResult): String = when {
        result.error != null -> "Error: ${result.error.message}"
        result.decodedFrames > 0 ->
            "Stopped: encoded ${result.encodedFrames} / decoded ${result.decodedFrames}"
        else -> "No frames were encoded"
    }

    fun release() {
        if (released) return
        released = true
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
        val loopback = loopback
        this.loopback = null
        if (loopback != null) {
            val thread = Thread({ loopback.stop() }, "libyuv-loopback-release")
            stopThread = thread
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "encode/decode loopback failed", error)
        updateStatus {
            it.copy(running = false, message = error.message ?: error::class.java.simpleName)
        }
        val loopback = loopback
        if (loopback != null) {
            this.loopback = null
            mainHandler.post {
                analysis?.clearAnalyzer()
                analysis = null
                cameraProvider?.unbindAll()
            }
            val thread = Thread({ loopback.stop() }, "libyuv-loopback-failure")
            stopThread = thread
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun updateStatus(transform: (CaptureStatus) -> CaptureStatus) {
        mainHandler.post {
            status = transform(status)
        }
    }

    private companion object {
        const val TAG = "LibYuvCodec"
    }
}
