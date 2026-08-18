@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.expensetracker.data.AppDatabase
import com.expensetracker.data.Budget
import com.expensetracker.data.BudgetDao
import com.expensetracker.data.Category
import com.expensetracker.data.Direction
import com.expensetracker.data.Reminder
import com.expensetracker.data.ReminderDao
import com.expensetracker.data.Transaction
import com.expensetracker.data.TransactionDao
import com.expensetracker.util.NotificationAccessHelper
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.expensetracker.backup.BackupPayload
import com.expensetracker.backup.BackupSerializer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

private enum class DirectionFilter(val label: String) {
    ALL("All"), SENT("Sent"), RECEIVED("Received"), NEEDS_REVIEW("Needs review")
}

private enum class DateFilter(val label: String) {
    TODAY("Today"), THIS_WEEK("This week"), THIS_MONTH("This month"), ALL_TIME("All time"), CUSTOM("Custom range 📅")
}

private enum class SortOption(val label: String) {
    DATE_NEWEST("Newest first"), DATE_OLDEST("Oldest first"),
    AMOUNT_HIGH("Amount: high to low"), AMOUNT_LOW("Amount: low to high")
}

private enum class Screen(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TRANSACTIONS("Transactions", Icons.Filled.List),
    CHARTS("Charts", Icons.Filled.BarChart),
    BUDGETS("Budgets", Icons.Filled.PieChart),
    REMINDERS("Reminders", Icons.Filled.NotificationsActive)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(applicationContext)
        val transactionDao = db.transactionDao()
        val reminderDao = db.reminderDao()
        val budgetDao = db.budgetDao()

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var notificationAccessGranted by remember { mutableStateOf(NotificationAccessHelper.isEnabled(context)) }
                LifecycleStartEffect(Unit) {
                    notificationAccessGranted = NotificationAccessHelper.isEnabled(context)
                    onStopOrDispose { }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                var screen by rememberSaveable { mutableStateOf(Screen.TRANSACTIONS) }
                val allTransactions by transactionDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
                var showManualEntry by remember { mutableStateOf(false) }
                var showAddReminder by remember { mutableStateOf(false) }
                var showBackupDialog by remember { mutableStateOf(false) }
                val snackbarHostState = remember { SnackbarHostState() }

                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        try {
                            val payload = BackupPayload(
                                exportedAtMillis = System.currentTimeMillis(),
                                transactions = transactionDao.getAllOnce(),
                                reminders = reminderDao.getAllOnce(),
                                budgets = budgetDao.getAllOnce()
                            )
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(BackupSerializer.toJson(payload).toByteArray())
                            }
                            snackbarHostState.showSnackbar("Backup saved (${payload.transactions.size} transactions)")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Export failed: ${e.message}")
                        }
                    }
                }

                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        try {
                            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                                BufferedReader(InputStreamReader(input)).readText()
                            } ?: throw IllegalStateException("Could not read file")
                            val payload = BackupSerializer.fromJson(text)

                            transactionDao.deleteAll()
                            reminderDao.deleteAll()
                            budgetDao.deleteAll()
                            payload.transactions.forEach { transactionDao.insert(it) }
                            payload.reminders.forEach { reminderDao.insert(it) }
                            payload.budgets.forEach { budgetDao.upsert(it) }

                            snackbarHostState.showSnackbar("Restored ${payload.transactions.size} transactions")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Restore failed: ${e.message}")
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(screen.label) },
                            actions = {
                                IconButton(onClick = { showBackupDialog = true }) {
                                    Icon(Icons.Filled.CloudSync, contentDescription = "Backup & Restore")
                                }
                            }
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar {
                            Screen.entries.forEach { s ->
                                NavigationBarItem(
                                    selected = screen == s,
                                    onClick = {
                                        screen = s
                                        if (s == Screen.REMINDERS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    icon = { Icon(s.icon, contentDescription = s.label) },
                                    label = { Text(s.label, fontSize = 10.sp) }
                                )
                            }
                        }
                    },
                    floatingActionButton = {
                        when (screen) {
                            Screen.TRANSACTIONS -> FloatingActionButton(onClick = { showManualEntry = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add cash transaction")
                            }
                            Screen.REMINDERS -> FloatingActionButton(onClick = { showAddReminder = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
                            }
                            else -> {}
                        }
                    }
                ) { padding ->
                    Surface(modifier = Modifier.fillMaxSize().padding(padding), color = Color(0xFFF7F7F9)) {
                        when (screen) {
                            Screen.TRANSACTIONS -> TransactionsScreen(
                                transactionDao = transactionDao,
                                allTransactions = allTransactions,
                                notificationAccessGranted = notificationAccessGranted,
                                onEnableNotificationAccess = { context.startActivity(NotificationAccessHelper.settingsIntent()) }
                            )
                            Screen.CHARTS -> ChartsScreen(allTransactions)
                            Screen.BUDGETS -> BudgetsScreen(budgetDao, allTransactions)
                            Screen.REMINDERS -> RemindersScreen(reminderDao)
                        }
                    }

                    if (showManualEntry) {
                        ManualEntryDialog(
                            onDismiss = { showManualEntry = false },
                            onSave = { tx -> scope.launch { transactionDao.insert(tx) }; showManualEntry = false }
                        )
                    }
                    if (showAddReminder) {
                        AddReminderDialog(
                            onDismiss = { showAddReminder = false },
                            onSave = { r -> scope.launch { reminderDao.insert(r) }; showAddReminder = false }
                        )
                    }
                    if (showBackupDialog) {
                        BackupRestoreDialog(
                            onDismiss = { showBackupDialog = false },
                            onExport = {
                                val filename = "expense_tracker_backup_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.json"
                                exportLauncher.launch(filename)
                                showBackupDialog = false
                            },
                            onImport = {
                                importLauncher.launch(arrayOf("application/json"))
                                showBackupDialog = false
                            }
                        )
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
    allTransactions: List<Transaction>,
    notificationAccessGranted: Boolean,
    onEnableNotificationAccess: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var directionFilter by rememberSaveable { mutableStateOf(DirectionFilter.ALL) }
    var dateFilter by rememberSaveable { mutableStateOf(DateFilter.ALL_TIME) }
    var categoryFilter by rememberSaveable { mutableStateOf<Category?>(null) }
    var sortOption by rememberSaveable { mutableStateOf(SortOption.DATE_NEWEST) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    val filtered = remember(allTransactions, searchQuery, directionFilter, dateFilter, categoryFilter, customFrom, customTo, sortOption) {
        val base = filterTransactions(allTransactions, searchQuery, directionFilter, dateFilter, categoryFilter, customFrom, customTo)
        when (sortOption) {
            SortOption.DATE_NEWEST -> base.sortedByDescending { it.timestampMillis }
            SortOption.DATE_OLDEST -> base.sortedBy { it.timestampMillis }
            SortOption.AMOUNT_HIGH -> base.sortedByDescending { it.amount }
            SortOption.AMOUNT_LOW -> base.sortedBy { it.amount }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!notificationAccessGranted) {
            OnboardingBanner(onEnableClick = onEnableNotificationAccess)
        }

        if (allTransactions.isEmpty()) {
            EmptyState(showHint = notificationAccessGranted)
        } else {
            SearchBar(searchQuery) { searchQuery = it }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showDateRangeDialog = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (dateFilter == DateFilter.CUSTOM && customFrom != null && customTo != null)
                                "${shortDate(customFrom!!)} - ${shortDate(customTo!!)}"
                            else "Pick date range"
                        )
                    }
                    if (dateFilter == DateFilter.CUSTOM) {
                        IconButton(
                            onClick = { dateFilter = DateFilter.ALL_TIME; customFrom = null; customTo = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear date range", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                SortMenu(sortOption) { sortOption = it }
            }

            FilterChipsRow(DirectionFilter.entries, directionFilter, { it.label }) { directionFilter = it }
            FilterChipsRow(DateFilter.entries, dateFilter, { it.label }) {
                if (it == DateFilter.CUSTOM) {
                    showDateRangeDialog = true
                } else {
                    dateFilter = it
                    customFrom = null; customTo = null
                }
            }
            CategoryFilterRow(categoryFilter) { categoryFilter = it }

            if (filtered.isEmpty()) {
                NoResultsState()
            } else {
                SummaryHeader(
                    transactions = filtered,
                    directionFilter = directionFilter,
                    onSentClick = { directionFilter = if (directionFilter == DirectionFilter.SENT) DirectionFilter.ALL else DirectionFilter.SENT },
                    onReceivedClick = { directionFilter = if (directionFilter == DirectionFilter.RECEIVED) DirectionFilter.ALL else DirectionFilter.RECEIVED }
                )
                TransactionList(filtered, groupByDay = sortOption == SortOption.DATE_NEWEST || sortOption == SortOption.DATE_OLDEST, onRowClick = { selectedTransaction = it })
            }
        }
    }

    if (showDateRangeDialog) {
        DateRangeDialog(
            initialFrom = customFrom,
            initialTo = customTo,
            onDismiss = { showDateRangeDialog = false },
            onApply = { from, to ->
                customFrom = from; customTo = to
                dateFilter = DateFilter.CUSTOM
                showDateRangeDialog = false
            }
        )
    }

    selectedTransaction?.let { tx ->
        TransactionDetailDialog(
            transaction = tx,
            onDismiss = { selectedTransaction = null },
            onSave = { updated -> scope.launch { transactionDao.update(updated) }; selectedTransaction = null }
        )
    }
}

private fun shortDate(millis: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

private fun filterTransactions(
    transactions: List<Transaction>,
    query: String,
    direction: DirectionFilter,
    date: DateFilter,
    category: Category?,
    customFrom: Long?,
    customTo: Long?
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
            DateFilter.CUSTOM -> {
                if (customFrom == null || customTo == null) true
                else tx.timestampMillis in customFrom..(customTo + 86_400_000L - 1)
            }
        }

        val matchesCategory = category == null || Category.fromNameOrNull(tx.category) == category

        matchesQuery && matchesDirection && matchesDate && matchesCategory
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange,
        placeholder = { Text("Search merchant, bank, note, tag") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SortMenu(selected: SortOption, onSelect: (SortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option); expanded = false },
                    leadingIcon = { if (option == selected) Icon(Icons.Filled.Check, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
private fun <T> FilterChipsRow(options: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        items(options) { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(labelOf(option)) })
        }
    }
}

@Composable
private fun CategoryFilterRow(selected: Category?, onSelect: (Category?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All categories") }) }
        items(Category.entries) { cat ->
            FilterChip(selected = selected == cat, onClick = { onSelect(cat) }, label = { Text("${cat.emoji} ${cat.label}") })
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
            Button(onClick = onEnableClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))) { Text("Enable in Settings") }
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
                    "New bank/UPI alerts will show up here automatically. Tap + to add a cash expense.",
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
private fun SummaryHeader(
    transactions: List<Transaction>,
    directionFilter: DirectionFilter,
    onSentClick: () -> Unit,
    onReceivedClick: () -> Unit
) {
    val sent = transactions.filter { it.direction == Direction.SENT }.sumOf { it.amount }
    val received = transactions.filter { it.direction == Direction.RECEIVED }.sumOf { it.amount }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Sent", sent, Color(0xFFD32F2F), directionFilter == DirectionFilter.SENT, Modifier.weight(1f), onSentClick)
        StatCard("Received", received, Color(0xFF2E7D32), directionFilter == DirectionFilter.RECEIVED, Modifier.weight(1f), onReceivedClick)
    }
}

@Composable
private fun StatCard(label: String, amount: Double, color: Color, isActive: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (isActive) color.copy(alpha = 0.12f) else Color.White,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text("₹${"%.2f".format(amount)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun TransactionList(transactions: List<Transaction>, groupByDay: Boolean, onRowClick: (Transaction) -> Unit) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    if (!groupByDay) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)) {
            items(transactions) { tx -> TransactionRow(tx, timeFormat, onClick = { onRowClick(tx) }) }
        }
        return
    }

    val dayFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val grouped = remember(transactions) { transactions.groupBy { dayFormat.format(Date(it.timestampMillis)) } }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        grouped.forEach { (day, items) ->
            item { Text(day, style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
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

    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = Color.White, onClick = onClick) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tx.merchantOrContact ?: "Unknown", fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (category != null) { Spacer(modifier = Modifier.width(4.dp)); Text(category.emoji, style = MaterialTheme.typography.bodySmall) }
                }
                Text(
                    "${tx.bankOrSource} • ${timeFormat.format(Date(tx.timestampMillis))}" + if (tx.needsReview) " • Needs review" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (tx.needsReview) Color(0xFFE65100) else Color.Gray
                )
                if (!tx.note.isNullOrBlank()) Text("📝 ${tx.note}", style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
            }
            Text("${if (isSent) "-" else "+"}₹${"%.2f".format(tx.amount)}", fontWeight = FontWeight.SemiBold, color = if (isSent) Color(0xFFD32F2F) else Color(0xFF2E7D32))
        }
    }
}

// ---------- DATE RANGE PICKER ----------

@Composable
private fun DateRangeDialog(initialFrom: Long?, initialTo: Long?, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    var pickingFrom by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    val fromState = rememberDatePickerState(initialSelectedDateMillis = from)
    val toState = rememberDatePickerState(initialSelectedDateMillis = to)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick date range") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = pickingFrom, onClick = { pickingFrom = true }, label = { Text("From: ${from?.let { shortDate(it) } ?: "—"}") })
                    FilterChip(selected = !pickingFrom, onClick = { pickingFrom = false }, label = { Text("To: ${to?.let { shortDate(it) } ?: "—"}") })
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (pickingFrom) {
                    DatePicker(state = fromState, showModeToggle = false)
                    from = fromState.selectedDateMillis
                } else {
                    DatePicker(state = toState, showModeToggle = false)
                    to = toState.selectedDateMillis
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (from != null && to != null) onApply(minOf(from!!, to!!), maxOf(from!!, to!!)) },
                enabled = from != null && to != null
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
                        value = "${category.emoji} ${category.label}", onValueChange = {}, readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        Category.entries.forEach { cat ->
                            DropdownMenuItem(text = { Text("${cat.emoji} ${cat.label}") }, onClick = { category = cat; categoryExpanded = false })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(transaction.copy(category = category.name, note = note.trim().takeIf { it.isNotBlank() }, tags = tags.trim().takeIf { it.isNotBlank() }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- MANUAL CASH ENTRY ----------

@Composable
private fun ManualEntryDialog(onDismiss: () -> Unit, onSave: (Transaction) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Direction.SENT) }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.OTHER) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add cash expense") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == Direction.SENT, onClick = { direction = Direction.SENT }, label = { Text("Sent") })
                    FilterChip(selected = direction == Direction.RECEIVED, onClick = { direction = Direction.RECEIVED }, label = { Text("Received") })
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("Merchant / person") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                    OutlinedTextField(
                        value = "${category.emoji} ${category.label}", onValueChange = {}, readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        Category.entries.forEach { cat ->
                            DropdownMenuItem(text = { Text("${cat.emoji} ${cat.label}") }, onClick = { category = cat; categoryExpanded = false })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: return@TextButton
                    onSave(
                        Transaction(
                            amount = amt, direction = direction,
                            merchantOrContact = merchant.trim().takeIf { it.isNotBlank() },
                            bankOrSource = "Cash", timestampMillis = System.currentTimeMillis(),
                            category = category.name, note = note.trim().takeIf { it.isNotBlank() },
                            rawTextHash = "manual-${UUID.randomUUID()}", needsReview = false
                        )
                    )
                },
                enabled = amount.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- CHARTS SCREEN (custom lightweight bar chart, no external chart lib) ----------

@Composable
private fun ChartsScreen(allTransactions: List<Transaction>) {
    val now = Calendar.getInstance()
    val startOfMonth = (now.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val thisMonthSpend = remember(allTransactions) {
        allTransactions.filter { it.direction == Direction.SENT && it.timestampMillis >= startOfMonth }
    }
    val byCategory = remember(thisMonthSpend) {
        thisMonthSpend.groupBy { Category.fromNameOrNull(it.category) ?: Category.OTHER }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
            .toList().sortedByDescending { it.second }
    }

    if (byCategory.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFBDBDBD))
                Spacer(modifier = Modifier.height(12.dp))
                Text("No spending this month yet", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    val total = byCategory.sumOf { it.second }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("This month's spending by category", style = MaterialTheme.typography.titleMedium)
            Text("Total: ₹${"%.2f".format(total)}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Spacer(modifier = Modifier.height(16.dp))
        }
        items(byCategory) { (category, amount) ->
            CategoryBarRow(category, amount, total)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CategoryBarRow(category: Category, amount: Double, total: Double) {
    val fraction = if (total > 0) (amount / total).toFloat() else 0f
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("${category.emoji} ${category.label}", style = MaterialTheme.typography.bodyMedium)
            Text("₹${"%.2f".format(amount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            drawRoundRect(color = Color(0xFFE0E0E0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f))
            drawRoundRect(
                color = Color(0xFF5C6BC0),
                size = size.copy(width = size.width * fraction),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f)
            )
        }
    }
}

// ---------- BUDGETS SCREEN ----------

@Composable
private fun BudgetsScreen(budgetDao: BudgetDao, allTransactions: List<Transaction>) {
    val scope = rememberCoroutineScope()
    val budgets by budgetDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var editingCategory by remember { mutableStateOf<Category?>(null) }

    val now = Calendar.getInstance()
    val startOfMonth = (now.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val spendByCategory = remember(allTransactions) {
        allTransactions.filter { it.direction == Direction.SENT && it.timestampMillis >= startOfMonth }
            .groupBy { Category.fromNameOrNull(it.category) ?: Category.OTHER }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Set a monthly limit per category. Tap any category to edit.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
        }
        items(Category.entries) { cat ->
            val limit = budgets.firstOrNull { it.category == cat.name }?.monthlyLimit
            val spent = spendByCategory[cat] ?: 0.0
            BudgetRow(cat, limit, spent, onClick = { editingCategory = cat })
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    editingCategory?.let { cat ->
        val currentLimit = budgets.firstOrNull { it.category == cat.name }?.monthlyLimit
        EditBudgetDialog(
            category = cat, currentLimit = currentLimit,
            onDismiss = { editingCategory = null },
            onSave = { limit -> scope.launch { budgetDao.upsert(Budget(cat.name, limit)) }; editingCategory = null },
            onRemove = { scope.launch { budgetDao.delete(cat.name) }; editingCategory = null }
        )
    }
}

@Composable
private fun BudgetRow(category: Category, limit: Double?, spent: Double, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, onClick = onClick) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${category.emoji} ${category.label}", fontWeight = FontWeight.Medium)
                Text(
                    if (limit != null) "₹${"%.0f".format(spent)} / ₹${"%.0f".format(limit)}" else "No limit set",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
            }
            if (limit != null && limit > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                val fraction = (spent / limit).toFloat().coerceIn(0f, 1f)
                val barColor = when {
                    spent > limit -> Color(0xFFD32F2F)
                    spent / limit > 0.7 -> Color(0xFFF57C00)
                    else -> Color(0xFF2E7D32)
                }
                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    drawRoundRect(color = Color(0xFFE0E0E0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
                    drawRoundRect(color = barColor, size = size.copy(width = size.width * fraction), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
                }
                if (spent > limit) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Over budget by ₹${"%.2f".format(spent - limit)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD32F2F))
                }
            }
        }
    }
}

@Composable
private fun EditBudgetDialog(category: Category, currentLimit: Double?, onDismiss: () -> Unit, onSave: (Double) -> Unit, onRemove: () -> Unit) {
    var limitText by remember { mutableStateOf(currentLimit?.let { "%.0f".format(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${category.emoji} ${category.label} budget") },
        text = {
            OutlinedTextField(
                value = limitText, onValueChange = { limitText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Monthly limit (₹)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { limitText.toDoubleOrNull()?.let(onSave) }, enabled = limitText.toDoubleOrNull() != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (currentLimit != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
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
            items(reminders) { reminder -> ReminderRow(reminder, onDelete = { scope.launch { reminderDao.delete(reminder.id) } }) }
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
                    "Due day ${reminder.dueDayOfMonth} of month" + (reminder.amount?.let { " • ₹${"%.2f".format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
                if (!reminder.notes.isNullOrBlank()) Text(reminder.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete reminder", tint = Color(0xFFBDBDBD)) }
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

// ---------- BACKUP & RESTORE ----------

@Composable
private fun BackupRestoreDialog(onDismiss: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var confirmingImport by remember { mutableStateOf(false) }

    if (confirmingImport) {
        AlertDialog(
            onDismissRequest = { confirmingImport = false },
            title = { Text("Restore from backup?") },
            text = { Text("This replaces ALL current transactions, reminders, and budgets with the contents of the backup file. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmingImport = false; onImport() }) {
                    Text("Replace everything", color = Color(0xFFD32F2F))
                }
            },
            dismissButton = { TextButton(onClick = { confirmingImport = false }) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup & Restore") },
        text = {
            Column {
                Text(
                    "Export saves all your data to a file you choose (Drive, local storage, etc). Restore replaces everything with a previously exported file.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFFF3E0)) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "The backup file is NOT encrypted. Save it somewhere private, not a publicly shared folder.",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF5D4037)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmingImport = true }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}