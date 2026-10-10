package com.autoexpensetracker.util

import android.content.Context

/**
 * Which view the history screen (`MonthlyHistoryScreen`) shows: one DAY, one
 * MONTH, or a YEAR broken down month by month. Originally a two-way
 * Month/Year toggle for the totals card; DAY was added with the Day/Month/Year
 * selector. The stored strings "MONTH" and "YEAR" from that earlier toggle are
 * still valid, so existing users' saved choice carries over (a saved "YEAR"
 * now opens the Jan-Dec table instead of year totals over a month list).
 */
enum class SummaryPeriod { DAY, MONTH, YEAR }

/**
 * Persists the user's Month-vs-Year summary preference on
 * `MonthlyHistoryScreen` (REQUIREMENTS.md ยง2.19). Stored in plain
 * SharedPreferences rather than a new Room column/table, matching the
 * existing pattern for single, low-stakes UI preferences (see
 * `DismissedSuggestionsStore` / Decision Log 2026-08-18).
 */
object SummaryPeriodStore {
    private const val PREFS_NAME = "summary_period"
    private const val KEY_PERIOD = "period"

    fun get(context: Context): SummaryPeriod {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PERIOD, SummaryPeriod.MONTH.name)
        return runCatching { SummaryPeriod.valueOf(stored ?: SummaryPeriod.MONTH.name) }
            .getOrDefault(SummaryPeriod.MONTH)
    }

    fun set(context: Context, period: SummaryPeriod) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERIOD, period.name)
            .apply()
    }
}