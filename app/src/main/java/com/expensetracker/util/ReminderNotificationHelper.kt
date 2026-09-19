package com.autoexpensetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ReminderNotificationHelper {
    private const val CHANNEL_ID = "bill_reminders"
    private const val CHECKIN_CHANNEL_ID = "daily_checkin"
    private const val CHECKIN_NOTIFICATION_ID = -1 // fixed ID: a new check-in replaces any still-showing one rather than stacking

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bill reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Reminders for recurring bills and subscriptions" }
        manager.createNotificationChannel(channel)

        // Separate channel from bill reminders (a due-date-specific,
        // per-item thing the user explicitly set up) so this general daily
        // nudge can be muted/adjusted independently in system settings
        // without also silencing actual bill due-date alerts.
        val checkinChannel = NotificationChannel(
            CHECKIN_CHANNEL_ID,
            "Daily check-in",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "A daily nudge to review today's spending" }
        manager.createNotificationChannel(checkinChannel)
    }

    /** Shows the daily "review your spending" nudge. Caller must already hold POST_NOTIFICATIONS on API 33+. */
    fun showDailyCheckIn(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // placeholder — see REQUIREMENTS.md app icon Open Item
            .setContentTitle("How'd today go?")
            .setContentText("Take a minute to check today's spending")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(CHECKIN_NOTIFICATION_ID, notification)
    }

    /** Shows a reminder notification. Caller must already hold POST_NOTIFICATIONS on API 33+. */
    fun show(context: Context, reminderId: Long, title: String, amount: Double?) {
        ensureChannel(context)
        val text = if (amount != null) "₹${"%.2f".format(amount)} due today" else "Due today"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // placeholder — see REQUIREMENTS.md app icon Open Item
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // NotificationManagerCompat.notify silently no-ops if POST_NOTIFICATIONS
        // isn't granted on API 33+, so this is safe to call unconditionally.
        NotificationManagerCompat.from(context).notify(reminderId.toInt(), notification)
    }
}