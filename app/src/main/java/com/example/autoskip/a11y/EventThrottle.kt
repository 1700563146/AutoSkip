package com.example.autoskip.a11y

import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.autoskip.data.AppPrefs

/**
 * Anti-jitter layer.
 *
 * `TYPE_WINDOW_CONTENT_CHANGED` is by far the noisiest event an accessibility
 * service receives — a single animated splash screen can emit dozens per
 * second. Every one of those would otherwise trigger a full node-tree walk.
 *
 * The service config's `notificationTimeout` already coalesces events at the
 * framework level; this class is the second, in-process layer (GKD does the
 * same belt-and-braces thing) and additionally drops events that are useless
 * regardless of timing.
 */
class EventThrottle(private val prefs: AppPrefs) {

    private var lastContentEventTime = 0L
    private var lastStateEventTime = 0L
    private var lastAcceptedKey: String? = null
    private var lastAcceptedTime = 0L

    /**
     * Returns true when the event is worth a node-tree walk.
     *
     * @param ownPackage this app's package name, used for self-event suppression.
     */
    fun shouldProcess(event: AccessibilityEvent, ownPackage: String): Boolean {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return false
        }

        val pkg = event.packageName?.toString() ?: return false
        if (pkg.isEmpty()) return false

        // Self-event suppression. Our own windows generate just as much churn as
        // anyone else's, and reacting to them is how a service ends up clicking
        // its own UI. The one sanctioned exception is handled further down the
        // pipeline by view id, not here.
        if (pkg == ownPackage) return false

        if (pkg in NOISY_PACKAGES) return false

        // Multi-display events describe a window the user is not looking at.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            event.displayId != Display.DEFAULT_DISPLAY
        ) {
            return false
        }

        val now = SystemClock.uptimeMillis()
        val key = "$type|$pkg|${event.className}"

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val sinceStateChange = now - lastStateEventTime
            // A fresh window-state change means something structurally new
            // appeared, so let content events through for a short grace period
            // instead of sitting out the debounce window.
            if (now - lastContentEventTime < prefs.debounceMs &&
                sinceStateChange > STATE_CHANGE_GRACE_MS
            ) {
                return false
            }
            lastContentEventTime = now
        } else {
            lastStateEventTime = now
        }

        // Coalesce a burst that keeps describing the same window: a repeat of
        // the exact same (type, package, class) inside the debounce window adds
        // no new information.
        if (key == lastAcceptedKey && now - lastAcceptedTime < prefs.debounceMs) {
            return false
        }
        lastAcceptedKey = key
        lastAcceptedTime = now

        return true
    }

    /** Clears timing state, e.g. when the service is reconnected. */
    fun reset() {
        lastContentEventTime = 0L
        lastStateEventTime = 0L
        lastAcceptedKey = null
        lastAcceptedTime = 0L
    }

    companion object {
        private const val STATE_CHANGE_GRACE_MS = 500L

        /**
         * Windows that churn constantly and never contain an ad to skip:
         * the system UI (status bar clock, notification shade) and the input
         * method (every keystroke redraws it).
         */
        private val NOISY_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.inputmethod.latin",
        )
    }
}
