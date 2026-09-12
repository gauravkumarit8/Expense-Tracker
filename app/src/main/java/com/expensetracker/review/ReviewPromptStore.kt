package com.autoexpensetracker.review

import android.content.Context
import java.util.concurrent.TimeUnit

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

    // First ask only after this many transactions have been captured
    // *automatically* (manual cash entries don't count — they're not
    // evidence the core notification-capture feature actually worked).
    private const val MIN_CAPTURED_TRANSACTIONS = 10

    // Don't ask again for 90 days after a prompt, and cap it at 3 asks
    // ever, regardless of how long someone keeps using the app.
    private val MIN_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(90)
    private const val MAX_PROMPTS_EVER = 3

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Call once per successfully auto-captured transaction (see
     *  ParseAndStoreWorker) — never for manual cash entries. */
    fun recordCapturedTransaction(context: Context) {
        val p = prefs(context)
        val count = p.getInt(KEY_CAPTURED_COUNT, 0) + 1
        p.edit().putInt(KEY_CAPTURED_COUNT, count).apply()
    }

    fun isEligibleForPrompt(context: Context): Boolean {
        val p = prefs(context)
        val capturedCount = p.getInt(KEY_CAPTURED_COUNT, 0)
        val promptCount = p.getInt(KEY_PROMPT_COUNT, 0)
        val lastPrompt = p.getLong(KEY_LAST_PROMPT_MILLIS, 0L)

        if (capturedCount < MIN_CAPTURED_TRANSACTIONS) return false
        if (promptCount >= MAX_PROMPTS_EVER) return false
        if (lastPrompt != 0L && System.currentTimeMillis() - lastPrompt < MIN_INTERVAL_MILLIS) return false
        return true
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