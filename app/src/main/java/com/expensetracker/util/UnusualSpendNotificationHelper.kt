package com.autoexpensetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Transaction

object UnusualSpendNotificationHelper {
    private const val CHANNEL_ID = "unusual_spend_alerts"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Unusual spending alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Alerts when a transaction is much higher than your usual spending in that category" }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, transaction: Transaction, category: Category, historicalAverage: Double) {
        ensureChannel(context)
        val title = "Unusual spend: ${category.emoji} ${category.label}"
        val text = "₹${"%.2f".format(transaction.amount)} — your average is ₹${"%.2f".format(historicalAverage)}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(("unusual-" + transaction.id).hashCode(), notification)
    }
}