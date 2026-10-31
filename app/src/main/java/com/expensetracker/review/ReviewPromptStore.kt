package com.autoexpensetracker.review

import android.content.Context

/**
 * Decides *whether* to ask for a review — the actual Play In-App Review
 * flow (ReviewHelper) decides *how*. Kept separate so the eligibility
 * policy below is easy to find and tune without touching Play API code.
 *
 * Google's In-App Review API already applies its own client-side quota
 * (roughly a handful of prompts per app per year, regardless of what we
 * do), so this store isn't strictly required to avoid spamming the actual
 * dialog. It exists anyway because:
 *  - calling requestReviewFlow() on literally every app open is wasteful
 *    and against Play's own review guidelines ("don't prompt after every
 *    session"), even if Google silently no-ops most of those calls;
 *  - the *first* prompt should only happen after the app has demonstrably
 *    worked for this user (captured real transactions automatically) —
 *    asking a brand-new user who hasn't seen the core feature work yet is
 *    exactly the kind of premature ask that gets a 1-star "doesn't even
 *    work" review instead of a 5-star one.
 */
object ReviewPromptStore {
    private const val PREFS_NAME = "review_prompt"
    private const val KEY_CAPTURED_COUNT = "captured_count"
    private const val KEY_LAST_PROMPT_MILLIS = "last_prompt_millis"
    private const val KEY_PROMPT_COUNT = "prompt_count"

    // The thresholds (3 auto-captured transactions, 90 days apart, 3 asks
    // ever) live in ReviewPolicy, which has no Android dependencies so they
    // can be tested off-device.

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Call once per successfully auto-captured transaction (see
     *  ParseAndStoreWorker) — never for manual cash entries. */
    fun recordCapturedTransaction(context: Context) {
        val p = prefs(context)
        val count = p.getInt(KEY_CAPTURED_COUNT, 0) + 1
        p.edit().putInt(KEY_CAPTURED_COUNT, count).apply()
    }

    /** Auto-captured transactions counted so far (shown on the capture-status screen). */
    fun capturedCount(context: Context): Int = prefs(context).getInt(KEY_CAPTURED_COUNT, 0)

    fun isEligibleForPrompt(context: Context): Boolean {
        val p = prefs(context)
        return ReviewPolicy.isEligible(
            capturedCount = p.getInt(KEY_CAPTURED_COUNT, 0),
            promptCount = p.getInt(KEY_PROMPT_COUNT, 0),
            lastPromptMillis = p.getLong(KEY_LAST_PROMPT_MILLIS, 0L),
            nowMillis = System.currentTimeMillis()
        )
    }

    /** Call after launching (or attempting to launch) the review flow,
     *  regardless of whether Google actually displayed it — we can't
     *  observe that, and re-attempting immediately would defeat the
     *  cooldown. */
    fun recordPromptShown(context: Context) {
        val p = prefs(context)
        p.edit()
            .putLong(KEY_LAST_PROMPT_MILLIS, System.currentTimeMillis())
            .putInt(KEY_PROMPT_COUNT, p.getInt(KEY_PROMPT_COUNT, 0) + 1)
            .apply()
    }
}