package com.autoexpensetracker.util

import android.content.Context

/**
 * Settings for the weekly summary notification. OFF by default: like the daily
 * check-in it is a recurring notification, so it never starts without the user
 * saying yes (in Settings, or on the one-time card on Home).
 *
 * Deliberately NOT cleared by "Delete all data": it is a preference, not
 * financial data, and with no transactions the summary simply stays silent.
 */
object WeeklyDigestStore {
    private const val PREFS = "weekly_digest_prefs"
    private const val K_ENABLED = "enabled"
    private const val K_LAST_SENT = "last_sent_millis"
    private const val K_INVITE_ANSWERED = "invite_answered"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, enabled).apply()
    }

    fun lastSentMillis(context: Context): Long = prefs(context).getLong(K_LAST_SENT, 0L)

    fun markSent(context: Context, nowMillis: Long) {
        prefs(context).edit().putLong(K_LAST_SENT, nowMillis).apply()
    }

    /** True once the Home card has been accepted or dismissed: it is never shown again. */
    fun isInviteAnswered(context: Context): Boolean = prefs(context).getBoolean(K_INVITE_ANSWERED, false)

    fun setInviteAnswered(context: Context) {
        prefs(context).edit().putBoolean(K_INVITE_ANSWERED, true).apply()
    }
}
