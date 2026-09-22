package com.example.autoskip.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * All persisted settings, backed by a single [SharedPreferences] file.
 *
 * Reads happen on every accessibility event, so values are kept in memory and
 * written through on change rather than re-read from disk each time.
 */
class AppPrefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, Defaults.ENABLED)
        set(value) = sp.edit { putBoolean(KEY_ENABLED, value) }

    /** Minimum gap between two scans triggered by content-change events. */
    var debounceMs: Long
        get() = sp.getLong(KEY_DEBOUNCE_MS, Defaults.DEBOUNCE_MS)
        set(value) = sp.edit { putLong(KEY_DEBOUNCE_MS, value) }

    /** Minimum gap between two successful clicks, anywhere. */
    var clickCooldownMs: Long
        get() = sp.getLong(KEY_CLICK_COOLDOWN_MS, Defaults.CLICK_COOLDOWN_MS)
        set(value) = sp.edit { putLong(KEY_CLICK_COOLDOWN_MS, value) }

    /** Minimum gap before the *same* node may be clicked again. */
    var nodeCooldownMs: Long
        get() = sp.getLong(KEY_NODE_COOLDOWN_MS, Defaults.NODE_COOLDOWN_MS)
        set(value) = sp.edit { putLong(KEY_NODE_COOLDOWN_MS, value) }

    /** How many targets a single scan is allowed to click. */
    var maxClicksPerScan: Int
        get() = sp.getInt(KEY_MAX_CLICKS, Defaults.MAX_CLICKS_PER_SCAN)
        set(value) = sp.edit { putInt(KEY_MAX_CLICKS, value) }

    /** Hard cap on nodes visited per scan; protects against huge trees. */
    var maxScanNodes: Int
        get() = sp.getInt(KEY_MAX_SCAN_NODES, Defaults.MAX_SCAN_NODES)
        set(value) = sp.edit { putInt(KEY_MAX_SCAN_NODES, value) }

    /** Wall-clock budget for a single scan, in milliseconds. */
    var scanTimeoutMs: Long
        get() = sp.getLong(KEY_SCAN_TIMEOUT_MS, Defaults.SCAN_TIMEOUT_MS)
        set(value) = sp.edit { putLong(KEY_SCAN_TIMEOUT_MS, value) }

    /**
     * When false, nothing belonging to our own package is ever clicked — not
     * even the test-mode overlay. Toggling it off is the quickest way to watch
     * self-event suppression actually work.
     */
    var allowSelfTestOverlay: Boolean
        get() = sp.getBoolean(KEY_ALLOW_SELF, Defaults.ALLOW_SELF_TEST_OVERLAY)
        set(value) = sp.edit { putBoolean(KEY_ALLOW_SELF, value) }

    /** How long the test overlay stays hidden after being clicked. */
    var testIntervalMs: Long
        get() = sp.getLong(KEY_TEST_INTERVAL_MS, Defaults.TEST_INTERVAL_MS)
        set(value) = sp.edit { putLong(KEY_TEST_INTERVAL_MS, value) }

    // --- Whitelist (placeholder) -------------------------------------------

    var defaultAllowUnlisted: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_ALLOW, Defaults.DEFAULT_ALLOW_UNLISTED)
        set(value) = sp.edit { putBoolean(KEY_DEFAULT_ALLOW, value) }

    /**
     * Whether [pkg] should be processed at all.
     *
     * PLACEHOLDER: this decision is honoured by the service, but because there
     * is only one built-in rule set, "enabled" and "disabled" are currently the
     * only two behaviours. Per-app rule assignment is where GKD's subscription
     * model would plug in.
     */
    fun isPackageEnabled(pkg: String): Boolean {
        if (pkg in readSet(KEY_ENABLED_PACKAGES)) return true
        if (pkg in readSet(KEY_DISABLED_PACKAGES)) return false
        return defaultAllowUnlisted
    }

    /** Records an explicit override for [pkg], or clears it when it matches the default. */
    fun setPackageEnabled(pkg: String, enabled: Boolean) {
        val enabledSet = readSet(KEY_ENABLED_PACKAGES).toMutableSet()
        val disabledSet = readSet(KEY_DISABLED_PACKAGES).toMutableSet()
        enabledSet.remove(pkg)
        disabledSet.remove(pkg)
        if (enabled) enabledSet.add(pkg) else disabledSet.add(pkg)
        sp.edit {
            putStringSet(KEY_ENABLED_PACKAGES, enabledSet)
            putStringSet(KEY_DISABLED_PACKAGES, disabledSet)
        }
    }

    /** Drops every explicit override, returning all apps to the default policy. */
    fun clearWhitelistOverrides() {
        sp.edit {
            remove(KEY_ENABLED_PACKAGES)
            remove(KEY_DISABLED_PACKAGES)
        }
    }

    /** Resets the tuning parameters (not the whitelist) to their shipped values. */
    fun resetTunablesToDefaults() {
        sp.edit {
            putLong(KEY_DEBOUNCE_MS, Defaults.DEBOUNCE_MS)
            putLong(KEY_CLICK_COOLDOWN_MS, Defaults.CLICK_COOLDOWN_MS)
            putLong(KEY_NODE_COOLDOWN_MS, Defaults.NODE_COOLDOWN_MS)
            putInt(KEY_MAX_CLICKS, Defaults.MAX_CLICKS_PER_SCAN)
            putInt(KEY_MAX_SCAN_NODES, Defaults.MAX_SCAN_NODES)
            putLong(KEY_SCAN_TIMEOUT_MS, Defaults.SCAN_TIMEOUT_MS)
            putLong(KEY_TEST_INTERVAL_MS, Defaults.TEST_INTERVAL_MS)
            putBoolean(KEY_ALLOW_SELF, Defaults.ALLOW_SELF_TEST_OVERLAY)
            putBoolean(KEY_DEFAULT_ALLOW, Defaults.DEFAULT_ALLOW_UNLISTED)
        }
    }

    /**
     * [SharedPreferences.getStringSet] hands back a set that must not be
     * mutated, so every read returns a defensive copy.
     */
    private fun readSet(key: String): Set<String> =
        sp.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val NAME = "autoskip_prefs"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_DEBOUNCE_MS = "debounce_ms"
        private const val KEY_CLICK_COOLDOWN_MS = "click_cooldown_ms"
        private const val KEY_NODE_COOLDOWN_MS = "node_cooldown_ms"
        private const val KEY_MAX_CLICKS = "max_clicks_per_scan"
        private const val KEY_MAX_SCAN_NODES = "max_scan_nodes"
        private const val KEY_SCAN_TIMEOUT_MS = "scan_timeout_ms"
        private const val KEY_ALLOW_SELF = "allow_self_test_overlay"
        private const val KEY_TEST_INTERVAL_MS = "test_interval_ms"
        private const val KEY_DEFAULT_ALLOW = "default_allow_unlisted"
        private const val KEY_ENABLED_PACKAGES = "whitelist_enabled"
        private const val KEY_DISABLED_PACKAGES = "whitelist_disabled"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }

    /** Shipped defaults, also used to seed the settings sliders. */
    object Defaults {
        const val ENABLED = true
        const val DEBOUNCE_MS = 300L
        const val CLICK_COOLDOWN_MS = 1500L
        const val NODE_COOLDOWN_MS = 5000L

        /**
         * One click per scan on purpose: after a click the node tree we just
         * walked is stale, so acting again on the same snapshot is the easiest
         * way to misfire. Raise it only if a page genuinely needs two taps.
         */
        const val MAX_CLICKS_PER_SCAN = 1

        const val MAX_SCAN_NODES = 4096
        const val SCAN_TIMEOUT_MS = 800L
        const val ALLOW_SELF_TEST_OVERLAY = true
        const val TEST_INTERVAL_MS = 1500L
        const val DEFAULT_ALLOW_UNLISTED = true
    }
}
