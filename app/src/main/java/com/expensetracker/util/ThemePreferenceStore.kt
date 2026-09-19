package com.autoexpensetracker.util

import android.content.Context

/**
 * Manual theme override. Previously the app only ever followed the
 * system's dark/light setting (`isSystemInDarkTheme()` was the sole
 * input to `ExpenseTrackerTheme`) — there was no in-app toggle at all,
 * which is what prompted this: it's not that the toggle was hard to
 * find, it genuinely didn't exist yet.
 *
 * SYSTEM remains the default (matches prior behavior exactly for anyone
 * who never touches this setting). AMOLED is a distinct mode from DARK,
 * not a sub-toggle of it — pure black backgrounds/surfaces instead of the
 * brand's warmed dark-green tone, specifically for saving battery on
 * OLED screens, which only makes sense as dark-family variant, never as
 * a light-mode option.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

object ThemePreferenceStore {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "mode"

    fun get(context: Context): ThemeMode {
        val stored = prefs(context).getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
        return try {
            ThemeMode.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun set(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
