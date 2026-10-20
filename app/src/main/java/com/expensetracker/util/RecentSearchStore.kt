package com.autoexpensetracker.util

import android.content.Context

/**
 * The last few things the user searched for, kept on this device only in
 * plain SharedPreferences. They are typed merchant names and notes, so
 * "Delete all data" clears them (see MainActivity) alongside the other
 * preference-backed stores. Not part of the backup file: it is a
 * convenience, not data.
 */
object RecentSearchStore {
    private const val PREFS_NAME = "recent_searches"
    private const val KEY_ITEMS = "items"

    fun getAll(context: Context): List<String> =
        SearchLogic.decodeRecent(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
        )

    /** Records [query] and returns the updated list. */
    fun add(context: Context, query: String): List<String> =
        save(context, SearchLogic.addRecent(getAll(context), query))

    fun remove(context: Context, query: String): List<String> =
        save(context, SearchLogic.removeRecent(getAll(context), query))

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun save(context: Context, items: List<String>): List<String> {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, SearchLogic.encodeRecent(items)).apply()
        return items
    }
}
