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
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.autoexpensetracker.util.formatInr
import com.autoexpensetracker.util.formatInrWhole
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
/** Comparison basis for the "Today's spending" trend card. */
private enum class SpendComparisonPeriod(val label: String) {
    YESTERDAY("vs yesterday"),
    LAST_WEEK("vs last week"),
    LAST_MONTH("vs last month")
}

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

    // Today vs a comparable prior day — deliberately a single day-to-day
    // comparison for all three basis options (not "today vs this whole
    // week"), so the number is always answering the same question ("was
    // I up or down on a typical day") regardless of which basis is
    // selected. LAST_MONTH uses Calendar.MONTH - 1 on the same
    // day-of-month, which can land oddly for day 29-31 in a
    // shorter/longer month — an accepted rough edge for a comparison
    // feature, not a precision accounting figure.
    var comparisonPeriod by remember { mutableStateOf(SpendComparisonPeriod.YESTERDAY) }
    fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun spendOnDay(dayStartMillis: Long): Double {
        val dayEndMillis = dayStartMillis + 24L * 60 * 60 * 1000
        return allTransactions
            .filter { it.direction == Direction.SENT && it.timestampMillis in dayStartMillis until dayEndMillis }
            .sumOf { it.amount }
    }
    val todaySpend = remember(allTransactions) { spendOnDay(startOfDay(Calendar.getInstance())) }
    val comparisonSpend = remember(allTransactions, comparisonPeriod) {
        val comparisonCal = Calendar.getInstance().apply {
            when (comparisonPeriod) {
                SpendComparisonPeriod.YESTERDAY -> add(Calendar.DAY_OF_MONTH, -1)
                SpendComparisonPeriod.LAST_WEEK -> add(Calendar.DAY_OF_MONTH, -7)
                SpendComparisonPeriod.LAST_MONTH -> add(Calendar.MONTH, -1)
            }
        }
        spendOnDay(startOfDay(comparisonCal))
    }

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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
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
                TodaySpendCard(
                    todaySpend = todaySpend,
                    comparisonSpend = comparisonSpend,
                    comparisonPeriod = comparisonPeriod,
                    onComparisonPeriodChange = { comparisonPeriod = it }
                )
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
private fun TodaySpendCard(
    todaySpend: Double,
    comparisonSpend: Double,
    comparisonPeriod: SpendComparisonPeriod,
    onComparisonPeriodChange: (SpendComparisonPeriod) -> Unit
) {
    // Spending MORE than the comparison day is the "bad" direction here
    // (red, trending up) and spending LESS is "good" (green, trending
    // down) — the reverse of how up/down colors usually work for e.g. a
    // stock price, which is intentional: this is a spend tracker, not an
    // investment tracker, and less spending is the desired direction.
    val (trendIcon, trendColor, trendText) = when {
        comparisonSpend <= 0.0 && todaySpend <= 0.0 -> Triple(Icons.Filled.TrendingFlat, Color.Gray, "No spending yet")
        comparisonSpend <= 0.0 -> Triple(Icons.Filled.TrendingUp, SemanticRed, "No spend that day to compare")
        else -> {
            val percent = ((todaySpend - comparisonSpend) / comparisonSpend) * 100
            when {
                percent > 0.5 -> Triple(Icons.Filled.TrendingUp, SemanticRed, "+${"%.0f".format(percent)}% ${comparisonPeriod.label}")
                percent < -0.5 -> Triple(Icons.Filled.TrendingDown, MaterialTheme.colorScheme.primary, "${"%.0f".format(percent)}% ${comparisonPeriod.label}")
                else -> Triple(Icons.Filled.TrendingFlat, Color.Gray, "About the same ${comparisonPeriod.label}")
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Today's spending", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpendComparisonPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = period == comparisonPeriod,
                            onClick = { onComparisonPeriodChange(period) },
                            label = { Text(period.name.lowercase().replaceFirstChar { it.uppercase() }.replace("_", " "), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(formatInr(todaySpend), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(trendIcon, contentDescription = null, tint = trendColor, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(trendText, style = MaterialTheme.typography.bodySmall, color = trendColor)
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
                "${if (net >= 0) "+" else "−"}${formatInr(kotlin.math.abs(net))}",
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
            "${formatInr(amount)}",
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
                    "${formatInrWhole(spent)} / ${formatInrWhole(limit)}",
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
                    "Due day ${reminder.dueDayOfMonth}" + (reminder.amount?.let { " • ${formatInr(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Icon(Icons.Filled.Receipt, contentDescription = null, tint = SemanticGray)
        }
    }
}