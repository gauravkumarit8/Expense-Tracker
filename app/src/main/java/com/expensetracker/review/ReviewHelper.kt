package com.autoexpensetracker.review

import android.app.Activity
import android.util.Log
import com.autoexpensetracker.BuildConfig
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Thin wrapper around Play Core's In-App Review flow. Whether a user
 * actually sees the review dialog is entirely up to Google's own quota —
 * this class has no visibility into that and doesn't try to guess. See
 * ReviewPromptStore for the "should we even ask" policy this is paired
 * with.
 */
object ReviewHelper {

    /**
     * Requests and, if Google grants it, launches the review flow.
     * Safe to call speculatively — request/launch failures (network,
     * quota, Play Store not installed, etc.) are swallowed rather than
     * surfaced, since a failed review prompt should never interrupt or be
     * visible to the user in any way.
     */
    @Volatile
    private var attemptedThisProcess = false

    /**
     * What the UI should call whenever the app is in the foreground and
     * usable. Does nothing unless the policy says it's time, and tries at most
     * once per app process: if Google's request fails (for example on a
     * sideloaded build) we must not retry on every resume. The persisted
     * cooldown only starts counting once a request SUCCEEDS, so without this
     * guard a failing request would be repeated each time the app came forward.
     */
    fun requestIfEligible(activity: Activity) {
        if (attemptedThisProcess) return
        if (!ReviewPromptStore.isEligibleForPrompt(activity)) return
        attemptedThisProcess = true
        maybeRequestReview(activity)
    }

    fun maybeRequestReview(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                // Swallowed for the user, but NOT silent for the developer any more.
                // The most common cause while testing: the In-App Review API only
                // works for a build installed from Google Play (including the
                // internal/closed testing tracks). A sideloaded or adb-installed
                // APK gets nothing here and shows no dialog, with no other symptom.
                if (BuildConfig.DEBUG) Log.w(TAG, "In-App Review unavailable: ${request.exception}")
                return@addOnCompleteListener
            }
            if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            if (BuildConfig.DEBUG) Log.d(TAG, "In-App Review requested OK; launching (Google's own quota decides whether it is shown)")
            manager.launchReviewFlow(activity, request.result)
            ReviewPromptStore.recordPromptShown(activity)
        }
    }

    private const val TAG = "ReviewHelper"
}
