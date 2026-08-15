package com.expensetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.expensetracker.worker.ParseAndStoreWorker

/**
 * Fallback capture path (see REQUIREMENTS.md Architecture ยง1).
 * Used only for messages that, on some OEM/dual-SIM configurations, never
 * surface as a system notification the NotificationCaptureService would see.
 * Deduplication against the primary path happens via rawTextHash in the DB.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (msg in messages) {
            val sender = msg.originatingAddress ?: continue
            val body = msg.messageBody ?: continue
            val timestamp = msg.timestampMillis

            val request = OneTimeWorkRequestBuilder<ParseAndStoreWorker>()
                .setInputData(
                    workDataOf(
                        ParseAndStoreWorker.KEY_SENDER to sender,
                        ParseAndStoreWorker.KEY_TEXT to body,
                        ParseAndStoreWorker.KEY_TIMESTAMP to timestamp
                    )
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
