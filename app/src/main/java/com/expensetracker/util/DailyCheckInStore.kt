package com.autoexpensetracker.util

import android.content.Context

/**
 * Whether the daily check-in nudge (a generic "review today's spending"
 * notification, distinct from the due-date-specific bill/subscription
 * reminders in ReminderCheckWorker) is enabled. Defaults to OFF —
 * unlike bill reminders, which the user explicitly creates one at a time,
 * this is a recurring daily notification with no natural per-item opt-in
 * moment, so it should never start firing without the user having
 * actively turned it on in Settings.
 */
object DailyCheckInStore {
    private const val PREFS_NAME = "daily_checkin_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
