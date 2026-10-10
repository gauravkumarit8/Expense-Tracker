package com.autoexpensetracker.util

import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import java.util.Calendar

/** One calendar month's totals within a year. [net] = received - sent. */
data class MonthTotals(
    val month: MonthRange,
    val received: Double,
    val sent: Double,
    val count: Int
) {
    val net: Double get() = received - sent
}

/**
 * Aggregations behind the Year view of the history screen — the
 * Jan-to-Dec table of received / sent / net per month.
 *
 * Plain loops and arrays rather than groupBy/sumOf: it's a single pass over
 * the transactions, and it keeps this file free of stdlib features newer than
 * the oldest compiler it was checked with (see the decision log).
 *
 * Totals follow the same rules as the monthly hero card and the net-summary
 * cards: every transaction counts, including ones flagged needs-review, and a
 * transaction whose direction is UNKNOWN counts as neither received nor sent.
 */
object PeriodBreakdown {

    /** Always exactly 12 entries, January..December of [year]. */
    fun forYear(transactions: List<Transaction>, year: Int): List<MonthTotals> {
        val received = DoubleArray(12)
        val sent = DoubleArray(12)
        val counts = IntArray(12)
        val cal = Calendar.getInstance()

        for (tx in transactions) {
            cal.timeInMillis = tx.timestampMillis
            if (cal.get(Calendar.YEAR) != year) continue
            val m = cal.get(Calendar.MONTH)
            counts[m] = counts[m] + 1
            if (tx.direction == Direction.RECEIVED) {
                received[m] = received[m] + tx.amount
            } else if (tx.direction == Direction.SENT) {
                sent[m] = sent[m] + tx.amount
            }
        }

        val result = ArrayList<MonthTotals>(12)
        for (m in 0 until 12) {
            result.add(MonthTotals(MonthRange(year, m), received[m], sent[m], counts[m]))
        }
        return result
    }
}
