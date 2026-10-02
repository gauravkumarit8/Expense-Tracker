package com.autoexpensetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoexpensetracker.data.Category

object BudgetWarningNotificationHelper {
    private const val CHANNEL_ID = "budget_warnings"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Budget warnings",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Alerts when you're on track to cross, or have crossed, a category budget" }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, category: Category, amount: Double, limit: Double, projected: Boolean) {
        ensureChannel(context)
        val title = if (projected) {
            "Heads up: ${category.emoji} ${category.label} budget"
        } else {
            "Over budget: ${category.emoji} ${category.label}"
        }
        val text = if (projected) {
            "At this pace you'll spend about ${formatInr(amount)} this month — your limit is ${formatInr(limit)}"
        } else {
            "You've spent ${formatInr(amount)} this month — your limit is ${formatInr(limit)}"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(("budget-" + category.name).hashCode(), notification)
    }
}
