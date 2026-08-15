package com.expensetracker.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.expensetracker.worker.ParseAndStoreWorker

/**
 * Primary transaction-capture path (see REQUIREMENTS.md Architecture ยง1).
 *
 * Requires the user to grant "Notification access" — a special permission
 * separate from READ_SMS, requested via a dedicated onboarding screen that
 * deep-links to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.
 *
 * IMPORTANT: This receives ALL notifications from ALL apps, not just banks.
 * Filtering happens downstream in TransactionParser — this service does the
 * absolute minimum work needed to hand off to WorkManager, so it stays fast
 * and doesn't block the notification pipeline.
 */
class NotificationCaptureService : NotificationListenerService() {

    // Only packages we bother forwarding at all — keeps CPU/battery use down
    // by rejecting obviously irrelevant notifications (games, social, etc.)
    // before even reading their extras. Extend this list per-device by
    // letting the user pick their bank/UPI apps in Settings, rather than
    // hardcoding forever — see REQUIREMENTS.md Open Items.
    private val relevantPackagePrefixes = listOf(
        "com.google.android.apps.messaging", // default SMS app
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.sbi.SBIFreedomPlus"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (relevantPackagePrefixes.none { pkg.startsWith(it) }) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (text.isBlank()) return

        val combined = "$title $text"

        // Hand off immediately to a WorkManager job. We do NOT parse inline
        // here — keeps this callback (which the OS expects to return fast)
        // lightweight, and WorkManager handles retry/battery constraints.
        val request = OneTimeWorkRequestBuilder<ParseAndStoreWorker>()
            .setInputData(
                workDataOf(
                    ParseAndStoreWorker.KEY_SENDER to pkg,
                    ParseAndStoreWorker.KEY_TEXT to combined,
                    ParseAndStoreWorker.KEY_TIMESTAMP to sbn.postTime
                )
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}
