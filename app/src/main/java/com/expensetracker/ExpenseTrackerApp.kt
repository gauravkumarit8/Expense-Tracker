package com.expensetracker

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.expensetracker.util.ReminderNotificationHelper
import com.expensetracker.worker.ReminderCheckWorker
import net.sqlcipher.database.SQLiteDatabase
import java.util.concurrent.TimeUnit

class ExpenseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SQLiteDatabase.loadLibs(this) // load native SQLCipher libs once

        ReminderNotificationHelper.ensureChannel(this)

        val reminderCheckRequest = PeriodicWorkRequestBuilder<ReminderCheckWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reminder_check",
            ExistingPeriodicWorkPolicy.KEEP,
            reminderCheckRequest
        )
    }
}