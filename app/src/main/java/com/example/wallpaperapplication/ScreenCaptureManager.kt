package com.example.wallpaperapplication

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

class ScreenCaptureManager(private val context: Context, private val eglBase: EglBase) {
    private val TAG = "ScreenCaptureManager"

    private var factory: PeerConnectionFactory? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    var screenTrack: VideoTrack? = null
        private set

    var isStreaming: Boolean = false
        private set

    fun interface ScreenCaptureStoppedListener {
        fun onScreenCaptureStopped()
    }
    var onScreenCaptureStoppedListener: ScreenCaptureStoppedListener? = null

    fun initialize(factory: PeerConnectionFactory) {
        this.factory = factory
        try {
            surfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
            videoSource = factory.createVideoSource(true) // isScreencast = true optimizes encoder for sharp screen content
            screenTrack = factory.createVideoTrack("screen_video", videoSource)
            screenTrack?.setEnabled(false)
            Log.d(TAG, "ScreenCaptureManager initialized with screen_video track (standby)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ScreenCaptureManager", e)
        }
    }

    fun startScreenCapture(mediaProjectionData: Intent): Boolean {
        if (isStreaming) {
            screenTrack?.setEnabled(true)
            Log.d(TAG, "Screen capture already running; screenTrack re-enabled")
            return true
        }

        val f = factory
        val helper = surfaceHelper
        val source = videoSource
        if (f == null || helper == null || source == null) {
            Log.e(TAG, "Cannot start screen capture: prerequisites uninitialized")
            return false
        }

        try {
            screenCapturer = ScreenCapturerAndroid(mediaProjectionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection session ended by Android system")
                    stopScreenCapture()
                    onScreenCaptureStoppedListener?.onScreenCaptureStopped()
                }
            })

            screenCapturer?.initialize(helper, context.applicationContext, source.capturerObserver)

            val metrics = context.resources.displayMetrics
            var w = metrics.widthPixels
            var h = metrics.heightPixels
            val maxDim = 1280
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / Math.max(w, h)
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            w = (w / 2) * 2
            h = (h / 2) * 2
            if (w <= 0) w = Constants.SCREEN_WIDTH
            if (h <= 0) h = Constants.SCREEN_HEIGHT

            screenCapturer?.startCapture(w, h, Constants.SCREEN_FPS)
            screenTrack?.setEnabled(true)
            isStreaming = true
            Log.i(TAG, "Live screen capture started (${w}x${h} @ ${Constants.SCREEN_FPS}fps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCapturerAndroid", e)
            stopScreenCapture()
            return false
        }
    }

    fun stopScreenCapture() {
        if (!isStreaming && screenCapturer == null) return
        Log.i(TAG, "Stopping screen capture...")
        try {
            screenTrack?.setEnabled(false)
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capturer", e)
        } finally {
            isStreaming = false
        }
    }

    fun dispose() {
        stopScreenCapture()
        surfaceHelper?.dispose()
        surfaceHelper = null
        videoSource?.dispose()
        videoSource = null
        screenTrack?.dispose()
        screenTrack = null
    }
}
