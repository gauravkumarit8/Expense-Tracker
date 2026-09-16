package com.autoexpensetracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoexpensetracker.data.BudgetDao
import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Reminder
import com.autoexpensetracker.data.ReminderDao
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.ui.theme.SemanticGray
import com.autoexpensetracker.ui.theme.SemanticRed
import com.autoexpensetracker.util.MonthRange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Home dashboard — the app's new landing tab (see REQUIREMENTS.md open
 * item: "no dashboard home page"). Deliberately kept in its own file
 * rather than folded into MainActivity.kt, which is already ~2,700 lines
 * (see REQUIREMENTS.md ยง Open Items on the "one giant file" architecture
 * debt) — this at least stops that file from growing further for new
 * screens.
 *
 * Pulls together a glanceable summary rather than duplicating any single
 * tab: this month's net position, the categories closest to (or over)
 * their budget, the soonest-due reminders, and the most recent activity —
 * each section links straight to its full screen via the nav callbacks.
 *
 * Styling deliberately leans on `MaterialTheme.colorScheme` (brand green
 * from ui/theme/, not hardcoded hex) for the hero card and section
 * chrome, per the incremental-migration approach called out in
 * REQUIREMENTS.md re: the ~70 hardcoded-hex-color debt elsewhere in
 * MainActivity.kt — new screens should be theme-driven from the start
 * rather than adding to that pile. `TransactionRow` in the recent-activity
 * list is the one deliberate exception: it's reused as-is (now `internal`
 * instead of `private`) from MainActivity.kt so a transaction looks
 * identical whether seen here or on the Transactions tab.
 */
@Composable
internal fun DashboardScreen(
    budgetDao: BudgetDao,
    reminderDao: ReminderDao,
    allTransactions: List<Transaction>,
    onSeeAllTransactions: () -> Unit,
    onSeeCharts: () -> Unit,
    onSeeBudgets: () -> Unit,
    onSeeReminders: () -> Unit,
    onAddTransaction: () -> Unit
) {
    val monthRange = remember { MonthRange.current() }
    val monthTransactions = remember(allTransactions) {
        allTransactions.filter { monthRange.contains(it.timestampMillis) }
    }
    val spent = remember(monthTransactions) {
        monthTransactions.filter { it.direction == Direction.SENT }.sumOf { it.amount }
    }
    val received = remember(monthTransactions) {
        monthTransactions.filter { it.direction == Direction.RECEIVED }.sumOf { it.amount }
    }
    val net = received - spent

    val budgets by budgetDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val spendByCategory = remember(monthTransactions) {
        monthTransactions.filter { it.direction == Direction.SENT }
            .groupBy { Category.fromNameOrNull(it.category) ?: Category.OTHER }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
    }
    // Categories closest to (or already over) their limit surface first —
    // those are the ones actually worth a glance on the home screen.
    val topBudgets = remember(budgets, spendByCategory) {
        budgets.mapNotNull { b ->
            val cat = Category.fromNameOrNull(b.category) ?: return@mapNotNull null
            Triple(cat, spendByCategory[cat] ?: 0.0, b.monthlyLimit)
        }.sortedByDescending { (_, spentAmt, limit) -> if (limit > 0) spentAmt / limit else 0.0 }
            .take(3)
    }

    val reminders by reminderDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val upcomingReminders = remember(reminders) {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        reminders.sortedBy { r ->
            val diff = r.dueDayOfMonth - today
            if (diff < 0) diff + 31 else diff
        }.take(3)
    }

    val recentTransactions = remember(allTransactions) {
        allTransactions.sortedByDescending { it.timestampMillis }.take(5)
    }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }

    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(animationSpec = tween(350)) + slideInVertically(animationSpec = tween(350)) { it / 8 }
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column {
                    Text("$greeting 👋", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    Text(monthRange.label(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }

            item {
                MonthSummaryHeroCard(spent = spent, received = received, net = net, onClick = onSeeCharts)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionChip("Charts", Icons.Filled.BarChart, Modifier.weight(1f), onSeeCharts)
                    QuickActionChip("Budgets", Icons.Filled.PieChart, Modifier.weight(1f), onSeeBudgets)
                    QuickActionChip("Reminders", Icons.Filled.NotificationsActive, Modifier.weight(1f), onSeeReminders)
                }
            }

            item {
                DashboardSection(title = "Budgets to watch", onSeeAll = onSeeBudgets) {
                    if (topBudgets.isEmpty()) {
                        EmptySectionCard(
                            message = "No budgets set yet. Add one to keep spending in check.",
                            actionLabel = "Set a budget",
                            onAction = onSeeBudgets
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            topBudgets.forEach { (cat, spentAmt, limit) ->
                                BudgetGlanceRow(cat, spentAmt, limit)
                            }
                        }
                    }
                }
            }

            item {
                DashboardSection(title = "Upcoming bills", onSeeAll = onSeeReminders) {
                    if (upcomingReminders.isEmpty()) {
                        EmptySectionCard(
                            message = "No reminders yet. Add bills or subscriptions so nothing sneaks up on you.",
                            actionLabel = "Add a reminder",
                            onAction = onSeeReminders
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            upcomingReminders.forEach { reminder -> UpcomingReminderRow(reminder) }
                        }
                    }
                }
            }

            item {
                DashboardSection(title = "Recent activity", onSeeAll = onSeeAllTransactions) {
                    if (recentTransactions.isEmpty()) {
                        EmptySectionCard(
                            message = "No transactions captured yet. They'll show up here automatically.",
                            actionLabel = "Add manually",
                            onAction = onAddTransaction
                        )
                    } else {
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
                            Column {
                                recentTransactions.forEach { tx -> TransactionRow(tx, timeFormat, onClick = {}) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSummaryHeroCard(spent: Double, received: Double, net: Double, onClick: () -> Unit) {
    val gradient = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        onClick = onClick,
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(20.dp)
        ) {
            Text(
                "This month's net",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${if (net >= 0) "+" else "−"}₹${"%.2f".format(kotlin.math.abs(net))}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HeroStat(label = "Sent", amount = spent)
                HeroStat(label = "Received", amount = received)
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, amount: Double) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
        Text(
            "₹${"%.2f".format(amount)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DashboardSection(title: String, onSeeAll: () -> Unit, content: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onSeeAll) {
                Text("See all")
                Spacer(modifier = Modifier.width(2.dp))
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.animateContentSize()) {
            content()
        }
    }
}

@Composable
private fun EmptySectionCard(message: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun BudgetGlanceRow(category: Category, spent: Double, limit: Double) {
    val fraction = if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1f) else 0f
    val barColor = when {
        limit > 0 && spent > limit -> SemanticRed
        fraction > 0.7f -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${category.emoji} ${category.label}", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "₹${"%.0f".format(spent)} / ₹${"%.0f".format(limit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                drawRoundRect(color = SemanticGray.copy(alpha = 0.35f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()))
                drawRoundRect(
                    color = barColor,
                    size = size.copy(width = size.width * fraction),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun UpcomingReminderRow(reminder: Reminder) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${reminder.dueDayOfMonth}",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(reminder.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Due day ${reminder.dueDayOfMonth}" + (reminder.amount?.let { " • ₹${"%.2f".format(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Icon(Icons.Filled.Receipt, contentDescription = null, tint = SemanticGray)
        }
    }
}