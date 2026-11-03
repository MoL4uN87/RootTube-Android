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
    private var returningToRootTube = false
    private var returnAttempts = 0
    private var foregroundApp: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        currentService = this
        Log.i(TAG, "service_connected screenshot_capability_enabled events=${serviceInfo?.eventTypes}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: "unknown"
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventPackage != "unknown" && eventPackage != "com.android.systemui") {
                foregroundApp = eventPackage
            }
            if (returningToRootTube && eventPackage == packageName) {
                returningToRootTube = false
                Log.i(TAG, "return_completed root_tube_foreground")
            } else if (returningToRootTube &&
                eventPackage != "unknown" &&
                eventPackage != "com.android.systemui" &&
                !isYandexPackage(eventPackage) &&
                eventPackage != packageName
            ) {
                returningToRootTube = false
                Log.i(TAG, "return_cancelled other_app")
            }
        }

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
                                    activeWindowIsAlice = false
                                    startReturnToRootTube()
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

    /**
     * Yandex may keep an additional chat layer on its back stack. Make at most
     * three user-visible Android Back actions, never while another app is in
     * foreground. The saved translation survives even if automatic return fails.
     */
    private fun startReturnToRootTube() {
        returningToRootTube = true
        returnAttempts = 0
        tryReturnBack()
    }

    private fun tryReturnBack() {
        if (!returningToRootTube) return
        if (foregroundApp != null && !isYandexPackage(foregroundApp)) {
            Log.i(TAG, "return_cancelled unexpected_foreground")
            returningToRootTube = false
            return
        }
        if (returnAttempts >= 3) {
            returningToRootTube = false
            Log.w(TAG, "return_attempt_limit_reached manual_return_required")
            return
        }
        returnAttempts++
        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.i(TAG, "return_back attempt=$returnAttempts accepted=$accepted")
        if (!accepted) {
            returningToRootTube = false
            return
        }
        main.postDelayed({
            if (returningToRootTube) tryReturnBack()
        }, 1_500L)
    }

    private fun isYandexPackage(name: String?): Boolean =
        name == "com.yandex.browser" || name == "com.yandex.aliceapp"

    private fun requireOcrEngine(): TessBaseAPI {
        tess?.let { return it }
        val root = File(filesDir, "alice-ocr")
        val dataDir = File(root, "tessdata")
        // Russian and English traineddata come from the project's assets.
        // Both are copied to app-private storage; no runtime network request.
        for (lang in listOf("rus", "eng")) {
            val languageData = File(dataDir, "$lang.traineddata")
            if (!languageData.exists() || languageData.length() < 1_000_000) {
                dataDir.mkdirs()
                assets.open("tessdata/$lang.traineddata").use { source ->
                    val partial = File(dataDir, "$lang.traineddata.partial")
                    partial.outputStream().use { dest -> source.copyTo(dest) }
                    if (partial.length() < 1_000_000) {
                        partial.delete()
                        error("OCR model $lang is missing or too small")
                    }
                    if (languageData.exists()) languageData.delete()
                    if (!partial.renameTo(languageData)) {
                        partial.delete()
                        error("Unable to install OCR model $lang")
                    }
                }
            }
        }
        val engine = TessBaseAPI()
        if (!engine.init(root.absolutePath, "rus+eng")) {
            engine.recycle()
            error("Unable to initialize Russian/English OCR")
        }
        tess = engine
        return engine
    }

    override fun onDestroy() {
        returningToRootTube = false
        currentService = null
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
        returningToRootTube = false
        currentService = null
        connected = false
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "RootTubeAliceOCR"
        @Volatile private var currentService: AliceScreenRecognitionService? = null

        fun notifyRootTubeResumed() {
            currentService?.main?.post {
                currentService?.let { instance ->
                    instance.returningToRootTube = false
                }
            }
        }

        @Volatile
        var connected: Boolean = false
            private set
    }
}
