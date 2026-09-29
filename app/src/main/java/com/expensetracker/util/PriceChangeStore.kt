package com.autoexpensetracker.util

import android.content.Context

/**
 * Which price-change alerts (see [SubscriptionPriceChangeDetector]) the
 * user has already dismissed, or already been notified about, so the same
 * detected jump doesn't reappear every time RemindersScreen is opened, or
 * fire a duplicate notification. Keyed by merchant + the new amount, so a
 * FURTHER price change for the same merchant later still surfaces — this
 * is intentionally not "dismiss this merchant forever."
 */
object PriceChangeStore {
    private const val PREFS_NAME = "price_change_alerts"
    private const val KEY_HANDLED = "handled"     // gates the one-time notification only
    private const val KEY_DISMISSED = "dismissed" // gates visibility in the RemindersScreen list

    private fun key(merchant: String, newAmount: Double) =
        "${merchant.trim().lowercase()}|${"%.2f".format(newAmount)}"

    fun isHandled(context: Context, merchant: String, newAmount: Double): Boolean =
        getSet(context, KEY_HANDLED).contains(key(merchant, newAmount))

    fun markHandled(context: Context, merchant: String, newAmount: Double) {
        addToSet(context, KEY_HANDLED, key(merchant, newAmount))
    }

    // Deliberately separate from "handled": a user who never granted
    // notification permission, or missed the notification, should still be
    // able to see and act on the alert in-app. Dismissing it in the UI
    // does NOT suppress the (already possibly-fired) notification and vice
    // versa — the two answer different questions ("was this pushed to the
    // user" vs "does the user still want to see this on screen").
    fun isDismissed(context: Context, merchant: String, newAmount: Double): Boolean =
        getSet(context, KEY_DISMISSED).contains(key(merchant, newAmount))

    fun dismiss(context: Context, merchant: String, newAmount: Double) {
        addToSet(context, KEY_DISMISSED, key(merchant, newAmount))
    }

    private fun getSet(context: Context, prefKey: String): Set<String> =
        prefs(context).getStringSet(prefKey, emptySet()) ?: emptySet()

    private fun addToSet(context: Context, prefKey: String, value: String) {
        val current = getSet(context, prefKey).toMutableSet()
        current.add(value)
        prefs(context).edit().putStringSet(prefKey, current).apply()
    }

    /** Wipes every recorded alert (used by "Delete all data"). */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
