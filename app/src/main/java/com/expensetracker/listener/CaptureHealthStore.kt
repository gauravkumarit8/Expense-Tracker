package com.autoexpensetracker.listener

import android.content.Context

/**
 * Four timestamps that answer "is capture actually working on this phone?".
 * No message content, amounts or names are stored — only when things last
 * happened — so unlike the app's other side stores this holds nothing
 * personal, and it is deliberately left out of backup and "Delete all data".
 *
 *  connectedAt     the system last bound the notification listener
 *  disconnectedAt  the system last unbound it (battery managers do this)
 *  lastCandidateAt the last notification that passed the bank/SMS filter and had text
 *  lastCapturedAt  the last time a transaction was actually saved
 *
 * [CaptureDiagnosis] turns these into a plain-language verdict.
 */
object CaptureHealthStore {
    private const val PREFS_NAME = "capture_health"
    private const val KEY_CONNECTED = "connected_at"
    private const val KEY_DISCONNECTED = "disconnected_at"
    private const val KEY_CANDIDATE = "last_candidate_at"
    private const val KEY_CAPTURED = "last_captured_at"

    data class Snapshot(val connectedAt: Long, val disconnectedAt: Long, val lastCandidateAt: Long, val lastCapturedAt: Long)

    fun recordConnected(context: Context) = stamp(context, KEY_CONNECTED)
    fun recordDisconnected(context: Context) = stamp(context, KEY_DISCONNECTED)
    fun recordCandidate(context: Context) = stamp(context, KEY_CANDIDATE)
    fun recordCaptured(context: Context) = stamp(context, KEY_CAPTURED)

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(p.getLong(KEY_CONNECTED, 0L), p.getLong(KEY_DISCONNECTED, 0L), p.getLong(KEY_CANDIDATE, 0L), p.getLong(KEY_CAPTURED, 0L))
    }

    private fun stamp(context: Context, key: String) {
        prefs(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
