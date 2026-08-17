package com.expensetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ReminderNotificationHelper {
    private const val CHANNEL_ID = "bill_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bill reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Reminders for recurring bills and subscriptions" }
        manager.createNotificationChannel(channel)
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