package com.goral.museintegrator.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.goral.museintegrator.MuseApp
import com.goral.museintegrator.storage.SessionPipeline
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Watches the Muse app for the session results screen, expands the Mind card, captures it
 * losslessly and hands the bitmap to the analysis pipeline.
 *
 * Everything about screen detection is a heuristic against someone else's UI, so failures are
 * surfaced rather than swallowed, and the quick-settings tile exists as a manual override for
 * the day Muse ships a redesign.
 */
class MuseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MuseCapture"
        private const val SETTLE_MS = 700L
        private const val EXPAND_WAIT_MS = 1000L
        // AccessibilityService.takeScreenshot() is rate limited to roughly one call per second.
        private const val SCREENSHOT_INTERVAL_MS = 1200L

        @Volatile
        var instance: MuseAccessibilityService? = null
            private set

        /**
         * Last foreground package the service saw, surfaced in Settings.
         *
         * Without this, a wrong Muse package name fails completely silently: no events match,
         * nothing is captured, and there is nothing on screen to tell you why.
         */
        @Volatile
        var lastSeenPackage: String? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingEvaluate: Runnable? = null
    private var lastScreenshotAt = 0L
    private var expandAttempts = 0
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // Left unfiltered so a wrong Muse package name is diagnosable rather than silent,
            // and so the manual tile works whatever app is in front. Events are discarded on
            // the next line of onAccessibilityEvent unless they come from the configured
            // package, and window content is only ever read once the results-screen signature
            // matches, so nothing is inspected that is not a Muse session result.
            packageNames = null
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 300
        }
        Log.i(TAG, "connected; watching ${MuseApp.prefs(this).musePackage}")
    }

    override fun onDestroy() {
        instance = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Skip our own package. Opening this app's Settings to read the diagnostic generates
        // its own accessibility events, which would overwrite the reading you came to take.
        if (pkg != packageName && pkg != lastSeenPackage) lastSeenPackage = pkg
        if (busy) return
        if (!MuseApp.prefs(this).autoCaptureEnabled) return
        if (pkg != MuseApp.prefs(this).musePackage) return
        scheduleEvaluate(SETTLE_MS)
    }

    private fun scheduleEvaluate(delay: Long) {
        pendingEvaluate?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { evaluate(manual = false) }
        pendingEvaluate = r
        mainHandler.postDelayed(r, delay)
    }

    /** Entry point for the quick-settings tile. */
    fun requestManualCapture() {
        expandAttempts = 0
        mainHandler.post { evaluate(manual = true) }
    }

    private fun evaluate(manual: Boolean) {
        if (busy) return
        val root = rootInActiveWindow
        if (root == null) {
            if (manual) toast("No Muse window available to capture.")
            return
        }

        val nodes = ViewTreeDumper.flatten(root)
        if (!MetadataParser.looksLikeResultsScreen(nodes)) {
            if (manual) toast("This does not look like a Muse session results screen.")
            return
        }

        val metadata = MetadataParser.parse(nodes)
        val identity = metadata.sessionLabel
        if (!manual && identity != null && SessionPipeline.alreadyCaptured(this, identity)) {
            return
        }

        if (!MetadataParser.mindGraphIsExpanded(nodes)) {
            if (expandAttempts >= 3) {
                if (manual) toast("Could not expand the Mind graph.")
                return
            }
            expandAttempts++
            if (expandMindCard(root)) {
                scheduleEvaluate(EXPAND_WAIT_MS)
            } else if (manual) {
                toast("Could not find the Mind card to expand.")
            }
            return
        }
        expandAttempts = 0

        val roi = graphRegionOfInterest(nodes) ?: run {
            if (manual) toast("Could not locate the Mind graph on screen.")
            return
        }

        // If the card is clipped by the screen edge the trace would be truncated, so push it
        // fully into view and come back rather than scoring a partial graph.
        if (roi.top < 0 || roi.bottom > resources.displayMetrics.heightPixels) {
            val mindNode = findNodeByText(root, "Mind")
            // ACTION_SHOW_ON_SCREEN has no legacy int constant the way ACTION_CLICK does; it
            // exists only as an AccessibilityAction (API 23+), so pass its id.
            val showOnScreen = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            if (mindNode != null && mindNode.performAction(showOnScreen)) {
                scheduleEvaluate(EXPAND_WAIT_MS)
                return
            }
        }

        busy = true
        captureScreenshot { bitmap ->
            if (bitmap == null) {
                busy = false
                toast("Screenshot failed.")
                return@captureScreenshot
            }
            worker.execute {
                try {
                    val outcome = SessionPipeline.ingest(
                        context = this,
                        bitmap = bitmap,
                        roi = roi,
                        metadata = metadata,
                        viewTreeJson = ViewTreeDumper.toJson(nodes, MuseApp.prefs(this).musePackage)
                    )
                    mainHandler.post {
                        toast(outcome.userMessage)
                        busy = false
                    }
                    if (MuseApp.prefs(this).capturePowerbands && outcome.stored) {
                        mainHandler.postDelayed({ capturePowerbands(outcome.sessionDirName) }, EXPAND_WAIT_MS)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "ingest failed", t)
                    mainHandler.post {
                        toast("Capture failed: ${t.message}")
                        busy = false
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    /**
     * The Brainwave Powerbands section is the one unlocked expandable left on the results
     * screen. graph-v1 does not parse it; the screenshot is preserved so a later algorithm can.
     */
    private fun capturePowerbands(sessionDirName: String?) {
        if (sessionDirName == null) return
        val root = rootInActiveWindow ?: return
        val node = findNodeByText(root, "Brainwave Powerbands") ?: return
        val clickable = nearestClickable(node) ?: return
        if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        mainHandler.postDelayed({
            captureScreenshot { bitmap ->
                if (bitmap == null) return@captureScreenshot
                worker.execute {
                    try {
                        SessionPipeline.storeAuxiliaryImage(this, sessionDirName, "sourcePowerbands.png", bitmap)
                    } catch (t: Throwable) {
                        Log.w(TAG, "powerbands capture failed", t)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
        }, EXPAND_WAIT_MS)
    }

    private fun captureScreenshot(onResult: (Bitmap?) -> Unit) {
        val since = System.currentTimeMillis() - lastScreenshotAt
        if (since < SCREENSHOT_INTERVAL_MS) {
            mainHandler.postDelayed({ captureScreenshot(onResult) }, SCREENSHOT_INTERVAL_MS - since)
            return
        }
        lastScreenshotAt = System.currentTimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val software = try {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    // Copy off the hardware buffer: getPixels() is unavailable on a hardware bitmap.
                    hardware?.copy(Bitmap.Config.ARGB_8888, false).also { hardware?.recycle() }
                } catch (t: Throwable) {
                    Log.e(TAG, "wrapHardwareBuffer failed", t); null
                } finally {
                    screenshot.hardwareBuffer.close()
                }
                mainHandler.post { onResult(software) }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot failed: $errorCode")
                mainHandler.post { onResult(null) }
            }
        })
    }

    // ------------------------------------------------------------------ tree helpers

    private fun expandMindCard(root: AccessibilityNodeInfo): Boolean {
        val mind = findNodeByText(root, "Mind") ?: return false
        val clickable = nearestClickable(mind) ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text) ?: return null
        return matches.firstOrNull { it.text?.toString()?.trim().equals(text, ignoreCase = true) }
            ?: matches.firstOrNull()
    }

    /**
     * Full screen width, spanning from the Mind card header down past the band legend.
     *
     * Deliberately not the union of the text nodes' bounds: the legend labels only reach about
     * half the screen width, and the plot extends well past them, so a tight union would clip
     * the right-hand end of the trace.
     */
    private fun graphRegionOfInterest(nodes: List<FlatNode>): Rect? {
        val mind = nodes.firstOrNull { it.text.trim().equals("Mind", ignoreCase = true) } ?: return null
        val legendLabels = setOf("active", "neutral", "calm")
        val legendBottom = nodes
            .filter { it.text.trim().lowercase(Locale.US) in legendLabels }
            .filter { it.bounds.top > mind.bounds.bottom }
            .maxOfOrNull { it.bounds.bottom } ?: return null
        if (legendBottom <= mind.bounds.bottom) return null
        val width = resources.displayMetrics.widthPixels
        return Rect(0, mind.bounds.bottom - 8, width, legendBottom + 24)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
