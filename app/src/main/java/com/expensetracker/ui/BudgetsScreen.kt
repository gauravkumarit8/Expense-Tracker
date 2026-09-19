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

// ---------- BUDGETS SCREEN ----------

@Composable
internal fun BudgetsScreen(budgetDao: BudgetDao, allTransactions: List<Transaction>) {
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
                    if (limit != null) "${formatInrWhole(spent)} / ${formatInrWhole(limit)}" else "No limit set",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray
                )
            }
            if (limit != null && limit > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                val fraction = (spent / limit).toFloat().coerceIn(0f, 1f)
                val barColor = when {
                    spent > limit -> SemanticRed
                    spent / limit > 0.7 -> Color(0xFFF57C00)
                    else -> BrandGreen
                }
                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    drawRoundRect(color = Color(0xFFE0E0E0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
                    drawRoundRect(color = barColor, size = size.copy(width = size.width * fraction), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f))
                }
                if (spent > limit) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Over budget by ${formatInr(spent - limit)}", style = MaterialTheme.typography.bodySmall, color = SemanticRed)
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
