package com.autoexpensetracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoexpensetracker.ui.theme.BrandGreen
import com.autoexpensetracker.ui.theme.MutedText

/**
 * What a brand-new user sees on Home until the first transaction arrives.
 *
 * The app only reads alerts that arrive AFTER it is set up, so a new user
 * otherwise meets an empty screen and no hint of when or whether anything
 * will happen. This says: whether auto-capture is on, what to expect and
 * how soon, that older history is not read (and how to bring it in), and
 * where to look if nothing shows up. The example row is plainly labelled
 * and is drawn here only - nothing is written to the database, so it can
 * never leak into totals, exports or backups.
 */
@Composable
internal fun FirstRunCard(
    notificationAccessGranted: Boolean,
    onEnableNotificationAccess: () -> Unit,
    onImportHistory: () -> Unit,
    onAddTransaction: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                if (notificationAccessGranted) "Waiting for your first alert" else "One step left to start tracking",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (notificationAccessGranted) {
                    "Auto-capture is on. Your next bank or UPI alert will appear here within seconds."
                } else {
                    "Turn on Notification Access so the app can read your bank and UPI alerts. It's the only permission it needs, and you control it in Android settings."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )

            Spacer(modifier = Modifier.height(16.dp))
            StepRow(
                done = notificationAccessGranted,
                title = "Notification access: ${if (notificationAccessGranted) "on" else "off"}",
                detail = null
            )
            StepRow(
                done = false,
                title = "Make a UPI payment, or wait for a bank alert",
                detail = "It shows up here by itself. Nothing to type."
            )
            StepRow(
                done = false,
                title = "Tracking starts from today",
                detail = "Past transactions aren't read. You can bring in older history from a bank statement CSV."
            )

            if (!notificationAccessGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onEnableNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn on notification access")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("What you'll see", style = MaterialTheme.typography.labelLarge, color = MutedText)
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Example, not your data", style = MaterialTheme.typography.labelSmall, color = MutedText)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Swiggy", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Food & Dining", style = MaterialTheme.typography.bodySmall, color = MutedText)
                        }
                        Text("-₹340.00", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Each one is sorted by category, shown in charts, and checked against the budgets you set.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onImportHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Import older history (bank CSV)")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAddTransaction) { Text("Add one yourself") }
                TextButton(onClick = onOpenSettings) { Text("Alerts not showing up?") }
            }
        }
    }
}

@Composable
private fun StepRow(done: Boolean, title: String, detail: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done yet",
            tint = if (done) BrandGreen else MutedText,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
    }
}
