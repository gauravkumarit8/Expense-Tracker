package com.autoexpensetracker.util

import android.content.Context

/**
 * Tracks which (category, year-month) pairs have already fired a budget
 * warning notification, so [BudgetWarningDetector] notifies at most once
 * per category per month instead of on every single transaction after the
 * warning threshold is first crossed. Deliberately plain SharedPreferences,
 * matching the existing lightweight-store pattern in this package
 * (`ManualBalanceStore`, `DismissedSuggestionsStore`) — this is a small set
 * of flags, not data needing queries or migrations.
 */
object BudgetWarningStore {
    private const val PREFS_NAME = "budget_warnings"

    private fun key(category: String, yearMonth: String) = "$category|$yearMonth"

    fun alreadyWarned(context: Context, category: String, yearMonth: String): Boolean =
        prefs(context).getBoolean(key(category, yearMonth), false)

    fun markWarned(context: Context, category: String, yearMonth: String) {
        prefs(context).edit().putBoolean(key(category, yearMonth), true).apply()
    }

    /** Wipes every recorded warning (used by "Delete all data"). */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
