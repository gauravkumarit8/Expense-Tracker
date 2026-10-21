package com.autoexpensetracker.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Offered to a free user who taps a Pro action that has a rewarded-ad
 * alternative (CSV export / import). Watching is always their choice;
 * tapping outside the dialog cancels.
 */
@Composable
internal fun RewardedUnlockDialog(
    featureName: String,
    spentThisMonth: Boolean,
    adReady: Boolean,
    onWatchAd: () -> Unit,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit
) {
    if (spentThisMonth) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Free use already taken") },
            text = {
                Text(
                    "You've already used this month's free use of a Pro feature. It comes back on the 1st. " +
                        "Upgrade to Pro to use $featureName whenever you like."
                )
            },
            confirmButton = { TextButton(onClick = onUpgrade) { Text("Upgrade to Pro") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("$featureName is a Pro feature") },
            text = {
                Text(
                    "You can use it once this month for free: watch one short ad and it unlocks right after. " +
                        "How long the ad runs is up to the advertiser."
                )
            },
            confirmButton = {
                TextButton(onClick = onWatchAd, enabled = adReady) {
                    Text(if (adReady) "Watch ad" else "Loading ad...")
                }
            },
            dismissButton = { TextButton(onClick = onUpgrade) { Text("Upgrade to Pro") } }
        )
    }
}

/**
 * The once-a-day, entirely optional offer shown after Search has been
 * opened several times: one ad for a banner-free day. Declining costs
 * nothing and is not followed up until tomorrow.
 */
@Composable
internal fun SearchAdPromptDialog(adReady: Boolean, onWatchAd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No ads for today?") },
        text = {
            Text(
                "You've used Search a lot today. Watch one short ad and the banner and sponsored cards are hidden until tomorrow. " +
                    "It's optional, and you'll only be asked once a day. How long the ad runs is up to the advertiser."
            )
        },
        confirmButton = {
            TextButton(onClick = onWatchAd, enabled = adReady) {
                Text(if (adReady) "Watch ad" else "Loading ad...")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}
