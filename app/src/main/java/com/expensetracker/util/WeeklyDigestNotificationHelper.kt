package com.autoexpensetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object WeeklyDigestNotificationHelper {
    private const val CHANNEL_ID = "weekly_summary"
    private const val NOTIFICATION_ID = -2 // fixed: a new summary replaces an old one still showing

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Its own channel so it can be muted in system settings without touching bill reminders or budget warnings.
        val channel = NotificationChannel(CHANNEL_ID, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A short summary of last week's spending" }
        manager.createNotificationChannel(channel)
    }

    /** Caller must already hold POST_NOTIFICATIONS on API 33+ (notify() silently does nothing otherwise). */
    fun show(context: Context, message: WeeklyDigest.Message) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // placeholder, same as the other notifications
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(NotificationIntents.openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
