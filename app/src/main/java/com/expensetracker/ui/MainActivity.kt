@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.expensetracker.data.*
import com.expensetracker.util.NotificationAccessHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private enum class DirectionFilter(val label: String) {
    ALL("All"), SENT("Sent"), RECEIVED("Received"), NEEDS_REVIEW("Needs review")
}

private enum class DateFilter(val label: String) {
    TODAY("Today"), THIS_WEEK("This week"), THIS_MONTH("This month"), ALL_TIME("All time")
}

private enum class Screen { TRANSACTIONS, REMINDERS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(applicationContext)
        val transactionDao = db.transactionDao()
        val reminderDao = db.reminderDao()

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var notificationAccessGranted by remember {
                    mutableStateOf(NotificationAccessHelper.isEnabled(context))
                }
                LifecycleStartEffect(Unit) {
                    notificationAccessGranted = NotificationAccessHelper.isEnabled(context)
                    onStopOrDispose { }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* no-op — reminders still work, just silently, if denied */ }

                var screen by rememberSaveable { mutableStateOf(Screen.TRANSACTIONS) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (screen == Screen.TRANSACTIONS) "Expense Tracker" else "Reminders") },
                            actions = {
                                IconButton(onClick = {
                                    screen = if (screen == Screen.TRANSACTIONS) Screen.REMINDERS else Screen.TRANSACTIONS
                                    if (screen == Screen.REMINDERS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }) {
                                    Icon(
                                        if (screen == Screen.TRANSACTIONS) Icons.Filled.NotificationsActive else Icons.Filled.List,
                                        contentDescription = if (screen == Screen.TRANSACTIONS) "Reminders" else "Transactions"
                                    )
                                }
                            }
                        )
                    },
                    floatingActionButton = {
                        if (screen == Screen.REMINDERS) {
                            var showAddDialog by remember { mutableStateOf(false) }
                            FloatingActionButton(onClick = { showAddDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
                            }
                            if (showAddDialog) {
                                AddReminderDialog(
                                    onDismiss = { showAddDialog = false },
                                    onSave = { reminder ->
                                        scope.launch { reminderDao.insert(reminder) }
                                        showAddDialog = false
                                    }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        color = Color(0xFFF7F7F9)
                    ) {
                        when (screen) {
                            Screen.TRANSACTIONS -> TransactionsScreen(
                                transactionDao = transactionDao,
                                notificationAccessGranted = notificationAccessGranted,
                                onEnableNotificationAccess = { context.startActivity(NotificationAccessHelper.settingsIntent()) }
                            )
                            Screen.REMINDERS -> RemindersScreen(reminderDao)
                        }
                    }
                }
            }
        }
    }
}

// ---------- TRANSACTIONS SCREEN ----------

@Composable
private fun TransactionsScreen(
    transactionDao: TransactionDao,
    notificationAccessGranted: Boolean,
    onEnableNotificationAccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val allTransactions by transactionDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var directionFilter by rememberSaveable { mutableStateOf(DirectionFilter.ALL) }
    var dateFilter by rememberSaveable { mutableStateOf(DateFilter.ALL_TIME) }
    var categoryFilter by rememberSaveable { mutableStateOf<Category?>(null) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    val filtered = remember(allTransactions, searchQuery, directionFilter, dateFilter, categoryFilter) {
        filterTransactions(allTransactions, searchQuery, directionFilter, dateFilter, categoryFilter)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!notificationAccessGranted) {
            OnboardingBanner(onEnableClick = onEnableNotificationAccess)
        }

        if (allTransactions.isEmpty()) {
            EmptyState(showHint = notificationAccessGranted)
        } else {
            SearchBar(searchQuery) { searchQuery = it }
            FilterChipsRow(DirectionFilter.entries, directionFilter, { it.label }) { directionFilter = it }
            FilterChipsRow(DateFilter.entries, dateFilter, { it.label }) { dateFilter = it }
            CategoryFilterRow(categoryFilter) { categoryFilter = it }

            if (filtered.isEmpty()) {
                NoResultsState()
            } else {
                SummaryHeader(filtered)
                TransactionList(filtered, onRowClick = { selectedTransaction = it })
            }
        }
    }

    selectedTransaction?.let { tx ->
        TransactionDetailDialog(
            transaction = tx,
            onDismiss = { selectedTransaction = null },
            onSave = { updated ->
                scope.launch { transactionDao.update(updated) }
                selectedTransaction = null
            }
        )
    }
}

private fun filterTransactions(
    transactions: List<Transaction>,
    query: String,
    direction: DirectionFilter,
    date: DateFilter,
    category: Category?
): List<Transaction> {
    val now = Calendar.getInstance()
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfWeek = (now.clone() as Calendar).apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfMonth = (now.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    return transactions.filter { tx ->
        val matchesQuery = query.isBlank() ||
            (tx.merchantOrContact?.contains(query, ignoreCase = true) == true) ||
            tx.bankOrSource.contains(query, ignoreCase = true) ||
            (tx.note?.contains(query, ignoreCase = true) == true) ||
            (tx.tags?.contains(query, ignoreCase = true) == true)

        val matchesDirection = when (direction) {
            DirectionFilter.ALL -> true
            DirectionFilter.SENT -> tx.direction == Direction.SENT && !tx.needsReview
            DirectionFilter.RECEIVED -> tx.direction == Direction.RECEIVED && !tx.needsReview
            DirectionFilter.NEEDS_REVIEW -> tx.needsReview
        }

        val matchesDate = when (date) {
            DateFilter.ALL_TIME -> true
            DateFilter.TODAY -> tx.timestampMillis >= startOfToday
            DateFilter.THIS_WEEK -> tx.timestampMillis >= startOfWeek
            DateFilter.THIS_MONTH -> tx.timestampMillis >= startOfMonth
        }

        val matchesCategory = category == null || Category.fromNameOrNull(tx.category) == category

        matchesQuery && matchesDirection && matchesDate && matchesCategory
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search merchant, bank, note, tag") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun <T> FilterChipsRow(options: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        items(options) { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(labelOf(option)) })
        }
    }
}

@Composable
private fun CategoryFilterRow(selected: Category?, onSelect: (Category?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All categories") })
        }
        items(Category.entries) { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text("${cat.emoji} ${cat.label}") }
            )
        }
    }
}

@Composable
private fun OnboardingBanner(onEnableClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFFFFF3E0)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFE65100))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Notification access needed", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Expense Tracker reads bank/UPI alerts from your notifications to auto-log transactions. Nothing leaves your device.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF5D4037)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onEnableClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))) {
                Text("Enable in Settings")
            }
        }
    }
}

@Composable
private fun EmptyState(showHint: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFBDBDBD))
            Spacer(modifier = Modifier.height(12.dp))
            Text("No transactions yet", style = MaterialTheme.typography.titleMedium)
            if (showHint) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "New bank/UPI alerts will show up here automatically.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
private fun NoResultsState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color(0xFFBDBDBD))
            Spacer(modifier = Modifier.height(8.dp))
            Text("No matching transactions", style = MaterialTheme.typography.titleSmall)
            Text("Try a different search or filter.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun SummaryHeader(transactions: List<Transaction>) {
    val sent = transactions.filter { it.direction == Direction.SENT }.sumOf { it.amount }
    val received = transactions.filter { it.direction == Direction.RECEIVED }.sumOf { it.amount }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Sent", sent, Color(0xFFD32F2F), Modifier.weight(1f))
        StatCard("Received", received, Color(0xFF2E7D32), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, amount: Double, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.White) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text("₹${"%.2f".format(amount)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun TransactionList(transactions: List<Transaction>, onRowClick: (Transaction) -> Unit) {
    val dayFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val grouped = remember(transactions) { transactions.groupBy { dayFormat.format(Date(it.timestampMillis)) } }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        grouped.forEach { (day, items) ->
            item {
                Text(day, style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            items(items) { tx -> TransactionRow(tx, timeFormat, onClick = { onRowClick(tx) }) }
        }
    }
}

@Composable
private fun TransactionRow(tx: Transaction, timeFormat: SimpleDateFormat, onClick: () -> Unit) {
    val isSent = tx.direction == Direction.SENT
    val iconBg = when { tx.needsReview -> Color(0xFFFFF3E0); isSent -> Color(0xFFFFEBEE); else -> Color(0xFFE8F5E9) }
    val iconTint = when { tx.needsReview -> Color(0xFFE65100); isSent -> Color(0xFFD32F2F); else -> Color(0xFF2E7D32) }
    val icon = when { tx.needsReview -> Icons.Filled.PriorityHigh; isSent -> Icons.Filled.ArrowUpward; else -> Icons.Filled.ArrowDownward }
    val category = Category.fromNameOrNull(tx.category)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp), color = Color.White,
        onClick = onClick
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tx.merchantOrContact ?: "Unknown", fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (category != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(category.emoji, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "${tx.bankOrSource} • ${timeFormat.format(Date(tx.timestampMillis))}" + if (tx.needsReview) " • Needs review" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tx.needsReview) Color(0xFFE65100) else Color.Gray
                )
                if (!tx.note.isNullOrBlank()) {
                    Text("📝 ${tx.note}", style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
                }
            }
            Text(
                "${if (isSent) "-" else "+"}₹${"%.2f".format(tx.amount)}",
                fontWeight = FontWeight.SemiBold,
                color = if (isSent) Color(0xFFD32F2F) else Color(0xFF2E7D32)
            )
        }
    }
}

// ---------- TRANSACTION DETAIL / EDIT DIALOG ----------

@Composable
private fun TransactionDetailDialog(transaction: Transaction, onDismiss: () -> Unit, onSave: (Transaction) -> Unit) {
    var category by remember { mutableStateOf(Category.fromNameOrNull(transaction.category) ?: Category.OTHER) }
    var note by remember { mutableStateOf(transaction.note.orEmpty()) }
    var tags by remember { mutableStateOf(transaction.tags.orEmpty()) }
    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(transaction.merchantOrContact ?: "Unknown") },
        text = {
            Column {
                Text("₹${"%.2f".format(transaction.amount)} • ${transaction.bankOrSource}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))

                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                    OutlinedTextField(
                        value = "${category.emoji} ${category.label}",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        Category.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text("${cat.emoji} ${cat.label}") },
                                onClick = { category = cat; categoryExpanded = false }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note") }, singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it },
                    label = { Text("Tags (comma separated)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    transaction.copy(
                        category = category.name,
                        note = note.trim().takeIf { it.isNotBlank() },
                        tags = tags.trim().takeIf { it.isNotBlank() }
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- REMINDERS SCREEN ----------

@Composable
private fun RemindersScreen(reminderDao: ReminderDao) {
    val scope = rememberCoroutineScope()
    val reminders by reminderDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())

    if (reminders.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFBDBDBD))
                Spacer(modifier = Modifier.height(12.dp))
                Text("No reminders yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Tap + to add a bill or subscription reminder.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(reminders) { reminder ->
                ReminderRow(reminder, onDelete = { scope.launch { reminderDao.delete(reminder.id) } })
            }
        }
    }
}

@Composable
private fun ReminderRow(reminder: Reminder, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp), color = Color.White
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE3F2FD)),
                contentAlignment = Alignment.Center
            ) {
                Text("${reminder.dueDayOfMonth}", fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(reminder.title, fontWeight = FontWeight.Medium)
                Text(
                    "Due day ${reminder.dueDayOfMonth} of month" + (reminder.amount?.let { " • ₹${"%.2f".format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
                if (!reminder.notes.isNullOrBlank()) {
                    Text(reminder.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete reminder", tint = Color(0xFFBDBDBD))
            }
        }
    }
}

@Composable
private fun AddReminderDialog(onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("1") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reminder") },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title (e.g. Netflix, Electricity)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = dueDay,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        val n = digits.toIntOrNull()
                        dueDay = when {
                            digits.isEmpty() -> ""
                            n != null && n in 1..31 -> digits
                            else -> dueDay
                        }
                    },
                    label = { Text("Due day of month (1-31)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val day = dueDay.toIntOrNull() ?: return@TextButton
                    if (title.isBlank() || day !in 1..31) return@TextButton
                    onSave(
                        Reminder(
                            title = title.trim(),
                            amount = amount.toDoubleOrNull(),
                            dueDayOfMonth = day,
                            notes = notes.trim().takeIf { it.isNotBlank() }
                        )
                    )
                },
                enabled = title.isNotBlank() && (dueDay.toIntOrNull()?.let { it in 1..31 } == true)
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}