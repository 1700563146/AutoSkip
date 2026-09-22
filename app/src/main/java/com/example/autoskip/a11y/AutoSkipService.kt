package com.example.autoskip.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.log.LogBus
import com.example.autoskip.log.LogLevel
import com.example.autoskip.model.DefaultRules
import com.example.autoskip.testmode.TestModeService

/**
 * The accessibility service that does the actual work.
 *
 * Pipeline, in order:
 *
 * ```
 * onAccessibilityEvent
 *   -> EventThrottle        anti-jitter: drop noisy/redundant/uninteresting events
 *   -> single worker thread serialises scans (no two walks at once)
 *   -> collectRoots         gather the node trees worth looking at
 *   -> whitelist check      placeholder gate, per package
 *   -> NodeTraversal        BFS for rule matches
 *   -> ClickGuard           anti-misclick: reject anything unsafe
 *   -> GestureTapper        perform the tap
 *   -> LogBus               report what happened
 * ```
 */
class AutoSkipService : AccessibilityService() {

    /** Only the fields we need, copied off the pooled event object. */
    private data class EventSnapshot(
        val pkg: String,
        val className: String?,
        /** True for a scan the test mode asked for explicitly. */
        val explicit: Boolean = false,
    )

    private lateinit var prefs: AppPrefs
    private lateinit var throttle: EventThrottle
    private lateinit var guard: ClickGuard
    private lateinit var tapper: GestureTapper

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    /** Re-entrancy guard: one scan at a time. */
    @Volatile
    private var scanning = false

    private val displayBounds = Rect()
    private var lastWhitelistRejection: String? = null
    private var lastScannedPackage: String? = null
    private val lastLogged = HashMap<String, String>()

    /** Snapshot queued by [scheduleRetry], consumed by [retryRunnable]. */
    private var pendingRetry: EventSnapshot? = null

    private val retryRunnable = Runnable {
        pendingRetry?.let { runScan(it) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        prefs = AppPrefs.get(this)
        throttle = EventThrottle(prefs)
        guard = ClickGuard(prefs)
        tapper = GestureTapper(this)

        val thread = HandlerThread("autoskip-scan").also { it.start() }
        workerThread = thread
        worker = Handler(thread.looper)

        refreshDisplayBounds()
        LogBus.log("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        if (!prefs.enabled) return

        if (!throttle.shouldProcess(safeEvent, packageName)) return

        // Never hold on to an AccessibilityEvent: the framework pools and
        // recycles them, so the worker thread gets a plain copy of the fields.
        val pkg = safeEvent.packageName?.toString()
        if (pkg.isNullOrEmpty()) return

        enqueue(EventSnapshot(pkg = pkg, className = safeEvent.className?.toString()))
    }

    /**
     * Asks for a scan of our own windows. Used by the test mode, which knows
     * exactly when its overlay appeared and should not have to wait for — or
     * depend on — an event from whatever app happens to be underneath.
     */
    fun requestSelfScan() {
        if (!prefs.enabled) return
        enqueue(EventSnapshot(pkg = packageName, className = null, explicit = true))
    }

    private fun enqueue(snapshot: EventSnapshot) {
        if (scanning) return
        worker?.post { runScan(snapshot) }
    }

    private fun runScan(snapshot: EventSnapshot) {
        scanning = true
        try {
            if (!snapshot.explicit && !prefs.isPackageEnabled(snapshot.pkg)) {
                // Only mention it when the app changes, otherwise a disabled app
                // that keeps redrawing would flood the log.
                if (lastWhitelistRejection != snapshot.pkg) {
                    lastWhitelistRejection = snapshot.pkg
                    LogBus.log("${snapshot.pkg} 已在白名单中禁用，不再处理", LogLevel.INFO)
                }
                return
            }

            if (snapshot.pkg != lastScannedPackage) {
                lastScannedPackage = snapshot.pkg
                lastLogged.clear()
            }

            val roots = collectRoots(snapshot.pkg, diagnostic = snapshot.explicit)
            if (roots.isEmpty()) {
                // The most likely failure mode, and otherwise completely
                // invisible: FLAG_RETRIEVE_INTERACTIVE_WINDOWS not granted, or
                // every matching window reporting a null root.
                if (snapshot.explicit) {
                    logOnce("noroots", "测试扫描：读不到 ${snapshot.pkg} 的任何窗口", LogLevel.ERROR)
                }
                return
            }

            val deadline = SystemClock.uptimeMillis() + prefs.scanTimeoutMs
            val matches = NodeTraversal.collect(
                roots = roots,
                rules = DefaultRules.ALL,
                maxNodes = prefs.maxScanNodes,
                deadlineUptimeMs = deadline,
            )

            LogBus.countScan()
            if (matches.isEmpty()) {
                if (snapshot.explicit) {
                    logOnce("nomatch", "测试扫描：读取 ${roots.size} 个窗口，未命中任何规则")
                }
                return
            }

            guard.beginScan(snapshot.pkg)
            var clicked = 0
            var retryAfter = 0L

            for (match in matches) {
                if (clicked >= prefs.maxClicksPerScan) break
                when (val verdict = guard.approve(match, packageName, displayBounds)) {
                    is GuardVerdict.Reject -> {
                        logRejection(match, verdict.reason)
                        retryAfter = maxOf(retryAfter, verdict.retryAfterMs)
                    }

                    GuardVerdict.Allow -> {
                        performTap(match)
                        clicked++
                    }
                }
            }

            // A match blocked only by a cooldown is a near miss, not a failure.
            // Without this, a button that shows up mid-cooldown would sit there
            // until some unrelated event happened to trigger another scan.
            if (clicked == 0 && retryAfter > 0) {
                scheduleRetry(snapshot, retryAfter)
            }
        } catch (t: Throwable) {
            LogBus.log("扫描异常：${t.message}", LogLevel.ERROR)
        } finally {
            scanning = false
        }
    }

    /**
     * Gathers every node tree worth searching.
     *
     * The active window alone is not enough. A `TYPE_APPLICATION_OVERLAY` window
     * created with `FLAG_NOT_FOCUSABLE` — which is exactly what the test-mode
     * button is — never becomes the active window, so `rootInActiveWindow` would
     * never contain it.
     */
    private fun collectRoots(eventPkg: String, diagnostic: Boolean): List<AccessibilityNodeInfo> {
        val includeSelf = TestModeService.isRunning && prefs.allowSelfTestOverlay
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val summary = StringBuilder()

        // getWindows() throws unless FLAG_RETRIEVE_INTERACTIVE_WINDOWS was granted.
        runCatching {
            for (window in windows) {
                val root = window.root
                val windowPkg = root?.packageName?.toString()
                summary.append("[type=").append(window.type)
                    .append(" pkg=").append(windowPkg)
                    .append(" root=").append(root != null)
                    .append("] ")
                if (root != null && (windowPkg == eventPkg || (includeSelf && windowPkg == packageName))) {
                    roots += root
                }
            }
        }.onFailure { summary.append("getWindows() 抛异常：${it.message}") }

        if (diagnostic) {
            logOnce("windows", "窗口枚举（要匹配的包=$eventPkg，includeSelf=$includeSelf）：$summary")
        }

        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots += it }
        }
        return roots
    }

    /**
     * Rejections repeat for as long as the cooldown lasts, so an identical
     * message is only reported once. Otherwise a single held-down button would
     * bury everything else in the log.
     */
    private fun logRejection(match: Match, reason: String) {
        logOnce("reject", "命中「${match.label}」但未点击：$reason", LogLevel.REJECT)
    }

    /**
     * Suppresses repeats of the same message *per category*. Categories matter:
     * keyed on the text alone, a periodic diagnostic and a periodic rejection
     * would keep resetting each other and both would spam.
     */
    private fun logOnce(category: String, message: String, level: LogLevel = LogLevel.INFO) {
        if (lastLogged[category] == message) return
        lastLogged[category] = message
        LogBus.log(message, level)
    }

    private fun scheduleRetry(snapshot: EventSnapshot, delayMs: Long) {
        val handler = worker ?: return
        pendingRetry = snapshot
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs + RETRY_MARGIN_MS)
    }

    private fun performTap(match: Match) {
        // Arm the cooldowns on the decision rather than on the async result:
        // that also covers the window while a gesture is still in flight.
        guard.recordClick(match)

        tapper.tap(match) { success, how ->
            if (success) {
                LogBus.countClick()
                LogBus.log("命中「${match.label}」→ 已点击（$how）", LogLevel.HIT)
                if (match.ownerPackage == packageName) {
                    TestModeService.onAutoClick()
                }
            } else {
                LogBus.log("命中「${match.label}」→ 点击失败：$how", LogLevel.ERROR)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayBounds()
    }

    private fun refreshDisplayBounds() {
        runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            displayBounds.set(wm.currentWindowMetrics.bounds)
        }
    }

    override fun onInterrupt() {
        LogBus.log("无障碍服务被系统中断", LogLevel.ERROR)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        pendingRetry = null
        worker?.removeCallbacksAndMessages(null)
        worker = null
        workerThread?.quitSafely()
        workerThread = null
        scanning = false
        if (instance === this) {
            instance = null
            LogBus.log("无障碍服务已断开")
        }
    }

    companion object {
        /**
         * Fired slightly after the cooldown expires rather than exactly on it,
         * so timer jitter cannot land the retry a millisecond too early and get
         * rejected all over again.
         */
        private const val RETRY_MARGIN_MS = 60L

        @Volatile
        var instance: AutoSkipService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
