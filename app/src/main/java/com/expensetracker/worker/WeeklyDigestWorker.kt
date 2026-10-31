package com.autoexpensetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autoexpensetracker.data.AppDatabase
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.util.WeeklyDigest
import com.autoexpensetracker.util.WeeklyDigestNotificationHelper
import com.autoexpensetracker.util.WeeklyDigestStore
import com.autoexpensetracker.util.formatInrWhole

/**
 * Sends the weekly summary. Re-checks the opt-in flag on every run (a periodic
 * request can outlive a toggle-off), refuses to send twice in 6 days, and stays
 * silent for a week with no transactions. Any failure reads as "try next week":
 * a missed summary is harmless, a crashing worker is not.
 */
class WeeklyDigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!WeeklyDigestStore.isEnabled(ctx)) return Result.success()
        val now = System.currentTimeMillis()
        if (!WeeklyDigest.isDue(WeeklyDigestStore.lastSentMillis(ctx), now)) return Result.success()
        return try {
            val entries = AppDatabase.getInstance(ctx).transactionDao().getAllOnce()
                .filter { it.direction == Direction.SENT || it.direction == Direction.RECEIVED }
                .map {
                    val category = Category.fromNameOrNull(it.category) ?: Category.OTHER
                    WeeklyDigest.Entry(
                        timestampMillis = it.timestampMillis,
                        amount = it.amount,
                        sent = it.direction == Direction.SENT,
                        categoryLabel = category.label,
                        countsForTopCategory = category != Category.TRANSFER
                    )
                }
            val message = WeeklyDigest.message(WeeklyDigest.compute(entries, now)) { formatInrWhole(it) }
            if (message != null) {
                WeeklyDigestNotificationHelper.show(ctx, message)
                WeeklyDigestStore.markSent(ctx, now)
            }
            Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }
}
