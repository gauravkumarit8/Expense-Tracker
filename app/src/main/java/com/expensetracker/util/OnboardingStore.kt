package com.autoexpensetracker.util

import android.content.Context

/**
 * Tracks whether the user has been through the first-launch onboarding
 * flow (`OnboardingScreen` in MainActivity.kt) — the disclosure screens
 * explaining what the app reads from notifications and why, shown before
 * requesting Notification Access. Plain SharedPreferences, matching the
 * existing pattern for simple one-off UI state (see
 * `DismissedSuggestionsStore`, `SummaryPeriodStore`).
 *
 * Deliberately tracks "has seen the explanation," not "has granted
 * notification access" — a user can complete onboarding and still decline
 * the permission (see the "Skip for now" path in `OnboardingScreen`), and
 * shouldn't be forced back through the full explainer every time they
 * reopen the app just because they haven't granted it yet. The existing
 * `OnboardingBanner` on the Transactions screen already handles reminding
 * them post-onboarding.
 */
object OnboardingStore {
    private const val PREFS_NAME = "onboarding"
    private const val KEY_COMPLETED = "completed"

    fun hasCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context, completed: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, completed)
            .apply()
    }
}