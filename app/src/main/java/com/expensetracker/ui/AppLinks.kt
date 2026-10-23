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
    const val PRIVACY_POLICY_URL = "https://grvthakur.bid/expense-tracker/privacy-policy.html"

    /** The support address published in the privacy policy. */
    const val SUPPORT_EMAIL = "gaurav954624@gmail.com"

    /** Opens the user's mail app with a new message to support. Nothing is sent from the app itself. */
    fun contactSupport(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "Auto Expense Tracker - feedback")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            // No mail app installed.
        }
    }

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
