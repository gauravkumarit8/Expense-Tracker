package com.autoexpensetracker.util

import android.content.Context

/**
 * Small persisted set of merchant keys (trimmed, lowercased) the user has
 * dismissed as recurring-transaction suggestions. Deliberately stored in
 * plain SharedPreferences rather than adding a Room table/migration for
 * something this minor — merchant names here are already visible
 * elsewhere in the unencrypted UI, so this isn't a new privacy exposure.
 */
object DismissedSuggestionsStore {
    private const val PREFS_NAME = "dismissed_suggestions"
    private const val KEY_SET = "dismissed_merchants"

    fun getAll(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SET, emptySet()) ?: emptySet()

    fun dismiss(context: Context, merchantKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_SET, emptySet()) ?: emptySet()).toMutableSet()
        current.add(merchantKey)
        prefs.edit().putStringSet(KEY_SET, current).apply()
    }
}