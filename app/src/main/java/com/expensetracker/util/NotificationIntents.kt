package com.autoexpensetracker.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.autoexpensetracker.ui.MainActivity

/**
 * What happens when the user taps one of the app's notifications: the app opens.
 * Before 2026-10-31 none of them had a tap action, so tapping a bill reminder,
 * budget warning or check-in did nothing at all.
 */
object NotificationIntents {
    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Bring the running app forward instead of stacking a second copy.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
