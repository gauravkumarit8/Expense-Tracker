package com.autoexpensetracker.util

import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Reminder
import com.autoexpensetracker.data.Transaction
import java.text.SimpleDateFormat
import java.util.*

data class RecurringSuggestion(
    val merchant: String,
    val averageAmount: Double,
    val suggestedDueDay: Int,
    val occurrenceCount: Int
)

/**
 * Detects merchants with a SENT transaction appearing in 2+ distinct
 * calendar months at a roughly consistent amount, and suggests them as
 * candidate bill/subscription reminders.
 *
 * Deliberately simple (no ML, no fuzzy merchant-name matching beyond
 * trim+lowercase) — consistent with the project's lightweight/explainable
 * design goals. See REQUIREMENTS.md ยง2.11.
 */
object RecurringDetector {

    private const val MIN_MONTHS = 2
    private const val AMOUNT_TOLERANCE = 0.15 // 15% variance allowed around the average

    fun detect(transactions: List<Transaction>, existingReminders: List<Reminder>, dismissed: Set<String>): List<RecurringSuggestion> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val existingTitles = existingReminders.map { it.title.trim().lowercase() }.toSet()

        return transactions
            .filter { it.direction == Direction.SENT && !it.needsReview && !it.merchantOrContact.isNullOrBlank() }
            .groupBy { it.merchantOrContact!!.trim().lowercase() }
            .mapNotNull { (merchantKey, txs) ->
                if (merchantKey in existingTitles || merchantKey in dismissed) return@mapNotNull null

                val distinctMonths = txs.map { monthFormat.format(Date(it.timestampMillis)) }.distinct()
                if (distinctMonths.size < MIN_MONTHS) return@mapNotNull null

                val average = txs.map { it.amount }.average()
                if (average <= 0) return@mapNotNull null
                val consistent = txs.all { kotlin.math.abs(it.amount - average) / average <= AMOUNT_TOLERANCE }
                if (!consistent) return@mapNotNull null

                val mostCommonDay = txs
                    .map { tx -> Calendar.getInstance().apply { timeInMillis = tx.timestampMillis }.get(Calendar.DAY_OF_MONTH) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }?.key ?: 1

                RecurringSuggestion(
                    merchant = txs.first().merchantOrContact!!.trim(),
                    averageAmount = average,
                    suggestedDueDay = mostCommonDay,
                    occurrenceCount = txs.size
                )
            }
            .sortedByDescending { it.occurrenceCount }
    }
}