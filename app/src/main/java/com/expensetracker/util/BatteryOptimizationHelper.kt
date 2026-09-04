package com.autoexpensetracker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Deliberately does NOT use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * (the direct "exempt this app from battery optimization" system dialog).
 * That intent requires declaring the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * permission in the manifest, which is itself a Play Console-restricted
 * permission requiring a core-functionality justification — similar to
 * exact alarms or full-screen intents — and battery exemption isn't core
 * to this app (it's a reliability nice-to-have for background notification
 * capture, not something the app is unusable without).
 *
 * Instead this opens the app's own system "App info" screen, where battery
 * settings are one tap away for the user to adjust themselves if they
 * choose to. This needs no special manifest permission and no Play
 * Console declaration at all.
 */
object BatteryOptimizationHelper {
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}