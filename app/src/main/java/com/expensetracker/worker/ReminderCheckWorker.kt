package com.autoexpensetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autoexpensetracker.data.AppDatabase
import com.autoexpensetracker.util.ReminderNotificationHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * Runs roughly once a day (scheduled as periodic work — see
 * ExpenseTrackerApp.onCreate). Checks each reminder's dueDayOfMonth against
 * today's date and fires a local notification if due, guarding against
 * duplicate notifications in the same month via lastNotifiedYearMonth.
 */
class ReminderCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(applicationContext).reminderDao()
        val today = Calendar.getInstance()
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val yearMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(today.time)

        for (reminder in dao.getAllOnce()) {
            val alreadyNotifiedThisMonth = reminder.lastNotifiedYearMonth == yearMonth
            // Treat any dueDayOfMonth beyond today's month length as "due on
            // the last day of this month" so e.g. day 31 still fires in Feb.
            val daysInMonth = today.getActualMaximum(Calendar.DAY_OF_MONTH)
            val effectiveDueDay = minOf(reminder.dueDayOfMonth, daysInMonth)

            if (!alreadyNotifiedThisMonth && todayDay == effectiveDueDay) {
                ReminderNotificationHelper.show(applicationContext, reminder.id, reminder.title, reminder.amount)
                dao.update(reminder.copy(lastNotifiedYearMonth = yearMonth))
            }
        }
        return Result.success()
    }
}