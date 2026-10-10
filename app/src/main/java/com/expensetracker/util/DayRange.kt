package com.autoexpensetracker.util

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A single calendar day, the Day-view counterpart of [MonthRange]. Built on
 * `java.util.Calendar` for the same reason (minSdk 24, no java.time
 * desugaring) and [Serializable] for the same reason (so it survives
 * `rememberSaveable` across rotation / process death).
 *
 * Stored as year/month/day components, not as a millisecond instant, so a
 * "day" is unambiguous and trivially comparable. [month] is zero-based,
 * matching `Calendar` and [MonthRange].
 */
data class DayRange(val year: Int, val month: Int, val day: Int) : Serializable {

    fun startMillis(): Long = Calendar.getInstance().apply {
        set(year, month, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Inclusive end of day: the last millisecond before the NEXT local
     * midnight. Computed by adding a calendar day rather than 24 hours, so a
     * daylight-saving day (23 or 25 hours) still ends at the right moment.
     */
    fun endMillis(): Long = Calendar.getInstance().apply {
        set(year, month, day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MILLISECOND, -1)
    }.timeInMillis

    fun contains(timestampMillis: Long): Boolean =
        timestampMillis >= startMillis() && timestampMillis <= endMillis()

    fun previous(): DayRange = shifted(-1)

    fun next(): DayRange = shifted(1)

    // Shifts from NOON, not midnight, so a DST transition at midnight can't
    // push the result onto the wrong date.
    private fun shifted(deltaDays: Int): DayRange {
        val c = Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            add(Calendar.DAY_OF_MONTH, deltaDays)
        }
        return DayRange(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    // yyyyMMdd as an Int: orders correctly and avoids comparing millis across
    // time zones / DST.
    private fun orderKey(): Int = year * 10000 + (month + 1) * 100 + day

    fun isToday(): Boolean = orderKey() == today().orderKey()

    /** True for today and any later day — used to disable "next day". */
    fun isCurrentOrFuture(): Boolean = orderKey() >= today().orderKey()

    fun isFuture(): Boolean = orderKey() > today().orderKey()

    // Built per call, not cached: a SimpleDateFormat captures the default time
    // zone when it is created, so a long-lived one would show the wrong day
    // after the device's zone changes (travel, or a manual change) while the
    // process is still alive. A label is formatted rarely enough that this is free.
    fun label(): String = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(startMillis()))

    /**
     * Material3's DatePicker speaks in UTC-midnight milliseconds for the
     * calendar date the user picked, NOT local midnight. Reading that value
     * with the device's local zone shifts the date by a day in any zone west
     * of UTC (the picked day becomes the previous evening locally). These two
     * functions convert through UTC so the picked calendar date round-trips
     * exactly, in every time zone.
     */
    fun toUtcPickerMillis(): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day)
    }.timeInMillis

    companion object {
        fun today(): DayRange = of(System.currentTimeMillis())

        fun of(timestampMillis: Long): DayRange {
            val c = Calendar.getInstance().apply { timeInMillis = timestampMillis }
            return DayRange(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }

        fun fromUtcPickerMillis(utcMillis: Long): DayRange {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
            return DayRange(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }
    }
}
