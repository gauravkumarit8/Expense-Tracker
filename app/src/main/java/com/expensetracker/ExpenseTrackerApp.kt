package com.autoexpensetracker

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.autoexpensetracker.util.ReminderNotificationHelper
import com.autoexpensetracker.worker.ReminderCheckWorker
import net.sqlcipher.database.SQLiteDatabase
import java.util.concurrent.TimeUnit

class ExpenseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SQLiteDatabase.loadLibs(this) // load native SQLCipher libs once

        ReminderNotificationHelper.ensureChannel(this)

        // Mobile Ads SDK initialization moved to MainActivity.onCreate,
        // gated behind UMP consent gathering (see ads/ConsentManager.kt and
        // REQUIREMENTS.md ยง2.23) — it used to run unconditionally right
        // here, which is no longer correct: requestConsentInfoUpdate()
        // needs an Activity (not an Application Context), and Google's EU
        // User Consent Policy requires consent be resolved before the ads
        // SDK initializes at all for EEA/UK users, not just before an ad
        // request.

        val reminderCheckRequest = PeriodicWorkRequestBuilder<ReminderCheckWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reminder_check",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderCheckRequest
        )
    }
}