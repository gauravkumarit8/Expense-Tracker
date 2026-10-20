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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import com.autoexpensetracker.ui.theme.MutedText
import com.autoexpensetracker.util.ChartMath
import com.autoexpensetracker.util.MonthRange
import com.autoexpensetracker.util.SummaryPeriod
import com.autoexpensetracker.util.SummaryPeriodStore
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
import com.autoexpensetracker.util.BankSourceMatcher
import com.autoexpensetracker.util.ManualBalanceStore
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

// ---------- CHARTS SCREEN (custom lightweight bar chart, no external chart lib) ----------

@Composable
internal fun ChartsScreen(allTransactions: List<Transaction>) {
    // BalanceVisibilityStore already existed but was never read from or
    // written to anywhere in the UI — the "Account balances" section always
    // rendered real figures with no way to mask them. Wired up here: state
    // seeded from the store (defaults hidden, see BalanceVisibilityStore
    // doc comment), toggled by the eye icon next to the section heading,
    // and persisted back on every toggle so the choice survives navigating
    // away and relaunching the app.
    val context = LocalContext.current
    var balancesVisible by remember { mutableStateOf(BalanceVisibilityStore.isVisible(context)) }

    // Manual overrides/additions/deletions for the balances section — see
    // ManualBalanceStore doc comment. Re-read after every mutation rather
    // than kept as a Flow, matching this screen's existing pattern for
    // BalanceVisibilityStore above (small, infrequently-changed local
    // preference state, not data that changes from outside this screen).
    var manualEntries by remember { mutableStateOf(ManualBalanceStore.getAll(context)) }
    var hiddenSources by remember { mutableStateOf(ManualBalanceStore.getHidden(context)) }
    var editingSource by remember { mutableStateOf<String?>(null) }
    var showAddBalanceDialog by remember { mutableStateOf(false) }

    fun refreshManualBalances() {
        manualEntries = ManualBalanceStore.getAll(context)
        hiddenSources = ManualBalanceStore.getHidden(context)
    }

    var selectedMonth by rememberSaveable { mutableStateOf(MonthRange.current()) }
    val startOfMonth = selectedMonth.startOfMonthMillis()
    val endOfMonth = selectedMonth.endOfMonthMillis()

    // Latest known balance per bank/account source — most recent transaction
    // (by timestamp) that happened to include a parsed balanceAfter value.
    //
    // Grouped by BankSourceMatcher.accountKey, NOT the raw sender string: the
    // same bank arrives under different route prefixes ("JD-SLCBNK-T" vs
    // "JK-SLCBNK-T"), which used to show one real account as two cards. The
    // card is labelled with the sender string of the most recent message.
    val autoBalances = remember(allTransactions) {
        allTransactions
            .filter { it.balanceAfter != null }
            .groupBy { BankSourceMatcher.accountKey(it.bankOrSource) }
            .mapValues { (_, txs) -> txs.maxByOrNull { it.timestampMillis }!! }
    }

    // Merge auto-detected balances with manual overrides/additions, drop
    // anything the user has deleted, sort newest-first. A manual entry for
    // a source that also has auto-detected transactions wins outright
    // (see ManualBalanceStore doc comment on why that's unconditional).
    data class BalanceEntry(val source: String, val amount: Double, val asOfMillis: Long, val isManual: Boolean)

    val latestBalances = remember(autoBalances, manualEntries, hiddenSources) {
        // Manual entries are matched by account key too, so a correction typed
        // under "JD-SLCBNK-T" also overrides auto-detected "JK-SLCBNK-T", and
        // two manual cards for the same bank collapse to the most recent one.
        // Hand-typed names ("HDFC", "CBI") aren't sender-ID-shaped, so they
        // keep their own distinct key and never merge by accident.
        val manualByKey = manualEntries
            .groupBy { BankSourceMatcher.accountKey(it.source) }
            .mapValues { (_, entries) -> entries.maxByOrNull { it.asOfMillis }!! }
        // Hiding ("deleting") an AUTO-detected card hides every route variant
        // of that bank. Manual cards keep the exact-source rule they always
        // had, so adding an account by hand always shows it (upsert() un-hides
        // that exact source).
        val hiddenKeys = hiddenSources.map { BankSourceMatcher.accountKey(it) }.toSet()
        val autoOnly = autoBalances
            .filterKeys { it !in manualByKey.keys && it !in hiddenKeys }
            .values
            .map { tx -> BalanceEntry(tx.bankOrSource, tx.balanceAfter!!, tx.timestampMillis, isManual = false) }
        val manual = manualByKey.values
            .filterNot { hiddenSources.contains(it.source) }
            .map { BalanceEntry(it.source, it.amount, it.asOfMillis, isManual = true) }
        (autoOnly + manual).sortedByDescending { it.asOfMillis }
    }

    val thisMonthSpend = remember(allTransactions, startOfMonth, endOfMonth) {
        allTransactions.filter { it.direction == Direction.SENT && it.timestampMillis in startOfMonth..endOfMonth }
    }
    val byCategory = remember(thisMonthSpend) {
        thisMonthSpend.groupBy { Category.fromNameOrNull(it.category) ?: Category.OTHER }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
            .toList().sortedByDescending { it.second }
    }

    if (showAddBalanceDialog) {
        AddOrEditBalanceDialog(
            initialSource = null,
            onDismiss = { showAddBalanceDialog = false },
            onSave = { source, amount ->
                ManualBalanceStore.upsert(context, source, amount)
                refreshManualBalances()
                showAddBalanceDialog = false
            }
        )
    }
    editingSource?.let { source ->
        val current = latestBalances.firstOrNull { it.source == source }
        AddOrEditBalanceDialog(
            initialSource = source,
            initialAmount = current?.amount,
            onDismiss = { editingSource = null },
            onSave = { _, amount ->
                ManualBalanceStore.upsert(context, source, amount)
                refreshManualBalances()
                editingSource = null
            }
        )
    }

    // Only the true empty-app state (no transactions AND no balances at
    // all) short-circuits to the full-screen placeholder. byCategory alone
    // being empty is NOT enough now that a month is user-selectable — a
    // selected past/future-adjacent month with zero spend must still show
    // the month picker and trend chart so the user has a way to navigate
    // back out of it, rather than getting stuck on a dead-end screen.
    if (allTransactions.isEmpty() && latestBalances.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(48.dp), tint = SemanticGray)
                Spacer(modifier = Modifier.height(12.dp))
                Text("No spending this month yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = { showAddBalanceDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add an account balance")
                }
            }
        }
        return
    }

    val total = byCategory.sumOf { it.second }
    val monthReceived = remember(allTransactions, startOfMonth, endOfMonth) {
        allTransactions
            .filter { it.direction == Direction.RECEIVED && it.timestampMillis in startOfMonth..endOfMonth }
            .sumOf { it.amount }
    }
    val dailySpend = remember(thisMonthSpend, selectedMonth) {
        ChartMath.dailyTotals(
            thisMonthSpend.map { it.timestampMillis to it.amount },
            selectedMonth.year,
            selectedMonth.month
        )
    }
    val elapsedDays = remember(selectedMonth) {
        ChartMath.elapsedDays(selectedMonth.year, selectedMonth.month, System.currentTimeMillis())
    }
    // Theme colours are read here, in composable scope, and handed down —
    // they cannot be read inside a Canvas draw lambda.
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            MonthSwitcher(
                label = selectedMonth.label(),
                canGoNext = !selectedMonth.isCurrentOrFuture(),
                onPrevious = { selectedMonth = selectedMonth.previous() },
                onNext = { selectedMonth = selectedMonth.next() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            MonthSummaryCard(spent = total, received = monthReceived)
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Text("Spending by category", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            if (byCategory.isEmpty()) {
                Text("No spending in ${selectedMonth.label()}", style = MaterialTheme.typography.bodySmall, color = MutedText)
            } else {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpendDonut(slices = byCategory, total = total, trackColor = trackColor)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        if (byCategory.isNotEmpty()) {
            items(byCategory) { (category, amount) ->
                CategoryBarRow(category, amount, total, trackColor)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (total > 0.0) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Daily spending", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                DailySpendChart(
                    daily = dailySpend,
                    total = total,
                    elapsedDays = elapsedDays,
                    monthLabel = selectedMonth.label()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Spending trend", style = MaterialTheme.typography.titleMedium)
            Text("Last 6 months, spent vs received. Tap a month to jump to it.", style = MaterialTheme.typography.bodySmall, color = MutedText)
            Spacer(modifier = Modifier.height(12.dp))
            SpendTrendChart(
                allTransactions = allTransactions,
                selectedMonth = selectedMonth,
                onSelectMonth = { selectedMonth = it }
            )
            Spacer(modifier = Modifier.height(28.dp))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Account balances", style = MaterialTheme.typography.titleMedium)
                    Text("Latest known balance per account", style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showAddBalanceDialog = true }) {
                        Icon(Icons.Filled.AddCircle, contentDescription = "Add account balance", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        balancesVisible = !balancesVisible
                        BalanceVisibilityStore.setVisible(context, balancesVisible)
                    }) {
                        Icon(
                            if (balancesVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (balancesVisible) "Hide balances" else "Show balances",
                            tint = MutedText
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (latestBalances.isEmpty()) {
            item {
                Text(
                    "No account balances yet — captured messages with a balance will show up here, or add one manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        } else {
            items(latestBalances, key = { it.source }) { entry ->
                // Two history sources merged chronologically: the
                // balanceAfter parsed off every transaction (auto-detected
                // accounts), and every manually-entered figure for this
                // source (2026-09-21: manual entries used to have no
                // history at all, since every edit overwrote the last one —
                // ManualBalanceStore now keeps the full timeline instead).
                // A source could plausibly have both if it started
                // auto-detected and was later manually corrected once or
                // twice, or vice versa — merging means the chart reflects
                // everything actually known about the account, not just
                // whichever source happened to produce it.
                val history = remember(allTransactions, manualEntries, entry.source) {
                    val key = BankSourceMatcher.accountKey(entry.source)
                    val fromTransactions = allTransactions
                        .filter { BankSourceMatcher.accountKey(it.bankOrSource) == key && it.balanceAfter != null }
                        .map { it.timestampMillis to it.balanceAfter!! }
                    val fromManual = ManualBalanceStore.exportAll(context)
                        .filter { BankSourceMatcher.accountKey(it.source) == key }
                        .map { it.asOfMillis to it.amount }
                    (fromTransactions + fromManual).sortedBy { it.first }
                }
                BalanceRow(
                    source = entry.source,
                    balance = entry.amount,
                    asOfMillis = entry.asOfMillis,
                    visible = balancesVisible,
                    isManual = entry.isManual,
                    history = history,
                    onEdit = { editingSource = entry.source },
                    onDelete = {
                        ManualBalanceStore.delete(context, entry.source)
                        refreshManualBalances()
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            item { Spacer(modifier = Modifier.height(20.dp)) }
        }

    }
}

@Composable
private fun BalanceRow(
    source: String,
    balance: Double,
    asOfMillis: Long,
    visible: Boolean,
    isManual: Boolean,
    history: List<Pair<Long, Double>>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove $source?") },
            text = { Text("This hides it from Account balances. If new messages from this bank arrive later, add it back manually to show it again.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
    if (showHistory) {
        BalanceHistoryDialog(source = source, history = history, visible = visible, onDismiss = { showHistory = false })
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Text(source, fontWeight = FontWeight.Medium)
                    if (isManual) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("(manual)", style = MaterialTheme.typography.labelSmall, color = MutedText)
                    }
                }
                Text(
                    if (visible) formatInr(balance) else "₹ • • • • • •",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("as of ${dateFormat.format(Date(asOfMillis))}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHistory = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ShowChart, contentDescription = "View $source balance history", tint = MutedText, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit $source balance", tint = MutedText, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove $source", tint = SemanticRed, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceHistoryDialog(
    source: String,
    history: List<Pair<Long, Double>>,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val pointDateFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$source balance history") },
        text = {
            if (history.size < 2) {
                Text(
                    "Not enough history yet to chart — this builds up as more transactions are captured, or each time you manually update this account's balance.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                var selectedIndex by remember(history) { mutableStateOf(history.size - 1) }
                Column {
                    val minY = history.minOf { it.second }
                    val maxY = history.maxOf { it.second }
                    val (selectedTime, selectedValue) = history[selectedIndex]
                    Text(
                        pointDateFormat.format(Date(selectedTime)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText
                    )
                    Text(
                        if (visible) formatInr(selectedValue) else "₹ • • • • • •",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .padding(vertical = 4.dp)
                            .pointerInput(history) {
                                detectTapGestures { offset ->
                                    // size here is IntSize -> size.width is Int.
                                    // Convert to Float so both branches of the
                                    // if-expression produce Float, otherwise
                                    // Kotlin infers a captured Comparable type
                                    // and `offset.x / stepX` fails to compile.
                                    val stepX = if (history.size > 1) {
                                        size.width.toFloat() / (history.size - 1)
                                    } else 0f
                                    val nearest = if (stepX > 0f) {
                                        (offset.x / stepX).toInt().coerceIn(0, history.size - 1)
                                    } else 0
                                    selectedIndex = nearest
                                }
                            }
                    ) {
                        val yRange = (maxY - minY).takeIf { it > 0.0 } ?: 1.0
                        val stepX = if (history.size > 1) size.width / (history.size - 1) else 0f
                        fun pointOffset(index: Int, value: Double): Offset {
                            val x = index * stepX
                            val y = size.height - ((value - minY) / yRange * size.height).toFloat()
                            return Offset(x, y)
                        }
                        val path = Path()
                        history.forEachIndexed { i, (_, y) ->
                            val p = pointOffset(i, y)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        drawPath(path, color = BrandGreen, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                        history.forEachIndexed { i, (_, y) ->
                            val p = pointOffset(i, y)
                            if (i == selectedIndex) {
                                drawLine(
                                    color = BrandGreen.copy(alpha = 0.3f),
                                    start = Offset(p.x, 0f),
                                    end = Offset(p.x, size.height),
                                    strokeWidth = 1.dp.toPx()
                                )
                                drawCircle(color = Color.White, radius = 7.dp.toPx(), center = p)
                                drawCircle(color = BrandGreen, radius = 7.dp.toPx(), center = p, style = Stroke(width = 2.5.dp.toPx()))
                            } else {
                                drawCircle(color = BrandGreen, radius = 4.dp.toPx(), center = p)
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dateFormat.format(Date(history.first().first)), style = MaterialTheme.typography.labelSmall, color = MutedText)
                        Text(dateFormat.format(Date(history.last().first)), style = MaterialTheme.typography.labelSmall, color = MutedText)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (visible) "Lowest: ${formatInr(minY)}" else "Lowest: ₹ • • • • • •",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText
                    )
                    Text(
                        "${history.size} balance points captured — tap the chart to see any point",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AddOrEditBalanceDialog(
    initialSource: String?,
    initialAmount: Double? = null,
    onDismiss: () -> Unit,
    onSave: (source: String, amount: Double) -> Unit
) {
    var source by remember { mutableStateOf(initialSource ?: "") }
    var amount by remember { mutableStateOf(initialAmount?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    val isEditing = initialSource != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit balance" else "Add account balance") },
        text = {
            Column {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Account / bank name") },
                    singleLine = true,
                    enabled = !isEditing,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Current balance") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: return@TextButton
                    val src = source.trim()
                    if (src.isEmpty()) return@TextButton
                    onSave(src, amt)
                },
                enabled = amount.toDoubleOrNull() != null && source.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One colour per category, kept stable so "Food" is the same colour everywhere. */
private fun categoryColor(category: Category): Color = when (category) {
    Category.FOOD -> Color(0xFFEF6C00)
    Category.GROCERIES -> Color(0xFF43A047)
    Category.SHOPPING -> Color(0xFFAB47BC)
    Category.BILLS -> Color(0xFF1E88E5)
    Category.TRANSFER -> Color(0xFF78909C)
    Category.ENTERTAINMENT -> Color(0xFFEC407A)
    Category.TRAVEL -> Color(0xFF00ACC1)
    Category.HEALTH -> Color(0xFFE53935)
    Category.OTHER -> Color(0xFF8D6E63)
}

@Composable
private fun MonthSwitcher(label: String, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
        }
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun MonthSummaryCard(spent: Double, received: Double) {
    val net = received - spent
    val netColor = if (net >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val netText = (if (net >= 0.0) "+" else "-") + formatInrWhole(kotlin.math.abs(net))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryStat("Spent", formatInrWhole(spent), MaterialTheme.colorScheme.error)
            SummaryStat("Received", formatInrWhole(received), MaterialTheme.colorScheme.primary)
            SummaryStat("Net", netText, netColor)
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MutedText)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/** Ring split by category, total in the middle. */
@Composable
private fun SpendDonut(slices: List<Pair<Category, Double>>, total: Double, trackColor: Color) {
    Box(modifier = Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 28.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            val gap = if (slices.size > 1) 2f else 0f
            var start = -90f
            slices.forEach { (category, amount) ->
                val sweep = (amount / total * 360.0).toFloat()
                drawArc(
                    color = categoryColor(category),
                    startAngle = start,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Spent", style = MaterialTheme.typography.labelMedium, color = MutedText)
            Text(formatInrWhole(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CategoryBarRow(category: Category, amount: Double, total: Double, trackColor: Color) {
    val fraction = if (total > 0) (amount / total).toFloat().coerceIn(0f, 1f) else 0f
    val color = categoryColor(category)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(8.dp))
            Text("${category.emoji} ${category.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(ChartMath.percentLabel(amount, total), style = MaterialTheme.typography.bodySmall, color = MutedText)
            Spacer(modifier = Modifier.width(12.dp))
            Text(formatInr(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

/** One bar per day of the month, with a dashed daily-average line and the peak day highlighted. */
@Composable
private fun DailySpendChart(daily: DoubleArray, total: Double, elapsedDays: Int, monthLabel: String) {
    val barColor = MaterialTheme.colorScheme.primary
    val peakColor = MaterialTheme.colorScheme.error
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val maxDay = daily.maxOrNull() ?: 0.0
    val peak = ChartMath.peakIndex(daily)
    val avg = ChartMath.dailyAverage(total, elapsedDays)

    Text(
        "Average ${formatInrWhole(avg)} a day in $monthLabel",
        style = MaterialTheme.typography.bodySmall,
        color = MutedText
    )
    if (peak >= 0) {
        Text(
            "Highest: day ${peak + 1}, ${formatInrWhole(daily[peak])}",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
        val n = daily.size
        val slot = size.width / n
        val barW = slot * 0.62f
        val corner = androidx.compose.ui.geometry.CornerRadius(barW / 3f, barW / 3f)
        daily.forEachIndexed { i, v ->
            val f = ChartMath.barFraction(v, maxDay)
            if (f > 0f) {
                val h = size.height * f
                drawRoundRect(
                    color = if (i == peak) peakColor else barColor,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = corner
                )
            }
        }
        drawLine(
            color = baseColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
        if (avg > 0.0 && maxDay > 0.0) {
            val y = size.height - size.height * (avg / maxDay).toFloat().coerceIn(0f, 1f)
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("1", style = MaterialTheme.typography.labelSmall, color = MutedText)
        Text("${daily.size}", style = MaterialTheme.typography.labelSmall, color = MutedText)
    }
}

private data class TrendMonth(val month: MonthRange, val spent: Double, val received: Double)

/**
 * Spent and received for each of the trailing 6 real-world months (ending at
 * the actual current month, independent of [selectedMonth] navigation, so
 * the window doesn't shift under the user as they browse). Tapping a month
 * jumps [selectedMonth] to it.
 *
 * Bar heights are explicit dp values computed from the data, and the month
 * labels sit in their own row below the bar area, so a tall bar can never
 * push its label out of the chart.
 */
@Composable
private fun SpendTrendChart(
    allTransactions: List<Transaction>,
    selectedMonth: MonthRange,
    onSelectMonth: (MonthRange) -> Unit
) {
    val months = remember {
        val current = MonthRange.current()
        (5 downTo 0).map { offset ->
            var m = current
            repeat(offset) { m = m.previous() }
            m
        }
    }
    val totals = remember(allTransactions, months) {
        months.map { m ->
            TrendMonth(
                month = m,
                spent = allTransactions
                    .filter { it.direction == Direction.SENT && m.contains(it.timestampMillis) }
                    .sumOf { it.amount },
                received = allTransactions
                    .filter { it.direction == Direction.RECEIVED && m.contains(it.timestampMillis) }
                    .sumOf { it.amount }
            )
        }
    }
    val maxValue = totals.maxOfOrNull { maxOf(it.spent, it.received) } ?: 0.0

    if (maxValue <= 0.0) {
        Text("Not enough history yet to show a trend.", style = MaterialTheme.typography.bodySmall, color = MutedText)
        return
    }

    val spentColor = MaterialTheme.colorScheme.error
    val receivedColor = MaterialTheme.colorScheme.primary
    val monthShortFormat = remember { SimpleDateFormat("MMM", Locale.getDefault()) }
    val chartHeight = 130.dp
    val barShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)

    val selected = totals.firstOrNull { it.month == selectedMonth }
    if (selected != null) {
        Text(
            "${selected.month.label()}: spent ${formatInrWhole(selected.spent)}, received ${formatInrWhole(selected.received)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        totals.forEach { t ->
            val isSelected = t.month == selectedMonth
            val alpha = if (isSelected) 1f else 0.45f
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = { onSelectMonth(t.month) })
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(chartHeight * ChartMath.barFraction(t.spent, maxValue))
                                .clip(barShape)
                                .background(spentColor.copy(alpha = alpha))
                        )
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(chartHeight * ChartMath.barFraction(t.received, maxValue))
                                .clip(barShape)
                                .background(receivedColor.copy(alpha = alpha))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    monthShortFormat.format(Date(t.month.startOfMonthMillis())),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MutedText,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(spentColor))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Spent", style = MaterialTheme.typography.labelSmall, color = MutedText)
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(receivedColor))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Received", style = MaterialTheme.typography.labelSmall, color = MutedText)
    }
}
