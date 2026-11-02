package com.github.libretube.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.github.libretube.voiceover.AliceOcrBridge
import com.github.libretube.voiceover.AliceOcrParser
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.Executors

/**
 * User-opt-in Accessibility Service. Capture happens only when the user has
 * requested one specific Alice translation, only while the Yandex Browser
 * (the official share-to-Alice target) or the dedicated Alice app is
 * foreground, and stops after a brief timeout or successful OCR result.
 *
 * No screenshots or chat text are saved or transmitted. The recognized result
 * stays only in AliceOcrBridge's process memory.
 */
class AliceScreenRecognitionService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var activeWindowIsAlice = false
    private var previousJobMarker: String? = null
    private var scheduled = false
    private var processing = false
    private var stableText: String? = null
    private var stableCount = 0
    private var lastCaptureAt = 0L
    private var lastEventLogged: String? = null
    private var tess: TessBaseAPI? = null
    private var ocrUnavailable = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.i(TAG, "service_connected screenshot_capability_enabled events=${serviceInfo?.eventTypes}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screenshot without an explicit, currently armed Alice request.
        val marker = AliceOcrBridge.currentMarker() ?: return
        if (previousJobMarker != marker) {
            previousJobMarker = marker
            stableText = null
            stableCount = 0
            ocrUnavailable = false
            lastEventLogged = null
            Log.i(TAG, "new_request_armed")
        }
        val packageName = event?.packageName?.toString() ?: "unknown"
        val type = event?.eventType ?: 0
        val signature = "$packageName:$type"
        if (signature != lastEventLogged) {
            // Diagnostic metadata only. Never log event text or screen contents.
            Log.i(TAG, "accessibility_event package=$packageName type=$type")
            lastEventLogged = signature
        }

        // ACTION_SEND opens the official Yandex Browser Alice chat. On this
        // handset the browser remains foreground (com.yandex.browser),
        // so watching only com.yandex.aliceapp prevented *all* OCR requests.
        if (packageName == "com.yandex.browser" ||
            packageName == "com.yandex.aliceapp"
        ) {
            if (!activeWindowIsAlice) {
                Log.i(TAG, "alice_foreground package=$packageName")
            }
            activeWindowIsAlice = true
            scheduleCheck()
        } else if (
            packageName != "com.android.systemui" &&
            packageName != "unknown" &&
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        ) {
            if (activeWindowIsAlice) Log.i(TAG, "alice_left package=$packageName")
            activeWindowIsAlice = false
        }
    }

    override fun onInterrupt() {
        activeWindowIsAlice = false
    }

    private fun scheduleCheck() {
        if (scheduled || processing || !activeWindowIsAlice ||
            !AliceOcrBridge.isArmed() || ocrUnavailable
        ) return
        scheduled = true
        val elapsed = android.os.SystemClock.elapsedRealtime() - lastCaptureAt
        main.postDelayed({
            scheduled = false
            if (activeWindowIsAlice && AliceOcrBridge.isArmed() && !processing) {
                captureOnce()
            }
        }, maxOf(1_500L, 2_500L - elapsed))
    }

    private fun captureOnce() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val marker = AliceOcrBridge.currentMarker() ?: return
        processing = true
        Log.i(TAG, "screenshot_requested")
        lastCaptureAt = android.os.SystemClock.elapsedRealtime()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val software = try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            buffer, result.colorSpace
                        )
                        val copy = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap?.recycle()
                        copy
                    } catch (_: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    if (software == null) {
                        Log.w(TAG, "screenshot_bitmap_unavailable")
                        processing = false
                        scheduleCheck()
                        return
                    }
                    worker.execute {
                        val candidate = try {
                            if (!AliceOcrBridge.isArmed() || !activeWindowIsAlice) null
                            else {
                                val sourceHeight = software.height
                                val y = (sourceHeight * 0.05).toInt()
                                val height = (sourceHeight * 0.88).toInt()
                                    .coerceAtMost(sourceHeight - y)
                                val crop = Bitmap.createBitmap(
                                    software, 0, y, software.width, height
                                )
                                try {
                                    val engine = requireOcrEngine()
                                    engine.setImage(crop)
                                    val pageText = engine.getUTF8Text().orEmpty()
                                    val parsed = AliceOcrParser.extract(pageText, marker)
                                    // Log only counts; never the contents of Alice chats.
                                    Log.i(
                                        TAG,
                                        "ocr_done chars=${pageText.length} marker_hits=${pageText.windowed(marker.length).count { it == marker }} parsed=${parsed != null}"
                                    )
                                    parsed
                                } finally {
                                    crop.recycle()
                                }
                            }
                        } catch (error: Exception) {
                            Log.e(TAG, "ocr_exception type=${error.javaClass.simpleName}")
                            ocrUnavailable = true
                            null
                        } finally {
                            software.recycle()
                        }
                        main.post {
                            processing = false
                            if (candidate != null && AliceOcrBridge.currentMarker() == marker) {
                                if (candidate == stableText) stableCount++
                                else {
                                    stableText = candidate
                                    stableCount = 1
                                }
                                Log.i(TAG, "candidate_stability count=$stableCount")
                                if (stableCount >= 2 && AliceOcrBridge.deliver(marker, candidate)) {
                                    Log.i(TAG, "translation_ready returning_to_caller")
                                    // Return to the calling app if Android's task stack allows.
                                    // If it does not, the user may return manually.
                                    activeWindowIsAlice = false
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                    return@post
                                }
                            }
                            scheduleCheck()
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot_failed code=$errorCode")
                    processing = false
                    // Could be a secure window; never attempt alternate capture APIs.
                    scheduleCheck()
                }
            }
        )
    }

    private fun requireOcrEngine(): TessBaseAPI {
        tess?.let { return it }
        val root = File(filesDir, "alice-ocr")
        val dataDir = File(root, "tessdata")
        val languageData = File(dataDir, "rus.traineddata")
        if (!languageData.exists() || languageData.length() < 1_000_000) {
            dataDir.mkdirs()
            assets.open("tessdata/rus.traineddata").use { source ->
                val partial = File(dataDir, "rus.traineddata.partial")
                partial.outputStream().use { dest -> source.copyTo(dest) }
                if (partial.length() < 1_000_000) {
                    partial.delete()
                    error("Cyrillic OCR model is missing")
                }
                if (!partial.renameTo(languageData)) {
                    partial.delete()
                    error("Unable to install local OCR model")
                }
            }
        }
        val engine = TessBaseAPI()
        if (!engine.init(root.absolutePath, "rus")) {
            engine.recycle()
            error("Unable to initialize Russian OCR")
        }
        tess = engine
        return engine
    }

    override fun onDestroy() {
        connected = false
        Log.i(TAG, "service_destroyed")
        main.removeCallbacksAndMessages(null)
        worker.execute {
            tess?.recycle()
            tess = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "RootTubeAliceOCR"

        @Volatile
        var connected: Boolean = false
            private set
    }
}
