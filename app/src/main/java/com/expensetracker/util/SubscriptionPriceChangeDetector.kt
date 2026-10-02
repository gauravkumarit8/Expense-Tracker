package com.autoexpensetracker.util

import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction

data class PriceChangeAlert(
    val merchant: String,
    val previousAmount: Double,
    val newAmount: Double,
    val changeFraction: Double, // positive = increase, negative = decrease
    val newTransactionId: Long
)

/**
 * [RecurringDetector] requires every occurrence of a merchant to sit within
 * AMOUNT_TOLERANCE of the average, so a merchant whose price genuinely
 * changed (e.g. a subscription renewal going from ₹199 to ₹249) simply
 * stops being suggested at all — the price change itself is invisible.
 * This is a separate, narrower check for exactly that: a merchant with a
 * previously STABLE price whose most recent charge breaks that stability.
 *
 * Deliberately simple, matching the project's other detectors (no ML): the
 * "baseline" is every occurrence except the most recent, and a flag only
 * fires if (a) that baseline was itself internally consistent — i.e. there
 * really was a stable price to compare against, not just noisy spending —
 * and (b) the latest charge differs from the baseline average by more than
 * CHANGE_THRESHOLD.
 *
 * Known limitation: this compares the latest charge only to the average of
 * everything before it, not a true multi-step trend — a merchant that
 * creeps up by a little each month (₹199 -> ₹210 -> ₹221 -> ₹232) will
 * eventually cross the threshold and get flagged once the cumulative drift
 * is large enough, which is still useful, just later than a smarter trend
 * detector would catch it.
 */
object SubscriptionPriceChangeDetector {

    private const val MIN_BASELINE_OCCURRENCES = 2 // need this many prior charges to call it a "baseline"
    private const val BASELINE_TOLERANCE = 0.15     // baseline itself must be this consistent (matches RecurringDetector)
    private const val CHANGE_THRESHOLD = 0.10        // latest charge must differ from baseline by more than this to flag

    fun detect(transactions: List<Transaction>): List<PriceChangeAlert> {
        return transactions
            .filter { it.direction == Direction.SENT && !it.needsReview && !it.merchantOrContact.isNullOrBlank() }
            .groupBy { MerchantMatcher.canonicalKey(it.merchantOrContact!!) }
            .mapNotNull { (_, txsUnsorted) ->
                val txs = txsUnsorted.sortedBy { it.timestampMillis }
                if (txs.size < MIN_BASELINE_OCCURRENCES + 1) return@mapNotNull null

                val latest = txs.last()
                val baseline = txs.dropLast(1)
                val baselineAvg = baseline.map { it.amount }.average()
                if (baselineAvg <= 0) return@mapNotNull null

                val baselineStable = baseline.all { kotlin.math.abs(it.amount - baselineAvg) / baselineAvg <= BASELINE_TOLERANCE }
                if (!baselineStable) return@mapNotNull null

                val changeFraction = (latest.amount - baselineAvg) / baselineAvg
                if (kotlin.math.abs(changeFraction) <= CHANGE_THRESHOLD) return@mapNotNull null

                PriceChangeAlert(
                    merchant = latest.merchantOrContact!!.trim(),
                    previousAmount = baselineAvg,
                    newAmount = latest.amount,
                    changeFraction = changeFraction,
                    newTransactionId = latest.id
                )
            }
            .sortedByDescending { kotlin.math.abs(it.changeFraction) }
    }
}
