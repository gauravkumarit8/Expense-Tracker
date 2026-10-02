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
 * Merchant grouping now goes through [MerchantMatcher] (fuzzy: strips
 * punctuation, reference numbers, and generic transaction-type words, then
 * groups by the remaining "brand" token) instead of exact trim+lowercase —
 * see that object's doc comment for the reasoning and the safety net
 * (the amount-consistency tolerance below) that keeps fuzzy grouping from
 * merging two genuinely different merchants that happen to share a first
 * word.
 */
object RecurringDetector {

    private const val MIN_MONTHS = 2
    private const val AMOUNT_TOLERANCE = 0.15 // 15% variance allowed around the average

    fun detect(transactions: List<Transaction>, existingReminders: List<Reminder>, dismissed: Set<String>): List<RecurringSuggestion> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val existingTitles = existingReminders.map { it.title.trim().lowercase() }.toSet()

        return transactions
            .filter { it.direction == Direction.SENT && !it.needsReview && !it.merchantOrContact.isNullOrBlank() }
            .groupBy { MerchantMatcher.canonicalKey(it.merchantOrContact!!) }
            .mapNotNull { (merchantKey, txs) ->
                // dismissed/existingTitles were keyed by the OLD exact
                // trim+lowercase name; also check every exact name in this
                // fuzzy group so a merchant dismissed under one spelling
                // variant doesn't reappear under a different variant that
                // now lands in the same fuzzy bucket.
                val exactNames = txs.map { it.merchantOrContact!!.trim().lowercase() }.toSet()
                if (merchantKey in existingTitles || merchantKey in dismissed) return@mapNotNull null
                if (exactNames.any { it in existingTitles || it in dismissed }) return@mapNotNull null

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

                // Display the most FREQUENT original spelling in the group,
                // not just the first occurrence, so a fuzzy-merged group
                // shows a representative real name rather than a random
                // variant.
                val representativeName = txs
                    .map { it.merchantOrContact!!.trim() }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }?.key ?: txs.first().merchantOrContact!!.trim()

                RecurringSuggestion(
                    merchant = representativeName,
                    averageAmount = average,
                    suggestedDueDay = mostCommonDay,
                    occurrenceCount = txs.size
                )
            }
            .sortedByDescending { it.occurrenceCount }
    }
}