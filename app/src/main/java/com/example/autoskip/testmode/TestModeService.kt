package com.example.autoskip.testmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.example.autoskip.R
import com.example.autoskip.a11y.AutoSkipService
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.log.LogBus
import com.example.autoskip.log.LogLevel
import com.example.autoskip.ui.MainActivity
import kotlin.random.Random

/**
 * Test mode: floats a "跳过" button at random positions so the whole pipeline
 * can be exercised without installing a third-party app that happens to have a
 * splash ad.
 *
 * NOTE on the window flags. This overlay must stay *touchable*. Adding
 * `FLAG_NOT_TOUCHABLE` looks harmless and even appealing — nothing but the
 * accessibility service could then dismiss the button — but it also removes the
 * window from `AccessibilityService.getWindows()` entirely, so the service can
 * never find the button it is supposed to click. Verified on API 36: with the
 * flag the window list is `[systemui, TestModeActivity]`; without it the
 * overlay shows up and the click lands.
 *
 * The consequence is that a real tap can also dismiss the button, so a manual
 * tap is deliberately *not* counted as a hit — see [onOverlayClicked].
 */
class TestModeService : Service() {

    private lateinit var prefs: AppPrefs
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())

    private var visible = false
    private var destroyed = false

    private val respawn = Runnable { showAtRandomPosition() }

    /**
     * Nudges the service while the button is on screen.
     *
     * Real apps emit a constant stream of events for the service to react to,
     * but this overlay is static and, being ours, its own events are suppressed
     * on purpose. Without a poll there would be exactly one scan per spawn, and
     * a single missed one — say the service reconnecting at that moment — would
     * leave the button stuck forever.
     */
    private val rescan = object : Runnable {
        override fun run() {
            if (destroyed || !visible) return
            AutoSkipService.instance?.requestSelfScan()
            handler.postDelayed(this, RESCAN_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        prefs = AppPrefs.get(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForegroundCompat()
        buildOverlay()

        handler.post { showAtRandomPosition() }
        LogBus.log("测试模式已启动")
        notifyChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        isRunning = false
        if (instance === this) instance = null
        LogBus.log("测试模式已停止")
        notifyChanged()
        super.onDestroy()
    }

    // --- Overlay -----------------------------------------------------------

    private fun buildOverlay() {
        val size = dp(OVERLAY_WIDTH_DP)
        val height = dp(OVERLAY_HEIGHT_DP)
        val padding = dp(12)

        overlayView = TextView(this).apply {
            // The stable id is what AutoSkipService uses to tell this button
            // apart from the rest of our own UI, which must never be clicked.
            id = R.id.test_overlay_text
            text = getString(R.string.overlay_label)
            setTextColor(getColor(R.color.overlay_skip_text))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.bg_overlay_skip)
            // Clickable so the node advertises itself as such and
            // performAction(ACTION_CLICK) is accepted.
            isClickable = true
            setOnClickListener { onOverlayClicked() }
        }

        layoutParams = WindowManager.LayoutParams(
            size,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun showAtRandomPosition() {
        if (destroyed) return

        // Position first, then attach, so the button never flashes at (0, 0).
        // The size is fixed, so this needs no layout pass to be correct.
        randomizePosition()
        if (visible) {
            runCatching { windowManager.updateViewLayout(overlayView, layoutParams) }
        } else if (!addOverlay()) {
            return
        }

        spawnCount++
        notifyChanged()

        handler.removeCallbacks(rescan)
        handler.post(rescan)
    }

    private fun addOverlay(): Boolean {
        return runCatching {
            windowManager.addView(overlayView, layoutParams)
            visible = true
            true
        }.getOrElse { t ->
            LogBus.log("悬浮窗添加失败（是否已授予悬浮窗权限？）：${t.message}", LogLevel.ERROR)
            stopSelf()
            false
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(rescan)
        if (!visible) return
        runCatching { windowManager.removeView(overlayView) }
        visible = false
    }

    /** Keeps the button clear of the status bar, navigation bar and any cutout. */
    private fun randomizePosition() {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )

        val minX = insets.left
        val minY = insets.top
        val maxX = (bounds.width() - insets.right - layoutParams.width).coerceAtLeast(minX)
        val maxY = (bounds.height() - insets.bottom - layoutParams.height).coerceAtLeast(minY)

        layoutParams.x = minX + random.nextInt(maxX - minX + 1)
        layoutParams.y = minY + random.nextInt(maxY - minY + 1)
    }

    /**
     * Fires for a human tap *and* for the accessibility service's
     * `ACTION_CLICK`, which dispatches through the same `performClick()` path.
     * Only dismissal happens here — scoring is done by [handleAutoClick], which
     * the service calls directly, so a manual tap cannot inflate the hit count.
     *
     * This runs on whichever thread performed the click, so the window work is
     * handed to the main looper.
     */
    private fun onOverlayClicked() {
        LogBus.log("测试悬浮窗收到点击（手动或无障碍）")
        handler.post { dismissAndReschedule() }
    }

    /**
     * Called by [AutoSkipService] once it has successfully tapped the overlay.
     * Drives the hit counter and brings the button back somewhere else.
     */
    fun handleAutoClick() {
        handler.post {
            if (destroyed) return@post
            autoHitCount++
            notifyChanged()
            dismissAndReschedule()
        }
    }

    private fun dismissAndReschedule() {
        removeOverlay()
        handler.removeCallbacks(respawn)
        handler.postDelayed(respawn, prefs.testIntervalMs.coerceAtLeast(MIN_INTERVAL_MS))
    }

    // --- Notification ------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TestModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.action_stop_test_short),
                    stopIntent,
                ).build(),
            )
            .setOngoing(true)
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TestModeService"
        private const val CHANNEL_ID = "test_mode"
        private const val NOTIFICATION_ID = 1001
        private const val OVERLAY_WIDTH_DP = 110
        private const val OVERLAY_HEIGHT_DP = 46
        private const val MIN_INTERVAL_MS = 300L
        private const val RESCAN_INTERVAL_MS = 400L

        const val ACTION_STOP = "com.example.autoskip.testmode.STOP"

        @Volatile
        private var instance: TestModeService? = null

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var spawnCount: Int = 0
            private set

        @Volatile
        var autoHitCount: Int = 0
            private set

        /** Notified on the main thread whenever the counters change. */
        @Volatile
        private var listener: (() -> Unit)? = null

        private val main = Handler(Looper.getMainLooper())

        fun setListener(value: (() -> Unit)?) {
            listener = value
        }

        private fun notifyChanged() {
            val current = listener ?: return
            main.post { current() }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TestModeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TestModeService::class.java))
        }

        fun resetCounters() {
            spawnCount = 0
            autoHitCount = 0
            notifyChanged()
        }

        /**
         * Entry point for [AutoSkipService]: the overlay was tapped, so score it
         * and move it somewhere new.
         */
        fun onAutoClick() {
            instance?.handleAutoClick()
        }
    }
}
