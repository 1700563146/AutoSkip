package com.example.autoskip.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.autoskip.a11y.AutoSkipService

/** Small helpers for the two permissions this app needs from the user. */
object Permissions {

    /**
     * True when our accessibility service is switched on in system settings.
     *
     * The live-instance check is tried first because it is instant and exact;
     * the settings string is the fallback for when the service is enabled but
     * not currently bound.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (AutoSkipService.isRunning()) return true

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val component = ComponentName(context, AutoSkipService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()

        return enabled.split(':').any {
            it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
