package com.autoexpensetracker.util

import android.content.Context

/** Whether `MonthlyHistoryScreen`'s totals are scoped to the selected month
 *  or to that month's containing calendar year. */
enum class SummaryPeriod { MONTH, YEAR }

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