package com.expensetracker.util

import android.content.Context
import android.content.Intent
import android.provider.Settings

object NotificationAccessHelper {

    /** True if the user has granted this app Notification access. */
    fun isEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(context.packageName)
    }

    /** Intent that opens the system "Notification access" settings screen directly. */
    fun settingsIntent(): Intent =
        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
}