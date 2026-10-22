package com.autoexpensetracker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Rate" and "Share" actions. They use the running app's own package name, so
 * on the Play Store build they point at the live listing.
 *
 * Rate goes to the Play Store listing rather than the in-app review pop-up on
 * purpose: Google rations that pop-up (and it never appears on a sideloaded
 * build), so it can't be a user-initiated "Rate this app" button. This always works.
 */
internal object AppLinks {

    fun rate(context: Context) {
        val packageName = context.packageName
        val inStore = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(inStore)
        } catch (e: ActivityNotFoundException) {
            // No Play Store app (rare): fall back to the web listing.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: ActivityNotFoundException) {
                // No browser either; nothing sensible left to do.
            }
        }
    }

    /** The public privacy policy page (same one linked from the Play listing). */
    const val PRIVACY_POLICY_URL = "https://gauravkumarit8.github.io/privacy-policy.html"

    fun privacyPolicy(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: ActivityNotFoundException) {
            // No browser on the phone; nothing sensible left to do.
        }
    }

    fun share(context: Context) {
        val link = "https://play.google.com/store/apps/details?id=${context.packageName}"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Expense Tracker")
            putExtra(
                Intent.EXTRA_TEXT,
                "I track my bank and UPI spending automatically with Expense Tracker. " +
                    "It never needs SMS access or a bank login, and everything stays on the phone.\n$link"
            )
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share Expense Tracker").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: ActivityNotFoundException) {
            // Nothing on the phone can handle a share; nothing sensible left to do.
        }
    }
}
