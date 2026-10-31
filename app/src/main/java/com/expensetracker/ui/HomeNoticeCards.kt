package com.autoexpensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoexpensetracker.ui.theme.MutedText

/**
 * Shown on Home once the app has captured something, offering the weekly
 * summary notification. One-time: answering either way (see
 * WeeklyDigestStore.setInviteAnswered) means it never comes back. Nothing is
 * turned on without the tap.
 */
@Composable
internal fun WeeklySummaryInviteCard(onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Get a weekly summary?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Every Sunday evening: how much you spent, where most of it went, and how that compares with the week before. " +
                    "One notification a week. You can turn it off any time in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNotNow) { Text("Not now") }
                Button(onClick = onTurnOn) { Text("Turn on") }
            }
        }
    }
}

/**
 * Shown when auto-capture was working and has stopped (notification access is
 * off). Without it a user whose phone reset the permission just sees the
 * numbers stop moving and concludes the app is broken.
 */
@Composable
internal fun CaptureOffCard(onTurnOn: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Auto-capture is off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "New bank and UPI payments are not being tracked right now, so your totals may be missing recent spending. " +
                    "Turn notification access back on to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onTurnOn) { Text("Turn on") }
            }
        }
    }
}
