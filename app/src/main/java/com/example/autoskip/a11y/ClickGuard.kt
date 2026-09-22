package com.example.autoskip.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.util.LruCache
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.model.DefaultRules

sealed interface GuardVerdict {
    data object Allow : GuardVerdict

    /**
     * @param retryAfterMs how long until this rejection could plausibly turn
     *   into an approval, or 0 when retrying is pointless (the target is
     *   off-screen, belongs to us, …). The service uses this to schedule a
     *   follow-up scan, so a button that appears during a cooldown is not
     *   missed forever.
     */
    data class Reject(val reason: String, val retryAfterMs: Long = 0L) : GuardVerdict
}

/**
 * Anti-misclick layer: the last checkpoint before a tap is actually performed.
 *
 * Every check here is a reason *not* to click. Rejections are returned rather
 * than swallowed so the UI can show why a promising-looking match was skipped —
 * without that, a blocked click is indistinguishable from a broken rule.
 */
class ClickGuard(private val prefs: AppPrefs) {

    private val recentNodes = LruCache<String, Long>(MAX_TRACKED_NODES)

    private var lastClickUptime = 0L
    private var currentPackage: String? = null
    private var clickedThisScan = 0

    /**
     * Starts a new scan for [pkg]. Switching packages clears the per-node
     * history: the same coordinates in a different app mean nothing.
     */
    fun beginScan(pkg: String) {
        if (pkg != currentPackage) {
            recentNodes.evictAll()
            currentPackage = pkg
        }
        clickedThisScan = 0
    }

    /** Records that a tap went through, arming the cooldowns. */
    fun recordClick(match: Match) {
        lastClickUptime = SystemClock.uptimeMillis()
        clickedThisScan++
        recentNodes.put(fingerprint(match), lastClickUptime)
    }

    fun approve(
        match: Match,
        ownPackage: String,
        displayBounds: Rect,
    ): GuardVerdict {
        val now = SystemClock.uptimeMillis()

        // 1. Never act on our own UI. The test-mode overlay is the single
        //    sanctioned exception, identified by its view id rather than by
        //    package alone, so the settings screens stay unreachable.
        if (match.ownerPackage == ownPackage) {
            val isTestOverlay = match.viewId == DefaultRules.TEST_OVERLAY_ENTRY_NAME
            if (!isTestOverlay || !prefs.allowSelfTestOverlay) {
                return GuardVerdict.Reject("目标属于本应用，已跳过")
            }
        }

        // 2. Bounds must describe a real, on-screen area.
        if (match.bounds.isEmpty) {
            return GuardVerdict.Reject("节点无有效边界")
        }
        if (!displayBounds.contains(match.bounds.centerX(), match.bounds.centerY())) {
            return GuardVerdict.Reject("节点中心点不在屏幕内")
        }

        // 3. Global cooldown between any two clicks.
        val sinceLastClick = now - lastClickUptime
        if (sinceLastClick < prefs.clickCooldownMs) {
            val remaining = prefs.clickCooldownMs - sinceLastClick
            return GuardVerdict.Reject("全局冷却中（剩余 ${remaining}ms）", retryAfterMs = remaining)
        }

        // 4. Same target, again and again — the classic misfire when a page
        //    keeps re-emitting content-changed events.
        val lastSeen = recentNodes.get(fingerprint(match))
        if (lastSeen != null && now - lastSeen < prefs.nodeCooldownMs) {
            val remaining = prefs.nodeCooldownMs - (now - lastSeen)
            return GuardVerdict.Reject("同一节点冷却中（剩余 ${remaining}ms）", retryAfterMs = remaining)
        }

        // 5. Per-scan budget.
        if (clickedThisScan >= prefs.maxClicksPerScan) {
            return GuardVerdict.Reject("本次扫描点击次数已达上限")
        }

        return GuardVerdict.Allow
    }

    /**
     * Identity of a click target, stable across rescans so the cooldown
     * survives the node object being replaced by a fresh instance.
     */
    private fun fingerprint(match: Match): String = buildString {
        append(match.ownerPackage).append('|')
        append(match.viewId).append('|')
        append(match.bounds.left).append(',')
        append(match.bounds.top).append(',')
        append(match.bounds.right).append(',')
        append(match.bounds.bottom).append('|')
        append(match.label)
    }

    private companion object {
        const val MAX_TRACKED_NODES = 256
    }
}
