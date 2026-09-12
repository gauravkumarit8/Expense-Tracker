package com.autoexpensetracker.review

import android.app.Activity
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
    fun maybeRequestReview(activity: Activity) {
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) return@addOnCompleteListener
            val reviewInfo = request.result
            manager.launchReviewFlow(activity, reviewInfo)
            ReviewPromptStore.recordPromptShown(activity)
        }
    }
}