package com.autoexpensetracker.listener

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Shortcuts to the system screens a user has to visit when a phone's battery
 * manager keeps stopping notification capture. The Xiaomi screen is an OEM
 * internal that can move between MIUI/HyperOS versions and could not be tested
 * here, so every launch is wrapped and falls back to the standard Android
 * "App info" page, which exists on every phone.
 */
object OemSettings {

    /** Redmi and POCO phones report manufacturer "Xiaomi"; the brand field tells them apart. */
    fun isXiaomiFamily(): Boolean =
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("redmi", ignoreCase = true) ||
            Build.BRAND.equals("poco", ignoreCase = true)

    fun openAppDetails(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    /** Xiaomi's "Autostart" list; falls back to App info if that screen isn't there. */
    fun openXiaomiAutostart(context: Context): Boolean {
        val intent = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        return start(context, intent) || openAppDetails(context)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false // ActivityNotFoundException / SecurityException: this phone doesn't have that screen
    }
}
