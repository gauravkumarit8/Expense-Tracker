@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.autoexpensetracker.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.autoexpensetracker.backup.BackupPayload
import com.autoexpensetracker.backup.BackupSerializer
import com.autoexpensetracker.data.AppDatabase
import com.autoexpensetracker.data.Budget
import com.autoexpensetracker.data.BudgetDao
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Reminder
import com.autoexpensetracker.data.ReminderDao
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.data.TransactionDao
import com.autoexpensetracker.util.NotificationAccessHelper
import com.autoexpensetracker.util.OnboardingStore
import com.autoexpensetracker.util.BatteryOptimizationHelper
import com.autoexpensetracker.util.AppLockManager
import com.autoexpensetracker.util.BiometricAuthHelper
import com.autoexpensetracker.util.DismissedSuggestionsStore
import com.autoexpensetracker.util.RecurringDetector
import com.autoexpensetracker.util.RecurringSuggestion
import com.autoexpensetracker.util.MonthRange
import com.autoexpensetracker.util.SummaryPeriod
import com.autoexpensetracker.util.SummaryPeriodStore
import com.autoexpensetracker.ads.BannerAdView
import com.autoexpensetracker.ads.ConsentManager
import com.autoexpensetracker.billing.BillingManager
import com.autoexpensetracker.update.AppUpdateHelper
import com.autoexpensetracker.review.ReviewHelper
import com.autoexpensetracker.review.ReviewPromptStore
import com.autoexpensetracker.ui.theme.ExpenseTrackerTheme
import com.autoexpensetracker.ui.theme.BrandGreen
import com.autoexpensetracker.ui.theme.SemanticRed
import com.autoexpensetracker.ui.theme.SemanticOrange
import com.autoexpensetracker.ui.theme.SemanticGray
import com.autoexpensetracker.ui.theme.SemanticIndigo
import com.autoexpensetracker.ui.theme.SemanticBrown
import com.autoexpensetracker.ui.theme.SemanticGold
import com.autoexpensetracker.util.BalanceVisibilityStore
import com.autoexpensetracker.util.ThemeMode
import com.autoexpensetracker.util.ThemePreferenceStore
import com.autoexpensetracker.util.DailyCheckInStore
import com.autoexpensetracker.worker.DailyCheckInScheduler
import com.autoexpensetracker.util.formatInr
import com.autoexpensetracker.util.formatInrWhole
import com.autoexpensetracker.util.ManualBalanceStore
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

// ---------- REMINDERS SCREEN ----------

@Composable
internal fun RemindersScreen(reminderDao: ReminderDao, allTransactions: List<Transaction>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reminders by reminderDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var dismissedVersion by remember { mutableStateOf(0) } // bump to force suggestion recompute after a dismiss

    val suggestions = remember(allTransactions, reminders, dismissedVersion) {
        val dismissed = DismissedSuggestionsStore.getAll(context)
        RecurringDetector.detect(allTransactions, reminders, dismissed)
    }

    if (reminders.isEmpty() && suggestions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(48.dp), tint = SemanticGray)
                Spacer(modifier = Modifier.height(12.dp))
                Text("No reminders yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Tap + to add a bill or subscription reminder.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    } else {
        val confirmedTotal = remember(reminders) { reminders.mapNotNull { it.amount }.sum() }
        val detectedTotal = remember(suggestions) { suggestions.sumOf { it.averageAmount } }

        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)) {
            if (confirmedTotal > 0 || detectedTotal > 0) {
                item {
                    MonthlyRecurringSpendCard(confirmedTotal = confirmedTotal, detectedTotal = detectedTotal)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            if (suggestions.isNotEmpty()) {
                item {
                    Text("Suggested (recurring spending detected)", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(suggestions) { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        onAccept = {
                            scope.launch {
                                reminderDao.insert(
                                    Reminder(
                                        title = suggestion.merchant,
                                        amount = suggestion.averageAmount,
                                        dueDayOfMonth = suggestion.suggestedDueDay
                                    )
                                )
                            }
                        },
                        onDismiss = {
                            DismissedSuggestionsStore.dismiss(context, suggestion.merchant.trim().lowercase())
                            dismissedVersion++
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                if (reminders.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Your reminders", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            items(reminders) { reminder -> ReminderRow(reminder, onDelete = { scope.launch { reminderDao.delete(reminder.id) } }) }
        }
    }
}

@Composable
private fun MonthlyRecurringSpendCard(confirmedTotal: Double, detectedTotal: Double) {
    val total = confirmedTotal + detectedTotal
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Monthly recurring spend",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${formatInr(total)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (detectedTotal > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${formatInr(confirmedTotal)} tracked + ${formatInr(detectedTotal)} detected but not yet added below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Across all reminders with a known amount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: RecurringSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFFEDE7F6)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(suggestion.merchant, fontWeight = FontWeight.Medium)
            Text(
                "~${formatInr(suggestion.averageAmount)} around day ${suggestion.suggestedDueDay} • seen ${suggestion.occurrenceCount} times",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Add reminder") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ReminderRow(reminder: Reminder, onDelete: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = Color.White) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE3F2FD)), contentAlignment = Alignment.Center) {
                Text("${reminder.dueDayOfMonth}", fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(reminder.title, fontWeight = FontWeight.Medium)
                Text(
                    "Due day ${reminder.dueDayOfMonth} of month" + (reminder.amount?.let { " • ${formatInr(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
                if (!reminder.notes.isNullOrBlank()) Text(reminder.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete reminder", tint = SemanticGray) }
        }
    }
}

@Composable
internal fun AddReminderDialog(onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("1") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reminder") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (e.g. Netflix, Electricity)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = dueDay,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        val n = digits.toIntOrNull()
                        dueDay = when { digits.isEmpty() -> ""; n != null && n in 1..31 -> digits; else -> dueDay }
                    },
                    label = { Text("Due day of month (1-31)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val day = dueDay.toIntOrNull() ?: return@TextButton
                    if (title.isBlank() || day !in 1..31) return@TextButton
                    onSave(Reminder(title = title.trim(), amount = amount.toDoubleOrNull(), dueDayOfMonth = day, notes = notes.trim().takeIf { it.isNotBlank() }))
                },
                enabled = title.isNotBlank() && (dueDay.toIntOrNull()?.let { it in 1..31 } == true)
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
