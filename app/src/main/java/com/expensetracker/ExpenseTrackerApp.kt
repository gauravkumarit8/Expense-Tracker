package com.expensetracker

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.expensetracker.util.ReminderNotificationHelper
import com.expensetracker.worker.ReminderCheckWorker
import com.google.android.gms.ads.MobileAds
import net.sqlcipher.database.SQLiteDatabase
import java.util.concurrent.TimeUnit

class ExpenseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SQLiteDatabase.loadLibs(this) // load native SQLCipher libs once

        ReminderNotificationHelper.ensureChannel(this)

        // Fire-and-forget init — ad loads simply won't succeed until this
        // completes, no need to block app startup on it. Gracefully does
        // nothing useful if the device has no network at launch; ad slots
        // just stay empty rather than the app failing to start. Core
        // tracking functionality never depends on this succeeding.
        MobileAds.initialize(this) { }

        val reminderCheckRequest = PeriodicWorkRequestBuilder<ReminderCheckWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reminder_check",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderCheckRequest
        )
    }
}