package com.autoexpensetracker.util

import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class SinceLastSalary(
    val amount: Double,
    val timestampMillis: Long,
    val confirmedOccurrences: Int,
    val spentSince: Double,
    val daysSince: Int
)

/**
 * Detects a recurring large credit (salary/main income) and reports what's
 * happened since the LAST time it actually arrived — not a prediction, a
 * look back at real data.
 *
 * Deliberately simple, matching the project's other detectors: for each
 * calendar month, take only the single LARGEST RECEIVED transaction (this
 * naturally excludes smaller same-month credits like interest or a refund
 * without needing to know anything about what salary "should" look like).
 * Across months, find the largest cluster of these monthly-max amounts
 * that sit within AMOUNT_TOLERANCE of each other — that's the confirmed
 * recurring credit. A one-off large transfer in some other month (larger
 * than salary) doesn't interfere: it becomes that month's "candidate" but
 * simply doesn't join the cluster if it doesn't recur at a similar amount.
 *
 * AMOUNT_TOLERANCE is 15%, not the 25% first tried — verified in
 * simulation that 25% produces false positives on two coincidentally
 * similar one-off payments with no real recurring pattern (e.g. two
 * unrelated freelance payments 25% apart getting misread as "salary");
 * 15% (matching the tolerance already used by RecurringDetector and
 * SubscriptionPriceChangeDetector) did not. See the decision log.
 *
 * Reports the MOST RECENT confirmed occurrence, which may be from a prior
 * month if this month's salary hasn't landed yet — intentional: this
 * answers "since I was last paid", not "this month's salary so far".
 */
object SalaryDetector {

    private const val MIN_MONTHS_FOR_PATTERN = 2
    private const val AMOUNT_TOLERANCE = 0.15

    fun detect(transactions: List<Transaction>): SinceLastSalary? {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())

        val monthlyMaxCredit = transactions
            .filter { it.direction == Direction.RECEIVED && !it.needsReview }
            .groupBy { monthFormat.format(Date(it.timestampMillis)) }
            .mapNotNull { (_, txs) -> txs.maxByOrNull { it.amount } }

        if (monthlyMaxCredit.size < MIN_MONTHS_FOR_PATTERN) return null

        // Try every candidate as a cluster center; keep the largest
        // resulting cluster. A tie (equally-sized clusters) keeps
        // whichever was found first — good enough for this purpose, not
        // worth a tie-break rule.
        val bestCluster = monthlyMaxCredit
            .map { center -> monthlyMaxCredit.filter { kotlin.math.abs(it.amount - center.amount) / center.amount <= AMOUNT_TOLERANCE } }
            .filter { it.size >= MIN_MONTHS_FOR_PATTERN }
            .maxByOrNull { it.size }
            ?: return null

        val latest = bestCluster.maxByOrNull { it.timestampMillis } ?: return null

        val spentSince = transactions
            .filter { it.direction == Direction.SENT && it.timestampMillis > latest.timestampMillis }
            .sumOf { it.amount }

        val daysSince = ((System.currentTimeMillis() - latest.timestampMillis) / (24L * 60 * 60 * 1000)).toInt()

        return SinceLastSalary(
            amount = latest.amount,
            timestampMillis = latest.timestampMillis,
            confirmedOccurrences = bestCluster.size,
            spentSince = spentSince,
            daysSince = daysSince
        )
    }
}
