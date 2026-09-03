package com.expensetracker.util

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A single calendar month (year + zero-based month, matching
 * `java.util.Calendar`'s convention) used to scope the Transactions home
 * screen and `MonthlyHistoryScreen` to "this month" / a user-picked month,
 * per REQUIREMENTS.md ยง2.19.
 *
 * Deliberately built on `java.util.Calendar` rather than `java.time.YearMonth`
 * — the project's minSdk is 24 with no core library desugaring configured in
 * `app/build.gradle`, so `java.time` isn't safely available on every
 * supported device. This also matches the `Calendar`-based date-range logic
 * already used in `MainActivity.filterTransactions()`.
 *
 * Implements [Serializable] specifically so it can be stored in
 * `rememberSaveable` on `MonthlyHistoryScreen` — a plain data class isn't
 * saveable across process death/config change on its own, and without this
 * `rememberSaveable(mutableStateOf(MonthRange.current()))` would throw at
 * runtime.
 */
data class MonthRange(val year: Int, val month: Int) : Serializable { // month: Calendar.JANUARY..Calendar.DECEMBER (0-based)

    fun startOfMonthMillis(): Long = Calendar.getInstance().apply {
        set(year, month, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Inclusive end-of-month millis (23:59:59.999 on the last day). */
    fun endOfMonthMillis(): Long = Calendar.getInstance().apply {
        set(year, month, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, 1)
        add(Calendar.MILLISECOND, -1)
    }.timeInMillis

    fun startOfYearMillis(): Long = Calendar.getInstance().apply {
        set(year, Calendar.JANUARY, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Inclusive end-of-year millis. */
    fun endOfYearMillis(): Long = Calendar.getInstance().apply {
        set(year, Calendar.DECEMBER, 31, 23, 59, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    fun contains(timestampMillis: Long): Boolean =
        timestampMillis in startOfMonthMillis()..endOfMonthMillis()

    fun isInSameYear(timestampMillis: Long): Boolean =
        timestampMillis in startOfYearMillis()..endOfYearMillis()

    fun label(): String = MONTH_LABEL_FORMAT.format(Date(startOfMonthMillis()))

    fun yearLabel(): String = year.toString()

    fun previous(): MonthRange = if (month == Calendar.JANUARY) {
        MonthRange(year - 1, Calendar.DECEMBER)
    } else {
        MonthRange(year, month - 1)
    }

    fun next(): MonthRange = if (month == Calendar.DECEMBER) {
        MonthRange(year + 1, Calendar.JANUARY)
    } else {
        MonthRange(year, month + 1)
    }

    /** True if this range cannot move forward past the real current month
     *  (used to disable/hide a "next month" control rather than letting the
     *  user navigate into the future where there's never any data). */
    fun isCurrentOrFuture(): Boolean {
        val now = current()
        return year > now.year || (year == now.year && month >= now.month)
    }

    /** True only for months strictly after the real current month —
     *  distinct from [isCurrentOrFuture], which also includes "now" (used
     *  where the current month itself should remain selectable, e.g. a
     *  month-picker grid, but genuinely future months should not). */
    fun isFuture(): Boolean {
        val now = current()
        return year > now.year || (year == now.year && month > now.month)
    }

    companion object {
        private val MONTH_LABEL_FORMAT = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        fun current(): MonthRange {
            val c = Calendar.getInstance()
            return MonthRange(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
        }

        fun of(timestampMillis: Long): MonthRange {
            val c = Calendar.getInstance().apply { timeInMillis = timestampMillis }
            return MonthRange(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
        }
    }
}