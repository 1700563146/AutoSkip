package com.example.autoskip.model

/**
 * One row of the app whitelist.
 *
 * PLACEHOLDER: the list is persisted and shown in the UI, but rule dispatch is
 * not yet scoped per app — every enabled package currently runs the same
 * [DefaultRules] set. See [com.example.autoskip.data.AppPrefs.isPackageEnabled].
 */
data class WhitelistEntry(
    val packageName: String,
    val appName: String,
    val enabled: Boolean,
)
