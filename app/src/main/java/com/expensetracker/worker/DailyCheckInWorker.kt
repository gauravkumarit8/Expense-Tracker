package com.autoexpensetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autoexpensetracker.util.DailyCheckInStore
import com.autoexpensetracker.util.ReminderNotificationHelper

/**
 * Fires the daily "review your spending" nudge. Re-checks
 * [DailyCheckInStore] on every run (rather than only at schedule time) so
 * that if the periodic work somehow survives a toggle-off — WorkManager
 * periodic work is normally cancelled immediately via
 * [DailyCheckInScheduler.cancel], but this is a cheap, harmless extra
 * guard against ever notifying after the user turned it off.
 */
class DailyCheckInWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (DailyCheckInStore.isEnabled(applicationContext)) {
            ReminderNotificationHelper.showDailyCheckIn(applicationContext)
        }
        return Result.success()
    }
}
