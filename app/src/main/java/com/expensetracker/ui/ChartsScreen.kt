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

    val now = Calendar.getInstance()
    val startOfMonth = (now.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // Latest known balance per bank/account source — most recent transaction
    // (by timestamp) that happened to include a parsed balanceAfter value.
    val autoBalances = remember(allTransactions) {
        allTransactions
            .filter { it.balanceAfter != null }
            .groupBy { it.bankOrSource }
            .mapValues { (_, txs) -> txs.maxByOrNull { it.timestampMillis }!! }
    }

    // Merge auto-detected balances with manual overrides/additions, drop
    // anything the user has deleted, sort newest-first. A manual entry for
    // a source that also has auto-detected transactions wins outright
    // (see ManualBalanceStore doc comment on why that's unconditional).
    data class BalanceEntry(val source: String, val amount: Double, val asOfMillis: Long, val isManual: Boolean)

    val latestBalances = remember(autoBalances, manualEntries, hiddenSources) {
        val manualBySource = manualEntries.associateBy { it.source }
        val autoOnly = autoBalances.keys.minus(manualBySource.keys).map { source ->
            val tx = autoBalances.getValue(source)
            BalanceEntry(source, tx.balanceAfter!!, tx.timestampMillis, isManual = false)
        }
        val manual = manualEntries.map { BalanceEntry(it.source, it.amount, it.asOfMillis, isManual = true) }
        (autoOnly + manual)
            .filterNot { hiddenSources.contains(it.source) }
            .sortedByDescending { it.asOfMillis }
    }

    val thisMonthSpend = remember(allTransactions) {
        allTransactions.filter { it.direction == Direction.SENT && it.timestampMillis >= startOfMonth }
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

    if (byCategory.isEmpty() && latestBalances.isEmpty()) {
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

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Account balances", style = MaterialTheme.typography.titleMedium)
                    Text("Latest known balance per account", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                            tint = Color.Gray
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
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        } else {
            items(latestBalances, key = { it.source }) { entry ->
                // The balanceAfter parsed off every transaction already forms a
                // real historical timeline per source — never charted before
                // now. Only meaningful for auto-detected sources (a purely
                // manual entry has no transactions to derive history from);
                // BalanceHistoryDialog handles an empty/single-point list
                // gracefully rather than this needing to special-case it here.
                val history = remember(allTransactions, entry.source) {
                    allTransactions
                        .filter { it.bankOrSource == entry.source && it.balanceAfter != null }
                        .sortedBy { it.timestampMillis }
                        .map { it.timestampMillis to it.balanceAfter!! }
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

        if (byCategory.isNotEmpty()) {
            item {
                Text("This month's spending by category", style = MaterialTheme.typography.titleMedium)
                Text("Total: ${formatInr(total)}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(byCategory) { (category, amount) ->
                CategoryBarRow(category, amount, total)
                Spacer(modifier = Modifier.height(10.dp))
            }
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

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Text(source, fontWeight = FontWeight.Medium)
                    if (isManual) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("(manual)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                Text(
                    if (visible) formatInr(balance) else "₹ • • • • • •",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("as of ${dateFormat.format(Date(asOfMillis))}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHistory = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ShowChart, contentDescription = "View $source balance history", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit $source balance", tint = Color.Gray, modifier = Modifier.size(18.dp))
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$source balance history") },
        text = {
            if (history.size < 2) {
                Text(
                    "Not enough history yet to chart — this builds up automatically as more transactions are captured for this account. " +
                        "Manually-added balances don't have a history, since each edit replaces the previous figure rather than tracking it over time.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    val minY = history.minOf { it.second }
                    val maxY = history.maxOf { it.second }
                    Text(
                        if (visible) "${formatInr(maxY)}" else "₹ • • • • • •",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp).padding(vertical = 4.dp)) {
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
                            drawCircle(color = BrandGreen, radius = 4.dp.toPx(), center = pointOffset(i, y))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dateFormat.format(Date(history.first().first)), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(dateFormat.format(Date(history.last().first)), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (visible) "Lowest: ${formatInr(minY)}" else "Lowest: ₹ • • • • • •",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        "${history.size} balance points captured",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
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

@Composable
private fun CategoryBarRow(category: Category, amount: Double, total: Double) {
    val fraction = if (total > 0) (amount / total).toFloat() else 0f
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("${category.emoji} ${category.label}", style = MaterialTheme.typography.bodyMedium)
            Text("${formatInr(amount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            drawRoundRect(color = Color(0xFFE0E0E0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f))
            drawRoundRect(
                color = SemanticIndigo,
                size = size.copy(width = size.width * fraction),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f)
            )
        }
    }
}
