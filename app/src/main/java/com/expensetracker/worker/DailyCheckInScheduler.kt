package com.autoexpensetracker.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily check-in nudge for roughly 8 PM local time. Android
 * WorkManager periodic work doesn't guarantee an exact fire time (it's a
 * "at least every N hours, at the OS's discretion for battery reasons"
 * contract) — the initial delay just aims the first run at approximately
 * the right time of day; subsequent runs follow ~24h after that, so it
 * should stay roughly evening-scoped without ever needing exact-alarm
 * permissions, which would be a heavier ask for a "nice to have" nudge
 * than it's worth.
 */
object DailyCheckInScheduler {
    private const val WORK_NAME = "daily_checkin"
    private const val TARGET_HOUR = 20 // 8 PM

    fun schedule(context: Context) {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<DailyCheckInWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
