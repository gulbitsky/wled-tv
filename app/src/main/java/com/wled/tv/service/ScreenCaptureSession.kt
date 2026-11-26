package com.wled.tv.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.wled.tv.engine.AmbientEngine
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureSession(
    private val context: Context,
    private val engineProvider: () -> AmbientEngine?,
    private val onTick: ((AmbientEngine) -> Unit)? = null
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _isCapturing = AtomicBoolean(false)
    val isCapturing: Boolean get() = _isCapturing.get()

    private val streamingTickerRunnable = object : Runnable {
        override fun run() {
            if (!_isCapturing.get()) return

            if (!isScreenActive()) {
                onScreenStandbyDetected()
                backgroundHandler?.postDelayed(this, 1000L)
                return
            }

            val currentEngine = engineProvider()
            if (currentEngine == null) {
                backgroundHandler?.postDelayed(this, 100L)
                return
            }

            // 1. Считываем выбранный пользователем FPS (от 1 до 5) и считаем задержку
            val userFps = currentEngine.settings.fps.coerceIn(1, 5)
            val frameIntervalMs = 1000L / userFps

            // 2. Запрашиваем 1 кадр строго по таймеру
            val reader = imageReader
            if (reader != null) {
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring image", e)
                    null
                }

                if (image != null) {
                    try {
                        val planes = image.planes
                        if (planes.isNotEmpty()) {
                            val plane = planes[0]
                            val buffer = plane.buffer
                            if (buffer != null) {
                                currentEngine.onFrameAvailable(
                                    buffer = buffer,
                                    width = image.width,
                                    height = image.height,
                                    rowStride = plane.rowStride,
                                    pixelStride = plane.pixelStride
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing timed frame", e)
                    } finally {
                        image.close()
                    }
                }
            }

            // 3. Отправляем цвет на WLED
            currentEngine.tickStreaming()

            try {
                onTick?.invoke(currentEngine)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onTick callback", e)
            }

            // Задержка между кадрами (1 FPS = 1000ms, 3 FPS = 333ms, 5 FPS = 200ms)
            backgroundHandler?.postDelayed(this, frameIntervalMs)
        }
    }

    fun startCapture(projection: MediaProjection, width: Int, height: Int, densityDpi: Int) {
        if (_isCapturing.get()) return

        mediaProjection = projection
        startBackgroundThread()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        setupImageAvailableListener(reader, backgroundHandler!!)

        virtualDisplay = projection.createVirtualDisplay(
            "WLED-TV-Capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            backgroundHandler
        )

        _isCapturing.set(true)
        backgroundHandler?.post(streamingTickerRunnable)
        Log.i(TAG, "Screen capture started ($width x $height)")
    }

    fun stopCapture() {
        if (!_isCapturing.get()) return

        _isCapturing.set(false)
        backgroundHandler?.removeCallbacks(streamingTickerRunnable)

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image reader", e)
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
        mediaProjection = null

        stopBackgroundThread()
        Log.i(TAG, "Screen capture stopped")
    }

    private fun setupImageAvailableListener(reader: ImageReader, handler: Handler) {
        // Оставляем пустым: слушатель 60 FPS отключён
        reader.setOnImageAvailableListener(null, null)
    }

    private fun isScreenActive(): Boolean = true

    private fun onScreenStandbyDetected() {
        Log.d(TAG, "Screen standby detected")
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("WledCaptureThread").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    companion object {
        private const val TAG = "ScreenCaptureSession"
    }
}
