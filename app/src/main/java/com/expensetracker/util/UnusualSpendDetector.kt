package com.autoexpensetracker.util

import android.content.Context
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.data.TransactionDao

/**
 * Flags a transaction as an unusual spend if it's significantly larger than
 * the historical average for its category, and fires a local notification.
 *
 * Deliberately simple (mean-based threshold, no ML) — consistent with the
 * project's lightweight/on-device/explainable design goals. See
 * REQUIREMENTS.md ยง2.11 for the exact thresholds and reasoning.
 */
object UnusualSpendDetector {

    private const val MULTIPLIER_THRESHOLD = 2.5   // flag if >= 2.5x the category average
    private const val MIN_HISTORY_SIZE = 3          // need at least this many prior transactions to have a meaningful average

    suspend fun checkAndNotify(context: Context, dao: TransactionDao, newTransaction: Transaction) {
        if (newTransaction.direction != Direction.SENT || newTransaction.needsReview) return
        val category = Category.fromNameOrNull(newTransaction.category) ?: return

        val history = dao.getAllOnce().filter {
            it.id != newTransaction.id &&
                it.direction == Direction.SENT &&
                !it.needsReview &&
                Category.fromNameOrNull(it.category) == category
        }
        if (history.size < MIN_HISTORY_SIZE) return

        val average = history.map { it.amount }.average()
        if (average <= 0) return

        if (newTransaction.amount >= average * MULTIPLIER_THRESHOLD) {
            UnusualSpendNotificationHelper.show(context, newTransaction, category, average)
        }
    }
}