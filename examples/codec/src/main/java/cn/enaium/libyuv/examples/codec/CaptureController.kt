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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

data class CaptureStatus(
    val running: Boolean = false,
    val message: String = "Idle",
    val frames: Long = 0,
    val bytes: Long = 0,
    val outputPath: String? = null,
)

/**
 * Owns the CameraX Preview/ImageAnalysis pair and the H.264 encoding session.
 *
 * Frames are converted to I420 on the CameraX analysis executor, queued without
 * blocking, rotated by libyuv when the sensor orientation requires it, and
 * encoded on a dedicated worker thread.
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
    private var encoder: H264Encoder? = null
    private var released = false

    fun start(owner: LifecycleOwner, previewView: PreviewView) {
        if (status.running || released || encoder != null) return
        updateStatus { it.copy(message = "Starting camera…") }

        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
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

                val outputFile = File(appContext.filesDir, "libyuv-${System.currentTimeMillis()}.mp4")
                val newEncoder = H264Encoder(
                    outputFile = outputFile,
                    onStats = { frames, bytes ->
                        updateStatus { it.copy(frames = frames, bytes = bytes) }
                    },
                    onError = { error -> fail(error) },
                )
                encoder = newEncoder

                imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        newEncoder.offer(YuvConverter.toI420(image))
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
                    preview,
                    imageAnalysis,
                )
                analysis = imageAnalysis
                newEncoder.start()
                updateStatus {
                    CaptureStatus(
                        running = true,
                        message = "Recording…",
                        outputPath = outputFile.absolutePath,
                    )
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun stop() {
        val encoder = encoder ?: return
        this.encoder = null
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()

        updateStatus { it.copy(running = false, message = "Finalizing…") }
        Thread({
            val result = encoder.stop()
            val message = when {
                result.error != null -> "Error: ${result.error.message}"
                result.bytes > 0 -> "Saved ${result.frames} frames"
                else -> "No frames were encoded"
            }
            updateStatus {
                it.copy(
                    running = false,
                    message = message,
                    frames = result.frames,
                    bytes = result.bytes,
                    outputPath = if (result.bytes > 0) encoder.outputFile.absolutePath else null,
                )
            }
        }, "libyuv-encoder-finalizer").apply {
            isDaemon = true
            start()
        }
    }

    fun release() {
        if (released) return
        released = true
        analysis?.clearAnalyzer()
        analysis = null
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
        val encoder = encoder
        this.encoder = null
        if (encoder != null) {
            Thread({ encoder.stop() }, "libyuv-encoder-release").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "capture failed", error)
        updateStatus {
            it.copy(running = false, message = error.message ?: error::class.java.simpleName)
        }
        val encoder = encoder
        if (encoder != null) {
            this.encoder = null
            mainHandler.post {
                analysis?.clearAnalyzer()
                analysis = null
                cameraProvider?.unbindAll()
            }
            Thread({ encoder.stop() }, "libyuv-encoder-failure").apply {
                isDaemon = true
                start()
            }
        }
    }

    private companion object {
        const val TAG = "LibYuvCodec"
    }

    private fun updateStatus(transform: (CaptureStatus) -> CaptureStatus) {
        mainHandler.post {
            status = transform(status)
        }
    }
}
