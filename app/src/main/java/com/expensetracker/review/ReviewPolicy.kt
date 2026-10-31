package com.autoexpensetracker.review

/**
 * When the app may ask for a Play Store review. The decision only — no storage
 * and no Android types — so the thresholds can be tested off-device;
 * [ReviewPromptStore] feeds it the persisted counters.
 *
 *  - at least [MIN_CAPTURED_TRANSACTIONS] auto-captured transactions, i.e. the
 *    user has actually seen the app do its job;
 *  - never more than [MAX_PROMPTS_EVER] attempts in total;
 *  - at least [MIN_INTERVAL_DAYS] between attempts.
 */
object ReviewPolicy {
    const val MIN_CAPTURED_TRANSACTIONS = 3
    const val MAX_PROMPTS_EVER = 3
    const val MIN_INTERVAL_DAYS = 90L

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    fun isEligible(capturedCount: Int, promptCount: Int, lastPromptMillis: Long, nowMillis: Long): Boolean {
        if (capturedCount < MIN_CAPTURED_TRANSACTIONS) return false
        if (promptCount >= MAX_PROMPTS_EVER) return false
        if (lastPromptMillis != 0L && nowMillis - lastPromptMillis < MIN_INTERVAL_DAYS * MILLIS_PER_DAY) return false
        return true
    }
}
