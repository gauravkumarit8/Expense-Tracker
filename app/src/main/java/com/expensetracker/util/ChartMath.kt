package com.autoexpensetracker.util

import java.util.Calendar

/**
 * Pure arithmetic behind the Charts screen, kept free of Android and Compose
 * types so it can be compiled and tested on its own.
 */
object ChartMath {

    /** Number of days in the given month (month is Calendar.JANUARY..DECEMBER, 0-based). */
    fun daysInMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /**
     * Total per day-of-month. Index 0 is day 1. [points] are (timestampMillis,
     * amount) pairs; anything outside the given month is ignored, so callers
     * can pass a wider list.
     */
    fun dailyTotals(points: List<Pair<Long, Double>>, year: Int, month: Int): DoubleArray {
        val totals = DoubleArray(daysInMonth(year, month))
        val cal = Calendar.getInstance()
        for (p in points) {
            cal.timeInMillis = p.first
            if (cal.get(Calendar.YEAR) != year || cal.get(Calendar.MONTH) != month) continue
            val idx = cal.get(Calendar.DAY_OF_MONTH) - 1
            if (idx in totals.indices) totals[idx] += p.second
        }
        return totals
    }

    /**
     * How many days of the month have happened so far: today's day-of-month
     * for the current month, the full length for any past month, and 0 for a
     * future month. Used as the divisor for "daily average".
     */
    fun elapsedDays(year: Int, month: Int, nowMillis: Long): Int {
        val now = Calendar.getInstance()
        now.timeInMillis = nowMillis
        val ny = now.get(Calendar.YEAR)
        val nm = now.get(Calendar.MONTH)
        return when {
            year > ny || (year == ny && month > nm) -> 0
            year == ny && month == nm -> now.get(Calendar.DAY_OF_MONTH)
            else -> daysInMonth(year, month)
        }
    }

    fun dailyAverage(total: Double, elapsedDays: Int): Double =
        if (elapsedDays <= 0) 0.0 else total / elapsedDays

    /** Index of the largest value, or -1 if there is no positive value. */
    fun peakIndex(values: DoubleArray): Int {
        var best = -1
        var bestValue = 0.0
        for (i in values.indices) {
            if (values[i] > bestValue) { bestValue = values[i]; best = i }
        }
        return best
    }

    /** value / max as a 0..1 fraction, never below [minVisible] for a non-zero value so a small bar is still visible. */
    fun barFraction(value: Double, max: Double, minVisible: Float = 0.02f): Float {
        if (max <= 0.0 || value <= 0.0) return 0f
        val f = (value / max).toFloat()
        return if (f < minVisible) minVisible else if (f > 1f) 1f else f
    }

    /** Share label: "<1%" for a tiny non-zero share, otherwise a whole-number percent. */
    fun percentLabel(amount: Double, total: Double): String {
        if (total <= 0.0 || amount <= 0.0) return "0%"
        val pct = amount / total * 100.0
        return if (pct < 1.0) "<1%" else "${pct.toInt()}%"
    }
}
