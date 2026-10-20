package com.autoexpensetracker.pdf

import java.util.Calendar

/**
 * Who may export which report. A day or a month inside the current and
 * previous two calendar months is free; anything older, and every full-year
 * report, is Pro. "Current + previous two" means three calendar months, not
 * 90 days, so on 1 November, October, September and August are still free.
 */
object PdfAccessPolicy {

    const val FREE_MONTHS = 3

    fun isFree(period: PdfPeriod, nowMillis: Long): Boolean {
        val year: Int
        val month: Int
        when (period) {
            is PdfPeriod.Year -> return false
            is PdfPeriod.Month -> { year = period.year; month = period.month }
            is PdfPeriod.Day -> { year = period.year; month = period.month }
        }
        val now = Calendar.getInstance()
        now.timeInMillis = nowMillis
        val monthsAgo = (now.get(Calendar.YEAR) - year) * 12 + (now.get(Calendar.MONTH) - month)
        // A future period is empty anyway, so it is never worth gating.
        return monthsAgo < FREE_MONTHS
    }
}
