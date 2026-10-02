package com.autoexpensetracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object PriceChangeNotificationHelper {
    private const val CHANNEL_ID = "price_change_alerts"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Price change alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Alerts when a recurring merchant's charge changes from its usual amount" }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, alert: PriceChangeAlert) {
        ensureChannel(context)
        val up = alert.changeFraction > 0
        val title = if (up) "Price went up: ${alert.merchant}" else "Price went down: ${alert.merchant}"
        val pct = "%.0f".format(kotlin.math.abs(alert.changeFraction) * 100)
        val text = "${formatInr(alert.previousAmount)} -> ${formatInr(alert.newAmount)} ($pct%)"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(("pricechange-" + alert.merchant.trim().lowercase()).hashCode(), notification)
    }
}
