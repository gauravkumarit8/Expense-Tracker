package com.autoexpensetracker.util

import android.content.Context

/**
 * Whether the "Account balances" section on ChartsScreen shows real
 * figures or a masked placeholder. Defaults to **hidden** — the balance
 * feature (ยง2.11) parses and displays a real bank/account balance
 * whenever a captured message happens to include one, which is more
 * sensitive than transaction amounts alone (a single number that
 * summarizes someone's total available money, rather than one purchase).
 * Masked-by-default means a shoulder-surfing glance at the Charts tab
 * doesn't expose it without a deliberate tap to reveal.
 *
 * Plain SharedPreferences, matching the existing pattern for simple
 * one-off UI preferences (see `SummaryPeriodStore`,
 * `DismissedSuggestionsStore`).
 */
object BalanceVisibilityStore {
    private const val PREFS_NAME = "balance_visibility"
    private const val KEY_VISIBLE = "visible"

    fun isVisible(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VISIBLE, false)

    fun setVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VISIBLE, visible)
            .apply()
    }
}