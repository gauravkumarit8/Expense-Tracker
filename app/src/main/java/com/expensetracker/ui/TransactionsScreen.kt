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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.autoexpensetracker.ads.BannerAdView
import com.autoexpensetracker.ads.SearchNativeAd
import com.autoexpensetracker.pdf.PdfAccessPolicy
import com.autoexpensetracker.pdf.PdfPeriod
import com.autoexpensetracker.util.CategoryRuleActions
import com.autoexpensetracker.util.CategoryRuleStore
import com.autoexpensetracker.util.CategoryRules
import com.autoexpensetracker.util.RecentSearchStore
import com.autoexpensetracker.util.SearchLogic
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.autoexpensetracker.util.DayRange
import com.autoexpensetracker.util.MonthRange
import com.autoexpensetracker.util.MonthTotals
import com.autoexpensetracker.util.PeriodBreakdown
import com.autoexpensetracker.util.SummaryPeriod
import com.autoexpensetracker.util.SummaryPeriodStore
import com.autoexpensetracker.ads.ConsentManager
import com.autoexpensetracker.billing.BillingManager
import com.autoexpensetracker.update.AppUpdateHelper
import com.autoexpensetracker.review.ReviewHelper
import com.autoexpensetracker.review.ReviewPromptStore
import com.autoexpensetracker.ui.theme.ExpenseTrackerTheme
import com.autoexpensetracker.ui.theme.BrandGreen
import com.autoexpensetracker.ui.theme.MutedText
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
import com.autoexpensetracker.util.formatInrCompact
import com.autoexpensetracker.util.formatInrWhole
import com.autoexpensetracker.util.ManualBalanceStore
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.launch
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

// ---------- TRANSACTIONS SCREEN (home tab) ----------
//
// REQUIREMENTS.md ยง2.19/ยง2.20 (2026-09-02): this screen used to show every
// transaction ever captured, with search/filter/sort chrome permanently
// docked at the top. It now:
//   1. Scopes to the CURRENT MONTH only (see MonthRange) — "See all months"
//      opens MonthlyHistoryScreen for anything older.
//   2. Shows the banner ad at the TOP, in guaranteed space, instead of
//      competing with search/filter UI for room.
//   3. Shows a color-coded Net summary (green if received > sent, red
//      otherwise) alongside Sent/Received.
//   4. No longer owns search/filters/sort/needs-review UI at all — that all
//      moved to SearchReviewScreen (the new "Search" bottom-nav tab).

@Composable
internal fun TransactionsScreen(
    transactionDao: TransactionDao,
    allTransactions: List<Transaction>,
    notificationAccessGranted: Boolean,
    onEnableNotificationAccess: () -> Unit,
    onSeeAllMonths: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }

    // Local Sent/Received quick-filter (2026-09-03 fix) — restores the
    // tap-to-filter behavior the old always-visible StatCards had, which
    // was dropped by mistake when search/filter/sort moved to the Search
    // tab. Deliberately kept local and separate from SearchReviewScreen's
    // own directionFilter: this one only ever toggles SENT/RECEIVED/ALL
    // over the current month's data, never NEEDS_REVIEW, and resets each
    // time the screen is recomposed fresh rather than persisting — quick,
    // in-the-moment filtering, not a saved search.
    var directionFilter by rememberSaveable { mutableStateOf(DirectionFilter.ALL) }

    val currentMonth = remember { MonthRange.current() }
    val monthTransactions = remember(allTransactions, currentMonth) {
        allTransactions.filter { currentMonth.contains(it.timestampMillis) }
            .sortedByDescending { it.timestampMillis }
    }
    val displayedTransactions = remember(monthTransactions, directionFilter) {
        when (directionFilter) {
            DirectionFilter.SENT -> monthTransactions.filter { it.direction == Direction.SENT }
            DirectionFilter.RECEIVED -> monthTransactions.filter { it.direction == Direction.RECEIVED }
            else -> monthTransactions
        }
    }

    fun toggleSent() { directionFilter = if (directionFilter == DirectionFilter.SENT) DirectionFilter.ALL else DirectionFilter.SENT }
    fun toggleReceived() { directionFilter = if (directionFilter == DirectionFilter.RECEIVED) DirectionFilter.ALL else DirectionFilter.RECEIVED }

    Column(modifier = Modifier.fillMaxSize()) {

        if (!notificationAccessGranted) {
            OnboardingBanner(onEnableClick = onEnableNotificationAccess)
        }

        Column(modifier = Modifier.weight(1f)) {
            if (allTransactions.isEmpty()) {
                EmptyState(showHint = notificationAccessGranted)
            } else {
                MonthHeaderRow(monthLabel = currentMonth.label(), onSeeAllMonths = onSeeAllMonths)

                if (monthTransactions.isEmpty()) {
                    NoTransactionsThisMonthState(monthLabel = currentMonth.label(), onSeeAllMonths = onSeeAllMonths)
                } else {
                    NetSummaryCard(
                        transactions = displayedTransactions,
                        directionFilter = directionFilter,
                        onSentClick = ::toggleSent,
                        onReceivedClick = ::toggleReceived
                    )
                    if (displayedTransactions.isEmpty()) {
                        NoResultsState()
                    } else {
                        TransactionList(displayedTransactions, groupByDay = true, onRowClick = { selectedTransaction = it })
                    }
                }
            }
        }
    }

    selectedTransaction?.let { tx ->
        TransactionDetailDialog(
            transaction = tx,
            allTransactions = allTransactions,
            onDismiss = { selectedTransaction = null },
            onSave = { updated -> scope.launch { transactionDao.update(updated) }; selectedTransaction = null },
            onSaveForMerchant = { updated ->
                scope.launch {
                    transactionDao.update(updated)
                    CategoryRuleActions.applyToMerchant(context, transactionDao, tx, updated)
                }
                selectedTransaction = null
            },
            onDelete = { scope.launch { transactionDao.delete(tx.id) }; selectedTransaction = null }
        )
    }
}

@Composable
private fun MonthHeaderRow(monthLabel: String, onSeeAllMonths: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(monthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onSeeAllMonths) {
            Text("History")
            Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NoTransactionsThisMonthState(monthLabel: String, onSeeAllMonths: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(40.dp), tint = SemanticGray)
            Spacer(modifier = Modifier.height(8.dp))
            Text("No transactions in $monthLabel", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onSeeAllMonths) { Text("Browse other months") }
        }
    }
}

private fun shortDate(millis: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

// ---------- SEARCH / FILTER / SORT / NEEDS-REVIEW SCREEN ----------
//
// REQUIREMENTS.md ยง2.20 (amended 2026-09-03): everything here used to live
// permanently docked at the top of TransactionsScreen — the search bar,
// filter chips, sort menu, and NeedsReviewBanner. First moved to its own
// bottom-nav tab, then reworked into a full-screen OVERLAY (like Settings /
// MonthlyHistoryScreen) opened from a top-bar Search icon instead — a
// persistent nav tab that replaced the current month's list with a
// different destination wasn't the right shape for "search," which is
// inherently a transient action, not a place you live. Dismissing this
// overlay returns to whatever screen (Transactions, Charts, Budgets, or
// Reminders) was showing underneath, unchanged. This screen still
// searches/filters across ALL transactions (not just the current month) —
// unlike the month-scoped home screen, "search" implies the full history
// by default.

@Composable
internal fun SearchReviewScreen(
    transactionDao: TransactionDao,
    allTransactions: List<Transaction>,
    showAds: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var directionFilter by rememberSaveable { mutableStateOf(DirectionFilter.ALL) }
    var dateFilter by rememberSaveable { mutableStateOf(DateFilter.ALL_TIME) }
    var categoryFilter by rememberSaveable { mutableStateOf<Category?>(null) }
    var sortOption by rememberSaveable { mutableStateOf(SortOption.DATE_NEWEST) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    // "Browse all transactions" from the idle state: shows the whole list
    // even with no query and no filter.
    var browseAll by rememberSaveable { mutableStateOf(false) }
    var recentSearches by remember { mutableStateOf(RecentSearchStore.getAll(context)) }

    // The keyboard does NOT open by itself. It used to, and it covered the
    // recent searches, the quick filters and the sponsored card the moment the
    // screen opened; the keyboard now appears when the user taps the field.

    fun rememberCurrentQuery() {
        if (SearchLogic.normalizeQuery(searchQuery).length >= 2) {
            recentSearches = RecentSearchStore.add(context, searchQuery)
        }
    }

    val activeFilterCount = (if (directionFilter != DirectionFilter.ALL) 1 else 0) +
        (if (dateFilter != DateFilter.ALL_TIME) 1 else 0) +
        (if (categoryFilter != null) 1 else 0)

    val filtered = remember(allTransactions, searchQuery, directionFilter, dateFilter, categoryFilter, customFrom, customTo, sortOption) {
        val base = filterTransactions(allTransactions, searchQuery, directionFilter, dateFilter, categoryFilter, customFrom, customTo)
        when (sortOption) {
            SortOption.DATE_NEWEST -> base.sortedByDescending { it.timestampMillis }
            SortOption.DATE_OLDEST -> base.sortedBy { it.timestampMillis }
            SortOption.AMOUNT_HIGH -> base.sortedByDescending { it.amount }
            SortOption.AMOUNT_LOW -> base.sortedBy { it.amount }
        }
    }

    fun clearDirection() { directionFilter = DirectionFilter.ALL }
    fun clearDate() { dateFilter = DateFilter.ALL_TIME; customFrom = null; customTo = null }
    fun clearCategory() { categoryFilter = null }
    fun clearAllFilters() { clearDirection(); clearDate(); clearCategory() }

    val needsReviewCount = remember(allTransactions) { allTransactions.count { it.needsReview } }
    val isIdle = searchQuery.isBlank() && activeFilterCount == 0 && !browseAll
    // Biggest spend categories this month, offered as quick filters.
    val topCategories = remember(allTransactions) {
        val month = MonthRange.current()
        SearchLogic.topCategoryNames(
            allTransactions
                .filter { it.direction == Direction.SENT && month.contains(it.timestampMillis) }
                .map { it.category to it.amount },
            3
        ).mapNotNull { Category.fromNameOrNull(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (needsReviewCount > 0 && directionFilter != DirectionFilter.NEEDS_REVIEW) {
            NeedsReviewBanner(count = needsReviewCount, onClick = { directionFilter = DirectionFilter.NEEDS_REVIEW })
        }

        if (allTransactions.isEmpty()) {
            EmptyState(showHint = false)
        } else {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { rememberCurrentQuery(); keyboard?.hide() }
            )

            if (isIdle) {
                SearchIdleState(
                    recent = recentSearches,
                    topCategories = topCategories,
                    totalCount = allTransactions.size,
                    onRecentClick = { searchQuery = it },
                    onRemoveRecent = { recentSearches = RecentSearchStore.remove(context, it) },
                    onClearRecent = { RecentSearchStore.clearAll(context); recentSearches = emptyList() },
                    onQuickSent = { directionFilter = DirectionFilter.SENT },
                    onQuickReceived = { directionFilter = DirectionFilter.RECEIVED },
                    onQuickToday = { dateFilter = DateFilter.TODAY },
                    onQuickWeek = { dateFilter = DateFilter.THIS_WEEK },
                    onQuickMonth = { dateFilter = DateFilter.THIS_MONTH },
                    onQuickCategory = { categoryFilter = it },
                    onBrowseAll = { browseAll = true },
                    adSlot = if (showAds) ({ SearchNativeAd() }) else null
                )
            } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { showFilterSheet = true }) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filters")
                }
                SortMenu(sortOption) { sortOption = it }
            }

            if (activeFilterCount > 0) {
                ActiveFiltersRow(
                    directionFilter = directionFilter, onClearDirection = ::clearDirection,
                    dateFilter = dateFilter, customFrom = customFrom, customTo = customTo, onClearDate = ::clearDate,
                    categoryFilter = categoryFilter, onClearCategory = ::clearCategory,
                    onClearAll = ::clearAllFilters
                )
            }

            if (filtered.isEmpty()) {
                NoResultsState()
            } else {
                Text(
                    if (filtered.size == 1) "1 result" else "${filtered.size} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                SummaryHeader(
                    transactions = filtered,
                    directionFilter = directionFilter,
                    onSentClick = { directionFilter = if (directionFilter == DirectionFilter.SENT) DirectionFilter.ALL else DirectionFilter.SENT },
                    onReceivedClick = { directionFilter = if (directionFilter == DirectionFilter.RECEIVED) DirectionFilter.ALL else DirectionFilter.RECEIVED }
                )
                // The list takes the space above the banner (weight), so the banner is
                // always visible under it instead of being pushed off screen.
                Box(modifier = Modifier.weight(1f)) {
                    TransactionList(
                        filtered,
                        groupByDay = sortOption == SortOption.DATE_NEWEST || sortOption == SortOption.DATE_OLDEST,
                        onRowClick = { rememberCurrentQuery(); selectedTransaction = it }
                    )
                }
                // A banner only while there is a real list of results on screen: never on the
                // idle screen (it has its own sponsored card) and never on "no results".
                if (showAds) BannerAdView()
            }
            }
        }
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            directionFilter = directionFilter, onDirectionChange = { directionFilter = it },
            dateFilter = dateFilter, customFrom = customFrom, customTo = customTo,
            onDateChange = { dateFilter = it; if (it != DateFilter.CUSTOM) { customFrom = null; customTo = null } },
            onPickCustomRange = { showDateRangeDialog = true },
            categoryFilter = categoryFilter, onCategoryChange = { categoryFilter = it },
            onClearAll = ::clearAllFilters,
            onDismiss = { showFilterSheet = false }
        )
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
            allTransactions = allTransactions,
            onDismiss = { selectedTransaction = null },
            onSave = { updated -> scope.launch { transactionDao.update(updated) }; selectedTransaction = null },
            onSaveForMerchant = { updated ->
                scope.launch {
                    transactionDao.update(updated)
                    CategoryRuleActions.applyToMerchant(context, transactionDao, tx, updated)
                }
                selectedTransaction = null
            },
            onDelete = { scope.launch { transactionDao.delete(tx.id) }; selectedTransaction = null }
        )
    }
}

// ---------- MONTHLY HISTORY SCREEN ----------
//
// REQUIREMENTS.md ยง2.19 (2026-09-02): reached via "See all months" on the
// home Transactions tab. Lets the user step through or pick any month and
// see that month's transactions, with a Month-vs-Year toggle controlling
// whether the summary totals are scoped to just that month or its whole
// calendar year (list always shows the selected month's transactions
// either way — the toggle only changes what the summary figures cover).

@Composable
internal fun MonthlyHistoryScreen(
    transactionDao: TransactionDao,
    allTransactions: List<Transaction>,
    isPro: Boolean,
    onExportPdf: (PdfPeriod) -> Unit,
    onExportCsv: (PdfPeriod) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedMonth by rememberSaveable { mutableStateOf(MonthRange.current()) }
    var selectedDay by rememberSaveable { mutableStateOf(DayRange.today()) }
    // The user's SAVED preference for which view opens (Day / Month / Year).
    // Same SharedPreferences pattern as before; the old two-way Month/Year
    // toggle stored "MONTH"/"YEAR", which are still valid values.
    var period by remember { mutableStateOf(SummaryPeriodStore.get(context)) }
    var showMonthYearDialog by remember { mutableStateOf(false) }
    var showDayDialog by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    // Same tap-to-filter as TransactionsScreen (2026-09-03) — kept
    // consistent across both screens since they share NetSummaryCard.
    var directionFilter by rememberSaveable { mutableStateOf(DirectionFilter.ALL) }

    // Called ONLY when the user taps the Day/Month/Year selector, so it is
    // the only thing that changes the saved preference. Drilling from the
    // Year table into a month changes `period` directly, without saving —
    // otherwise looking at one month would silently overwrite a "Year" preference.
    fun setPeriod(p: SummaryPeriod) {
        period = p
        SummaryPeriodStore.set(context, p)
    }
    fun toggleSent() { directionFilter = if (directionFilter == DirectionFilter.SENT) DirectionFilter.ALL else DirectionFilter.SENT }
    fun toggleReceived() { directionFilter = if (directionFilter == DirectionFilter.RECEIVED) DirectionFilter.ALL else DirectionFilter.RECEIVED }

    // Step the year while keeping the month, never landing in the future.
    fun shiftYear(delta: Int) {
        val moved = MonthRange(selectedMonth.year + delta, selectedMonth.month)
        selectedMonth = if (moved.isFuture()) MonthRange.current() else moved
    }

    fun applyDirectionFilter(list: List<Transaction>): List<Transaction> = when (directionFilter) {
        DirectionFilter.SENT -> list.filter { it.direction == Direction.SENT }
        DirectionFilter.RECEIVED -> list.filter { it.direction == Direction.RECEIVED }
        else -> list
    }

    val monthTransactionsUnfiltered = remember(allTransactions, selectedMonth) {
        allTransactions.filter { selectedMonth.contains(it.timestampMillis) }
            .sortedByDescending { it.timestampMillis }
    }
    val monthTransactions = remember(monthTransactionsUnfiltered, directionFilter) {
        applyDirectionFilter(monthTransactionsUnfiltered)
    }
    val dayTransactionsUnfiltered = remember(allTransactions, selectedDay) {
        allTransactions.filter { selectedDay.contains(it.timestampMillis) }
            .sortedByDescending { it.timestampMillis }
    }
    val dayTransactions = remember(dayTransactionsUnfiltered, directionFilter) {
        applyDirectionFilter(dayTransactionsUnfiltered)
    }
    val yearBreakdown = remember(allTransactions, selectedMonth.year) {
        PeriodBreakdown.forYear(allTransactions, selectedMonth.year)
    }

    // What the PDF button would export right now: the day, month or year the
    // screen is showing. Older months and every full year are Pro; the lock
    // shows for a free user on those (PdfAccessPolicy).
    val pdfPeriod: PdfPeriod = when (period) {
        SummaryPeriod.DAY -> PdfPeriod.Day(selectedDay.year, selectedDay.month, selectedDay.day)
        SummaryPeriod.MONTH -> PdfPeriod.Month(selectedMonth.year, selectedMonth.month)
        SummaryPeriod.YEAR -> PdfPeriod.Year(selectedMonth.year)
    }
    val pdfLocked = !isPro && !PdfAccessPolicy.isFree(pdfPeriod, System.currentTimeMillis())

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentedPeriodToggle(period = period, onPeriodChange = ::setPeriod)
            Spacer(modifier = Modifier.width(8.dp))
            ExportMenuButton(
                pdfLocked = pdfLocked,
                csvLocked = !isPro,
                onPdf = { onExportPdf(pdfPeriod) },
                onCsv = { onExportCsv(pdfPeriod) }
            )
        }

        when (period) {
            SummaryPeriod.DAY -> {
                PeriodStepper(
                    label = selectedDay.label(),
                    previousDescription = "Previous day",
                    nextDescription = "Next day",
                    canGoNext = !selectedDay.isCurrentOrFuture(),
                    onPrevious = { selectedDay = selectedDay.previous() },
                    onNext = { selectedDay = selectedDay.next() },
                    onLabelClick = { showDayDialog = true }
                )
                if (dayTransactionsUnfiltered.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No transactions on ${selectedDay.label()}", color = MutedText)
                    }
                } else {
                    NetSummaryCard(
                        transactions = dayTransactions,
                        directionFilter = directionFilter,
                        onSentClick = ::toggleSent,
                        onReceivedClick = ::toggleReceived
                    )
                    if (dayTransactions.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No transactions match this filter", color = MutedText)
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            // One day only, so the day header would just repeat the stepper above.
                            TransactionList(dayTransactions, groupByDay = false, onRowClick = { selectedTransaction = it })
                        }
                    }
                }
            }

            SummaryPeriod.MONTH -> {
                PeriodStepper(
                    label = selectedMonth.label(),
                    previousDescription = "Previous month",
                    nextDescription = "Next month",
                    canGoNext = !selectedMonth.isCurrentOrFuture(),
                    onPrevious = { selectedMonth = selectedMonth.previous() },
                    onNext = { selectedMonth = selectedMonth.next() },
                    onLabelClick = { showMonthYearDialog = true }
                )
                if (monthTransactionsUnfiltered.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No transactions in ${selectedMonth.label()}", color = MutedText)
                    }
                } else {
                    NetSummaryCard(
                        transactions = monthTransactions,
                        directionFilter = directionFilter,
                        onSentClick = ::toggleSent,
                        onReceivedClick = ::toggleReceived
                    )
                    if (monthTransactions.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No transactions match this filter", color = MutedText)
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f)) {
                            TransactionList(monthTransactions, groupByDay = true, onRowClick = { selectedTransaction = it })
                        }
                    }
                }
            }

            SummaryPeriod.YEAR -> {
                PeriodStepper(
                    label = selectedMonth.yearLabel(),
                    previousDescription = "Previous year",
                    nextDescription = "Next year",
                    canGoNext = selectedMonth.year < MonthRange.current().year,
                    onPrevious = { shiftYear(-1) },
                    onNext = { shiftYear(1) },
                    onLabelClick = null
                )
                if (yearBreakdown.all { it.count == 0 }) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No transactions in ${selectedMonth.yearLabel()}", color = MutedText)
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        YearBreakdown(
                            rows = yearBreakdown,
                            // Drill down WITHOUT saving: see setPeriod() above.
                            onMonthClick = { month -> selectedMonth = month; period = SummaryPeriod.MONTH }
                        )
                    }
                }
            }
        }
    }

    if (showMonthYearDialog) {
        MonthYearPickerDialog(
            initial = selectedMonth,
            onDismiss = { showMonthYearDialog = false },
            onPick = { picked -> selectedMonth = picked; showMonthYearDialog = false }
        )
    }

    if (showDayDialog) {
        DayPickerDialog(
            initial = selectedDay,
            onDismiss = { showDayDialog = false },
            onPick = { picked -> selectedDay = picked; showDayDialog = false }
        )
    }

    selectedTransaction?.let { tx ->
        TransactionDetailDialog(
            transaction = tx,
            allTransactions = allTransactions,
            onDismiss = { selectedTransaction = null },
            onSave = { updated -> scope.launch { transactionDao.update(updated) }; selectedTransaction = null },
            onSaveForMerchant = { updated ->
                scope.launch {
                    transactionDao.update(updated)
                    CategoryRuleActions.applyToMerchant(context, transactionDao, tx, updated)
                }
                selectedTransaction = null
            },
            onDelete = { scope.launch { transactionDao.delete(tx.id) }; selectedTransaction = null }
        )
    }
}

/** Previous / label / next row shared by the Day, Month and Year views. */
@Composable
private fun PeriodStepper(
    label: String,
    previousDescription: String,
    nextDescription: String,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLabelClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = previousDescription)
        }
        if (onLabelClick != null) {
            TextButton(onClick = onLabelClick) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = nextDescription)
        }
    }
}

// ---------- YEAR BREAKDOWN ----------
//
// The Jan-to-Dec table: received / sent / net per month, with the year's
// total pinned right under the column headings (so it is visible without
// scrolling past twelve rows). Tapping a month with data opens that month.
// Whole rupees, not paise: four money columns on a phone need the room, and
// paise add nothing to a year-at-a-glance view. For the CURRENT year, months
// after today are omitted rather than shown as a column of dashes.

@Composable
private fun YearBreakdown(rows: List<MonthTotals>, onMonthClick: (MonthRange) -> Unit) {
    val visible = remember(rows) { rows.filter { !it.month.isFuture() } }
    val totalReceived = remember(visible) { visible.fold(0.0) { acc, r -> acc + r.received } }
    val totalSent = remember(visible) { visible.fold(0.0) { acc, r -> acc + r.sent } }
    val maxSent = remember(visible) { visible.map { it.sent }.maxOrNull() ?: 0.0 }
    // Locale-based month names that don't depend on a cached date formatter's time zone.
    val shortMonths = remember { java.text.DateFormatSymbols.getInstance(Locale.getDefault()).shortMonths }

    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            YearRow(
                monthLabel = "Month", received = "Received", sent = "Sent", net = "Net",
                receivedColor = MutedText, sentColor = MutedText, netColor = MutedText,
                bold = false, muted = true, sentFraction = 0f, background = Color.Transparent
            )
        }
        item {
            val totalNet = totalReceived - totalSent
            YearRow(
                monthLabel = "Total",
                received = formatInrWhole(totalReceived),
                sent = formatInrWhole(totalSent),
                net = signedWhole(totalNet),
                receivedColor = BrandGreen, sentColor = SemanticRed, netColor = netColorFor(totalNet),
                bold = true, muted = false, sentFraction = 0f, background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
        }
        items(visible) { row ->
            val has = row.count > 0
            Surface(onClick = { onMonthClick(row.month) }, enabled = has, color = Color.Transparent) {
                YearRow(
                    monthLabel = shortMonths[row.month.month],
                    received = if (has) formatInrWhole(row.received) else "—",
                    sent = if (has) formatInrWhole(row.sent) else "—",
                    net = if (has) signedWhole(row.net) else "—",
                    receivedColor = if (has) BrandGreen else MutedText,
                    sentColor = if (has) SemanticRed else MutedText,
                    netColor = if (has) netColorFor(row.net) else MutedText,
                    bold = false, muted = !has,
                    sentFraction = if (has && maxSent > 0.0) (row.sent / maxSent).toFloat() else 0f,
                    background = Color.Transparent
                )
            }
        }
    }
}

private fun signedWhole(net: Double): String =
    "${if (net >= 0) "+" else "−"}${formatInrWhole(kotlin.math.abs(net))}"

@Composable
private fun netColorFor(net: Double): Color = when {
    net > 0.0 -> BrandGreen
    net < 0.0 -> SemanticRed
    else -> MutedText
}

@Composable
private fun YearRow(
    monthLabel: String,
    received: String,
    sent: String,
    net: String,
    receivedColor: Color,
    sentColor: Color,
    netColor: Color,
    bold: Boolean,
    muted: Boolean,
    sentFraction: Float,
    background: Color
) {
    val weight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                monthLabel, modifier = Modifier.weight(0.8f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold || !muted) FontWeight.SemiBold else FontWeight.Normal,
                color = if (muted) MutedText else Color.Unspecified
            )
            YearCell(received, receivedColor, weight, Modifier.weight(1.1f))
            YearCell(sent, sentColor, weight, Modifier.weight(1.1f))
            YearCell(net, netColor, weight, Modifier.weight(1.1f))
        }
        if (sentFraction > 0f) {
            // Thin bar: this month's spending relative to the biggest month of the year.
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(sentFraction.coerceIn(0.02f, 1f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SemanticRed.copy(alpha = 0.35f))
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
    }
}

@Composable
private fun YearCell(text: String, color: Color, weight: FontWeight, modifier: Modifier) {
    Text(
        text, modifier = modifier, color = color,
        style = MaterialTheme.typography.bodySmall, fontWeight = weight,
        textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SegmentedPeriodToggle(period: SummaryPeriod, onPeriodChange: (SummaryPeriod) -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            SummaryPeriod.DAY to "Day",
            SummaryPeriod.MONTH to "Month",
            SummaryPeriod.YEAR to "Year"
        ).forEach { (p, label) ->
            val selected = period == p
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                onClick = { onPeriodChange(p) }
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MutedText
                )
            }
        }
    }
}

@Composable
private fun DayPickerDialog(initial: DayRange, onDismiss: () -> Unit, onPick: (DayRange) -> Unit) {
    // DatePicker speaks UTC-midnight millis for the calendar date; DayRange
    // converts both ways through UTC so the picked date is exact in every time
    // zone (reading it with the local zone is off by a day west of UTC — the
    // logic tests cover this).
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toUtcPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis
                if (picked == null) {
                    onDismiss()
                } else {
                    val day = DayRange.fromUtcPickerMillis(picked)
                    // A future day can't have transactions; land on today instead.
                    onPick(if (day.isFuture()) DayRange.today() else day)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@Composable
private fun MonthYearPickerDialog(initial: MonthRange, onDismiss: () -> Unit, onPick: (MonthRange) -> Unit) {
    var year by remember { mutableStateOf(initial.year) }
    // DAY_OF_MONTH is pinned to 1 before setting MONTH — otherwise, e.g. on
    // the 31st, setting MONTH to February (28/29 days) rolls the date over
    // into March once normalized, and the formatted label would silently
    // shift to the wrong month.
    val monthNames = remember {
        (0..11).map { m ->
            SimpleDateFormat("MMM", Locale.getDefault()).format(
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.MONTH, m)
                }.time
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a month") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { year-- }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous year") }
                    Text("$year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { year++ }, enabled = year < MonthRange.current().year) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next year")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    monthNames.forEachIndexed { index, name ->
                        val candidate = MonthRange(year, index)
                        FilterChip(
                            selected = candidate == initial,
                            enabled = !candidate.isFuture(),
                            onClick = { onPick(candidate) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * One Export button for the period on screen, opening a small menu: PDF
 * report or CSV spreadsheet. A lock marks what needs Pro (a CSV can also be
 * unlocked once a month with a rewarded ad - the dialog that follows says so).
 */
@Composable
private fun ExportMenuButton(pdfLocked: Boolean, csvLocked: Boolean, onPdf: () -> Unit, onCsv: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.FileDownload, contentDescription = "Export this period")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (pdfLocked) "PDF report (Pro)" else "PDF report") },
                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                trailingIcon = if (pdfLocked) ({ Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                onClick = { menuOpen = false; onPdf() }
            )
            DropdownMenuItem(
                text = { Text(if (csvLocked) "CSV spreadsheet (Pro)" else "CSV spreadsheet") },
                leadingIcon = { Icon(Icons.Filled.TableChart, contentDescription = null) },
                trailingIcon = if (csvLocked) ({ Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                onClick = { menuOpen = false; onCsv() }
            )
        }
    }
}

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

    val q = query.trim()
    // "340", "₹340", "Rs. 1,500", "1,50,000" also search by EXACT amount.
    val amountQuery = SearchLogic.parseAmountQuery(q)

    return transactions.filter { tx ->
        val matchesQuery = q.isEmpty() ||
            (tx.merchantOrContact?.contains(q, ignoreCase = true) == true) ||
            tx.bankOrSource.contains(q, ignoreCase = true) ||
            (tx.note?.contains(q, ignoreCase = true) == true) ||
            (tx.tags?.contains(q, ignoreCase = true) == true) ||
            (amountQuery != null && SearchLogic.amountMatches(tx.amount, amountQuery)) ||
            SearchLogic.categoryMatches(q, tx.category, Category.fromNameOrNull(tx.category)?.label)

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
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange,
        placeholder = { Text("Merchant, category, amount", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
private fun ActiveFiltersRow(
    directionFilter: DirectionFilter, onClearDirection: () -> Unit,
    dateFilter: DateFilter, customFrom: Long?, customTo: Long?, onClearDate: () -> Unit,
    categoryFilter: Category?, onClearCategory: () -> Unit,
    onClearAll: () -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        if (directionFilter != DirectionFilter.ALL) {
            item { RemovableChip(directionFilter.label, onClearDirection) }
        }
        if (dateFilter != DateFilter.ALL_TIME) {
            val label = if (dateFilter == DateFilter.CUSTOM && customFrom != null && customTo != null)
                "${shortDate(customFrom)} - ${shortDate(customTo)}" else dateFilter.label
            item { RemovableChip(label, onClearDate) }
        }
        if (categoryFilter != null) {
            item { RemovableChip("${categoryFilter.emoji} ${categoryFilter.label}", onClearCategory) }
        }
        item {
            AssistChip(
                onClick = onClearAll, label = { Text("Clear all") },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true, onClick = onRemove,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove filter", modifier = Modifier.size(16.dp)) }
    )
}

@Composable
private fun FilterBottomSheet(
    directionFilter: DirectionFilter, onDirectionChange: (DirectionFilter) -> Unit,
    dateFilter: DateFilter, customFrom: Long?, customTo: Long?,
    onDateChange: (DateFilter) -> Unit, onPickCustomRange: () -> Unit,
    categoryFilter: Category?, onCategoryChange: (Category?) -> Unit,
    onClearAll: () -> Unit, onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Type", style = MaterialTheme.typography.labelLarge, color = MutedText)
            Spacer(modifier = Modifier.height(6.dp))
            FlowChipsRow(DirectionFilter.entries, directionFilter, { it.label }, onDirectionChange)

            Spacer(modifier = Modifier.height(16.dp))
            Text("Date", style = MaterialTheme.typography.labelLarge, color = MutedText)
            Spacer(modifier = Modifier.height(6.dp))
            FlowChipsRow(DateFilter.entries, dateFilter, { it.label }) {
                if (it == DateFilter.CUSTOM) onPickCustomRange() else onDateChange(it)
            }
            if (dateFilter == DateFilter.CUSTOM && customFrom != null && customTo != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("${shortDate(customFrom)} - ${shortDate(customTo)}", style = MaterialTheme.typography.bodySmall, color = MutedText)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Category", style = MaterialTheme.typography.labelLarge, color = MutedText)
            Spacer(modifier = Modifier.height(6.dp))
            FlowChipsRow(
                listOf<Category?>(null) + Category.entries,
                categoryFilter,
                { it?.let { c -> "${c.emoji} ${c.label}" } ?: "All categories" },
                onCategoryChange
            )

            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Show results") }
        }
    }
}

@Composable
private fun <T> FlowChipsRow(options: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(labelOf(option)) })
        }
    }
}

@Composable
private fun NeedsReviewBanner(count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp), color = SemanticOrange.copy(alpha = 0.12f), onClick = onClick
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PriorityHigh, contentDescription = null, tint = SemanticOrange)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 transaction needs review" else "$count transactions need review",
                    fontWeight = FontWeight.Medium
                )
                Text("Tap to fix amounts or directions the parser wasn't sure about", style = MaterialTheme.typography.bodySmall, color = SemanticBrown)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = SemanticOrange)
        }
    }
}

@Composable
private fun OnboardingBanner(onEnableClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = SemanticOrange.copy(alpha = 0.12f)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = SemanticOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Notification access needed", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Expense Tracker reads bank/UPI alerts from your notifications to auto-log transactions. Nothing leaves your device.",
                style = MaterialTheme.typography.bodySmall, color = SemanticBrown
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onEnableClick, colors = ButtonDefaults.buttonColors(containerColor = SemanticOrange)) { Text("Enable in Settings") }
        }
    }
}

@Composable
private fun EmptyState(showHint: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(48.dp), tint = SemanticGray)
            Spacer(modifier = Modifier.height(12.dp))
            Text("No transactions yet", style = MaterialTheme.typography.titleMedium)
            if (showHint) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "New bank/UPI alerts will show up here automatically. Tap + to add a cash expense.",
                    style = MaterialTheme.typography.bodySmall, color = MutedText,
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
            Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = SemanticGray)
            Spacer(modifier = Modifier.height(8.dp))
            Text("No matching transactions", style = MaterialTheme.typography.titleSmall)
            Text("Try a different search or filter.", style = MaterialTheme.typography.bodySmall, color = MutedText)
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
        StatCard("Sent", sent, SemanticRed, directionFilter == DirectionFilter.SENT, Modifier.weight(1f), onSentClick)
        StatCard("Received", received, BrandGreen, directionFilter == DirectionFilter.RECEIVED, Modifier.weight(1f), onReceivedClick)
    }
}

@Composable
private fun StatCard(label: String, amount: Double, color: Color, isActive: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = if (isActive) color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FitAmountText(
                full = formatInr(amount),
                compact = formatInrCompact(amount),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Net (received − sent) summary, green when ≥ 0 and red when negative,
 * shown in a single row alongside Sent/Received (2026-09-03: previously
 * Net was its own full-width card with Sent/Received in a second row
 * below — collapsed to one row per feedback).
 *
 * Sent/Received are tap-to-filter, mirroring the old always-visible
 * StatCards' behavior (2026-09-03 fix — this was accidentally dropped when
 * search/filter/sort moved to the Search tab). When a filter is active,
 * [transactions] is expected to already be pre-filtered by the caller (see
 * TransactionsScreen/MonthlyHistoryScreen), so the Net/Sent/Received
 * figures shown here reflect exactly what's currently filtered — e.g.
 * tapping "Sent" shows Received as ₹0.00, matching how the equivalent
 * filter has always behaved on SearchReviewScreen.
 */
@Composable
private fun NetSummaryCard(
    transactions: List<Transaction>,
    directionFilter: DirectionFilter = DirectionFilter.ALL,
    onSentClick: () -> Unit = {},
    onReceivedClick: () -> Unit = {}
) {
    val sent = remember(transactions) { transactions.filter { it.direction == Direction.SENT }.sumOf { it.amount } }
    val received = remember(transactions) { transactions.filter { it.direction == Direction.RECEIVED }.sumOf { it.amount } }
    val net = received - sent
    val netColor = if (net >= 0) BrandGreen else SemanticRed // green-800 / red-800
    val netSign = if (net >= 0) "+" else "−"
    val netFull = netSign + formatInr(kotlin.math.abs(net))
    val netCompact = netSign + formatInrCompact(kotlin.math.abs(net))

    // Net, Sent and Received share one row while their FULL amounts fit; when they
    // would not (about one lakh and up on a narrow phone), Net takes a row of its own
    // and Sent / Received share the one below, so no amount is ever cut off.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val rowStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        // 16dp screen padding each side, 8dp between the three cards, 12dp padding inside each.
        val unitPx = with(density) { (maxWidth - 32.dp - 16.dp).toPx() } / 3.15f
        val innerPadPx = with(density) { 24.dp.toPx() }
        fun fits(text: String, units: Float): Boolean =
            measurer.measure(text = text, style = rowStyle, maxLines = 1, softWrap = false).size.width <= units * unitPx - innerPadPx
        val singleRow = fits(netFull, 1.15f) && fits(formatInr(sent), 1f) && fits(formatInr(received), 1f)

        @Composable
        fun NetCard(modifier: Modifier) {
            Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = netColor.copy(alpha = 0.12f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Net", style = MaterialTheme.typography.labelMedium, color = MutedText)
                    FitAmountText(
                        full = netFull,
                        compact = netCompact,
                        style = MaterialTheme.typography.titleMedium,
                        color = netColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (singleRow) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NetCard(Modifier.weight(1.15f))
                StatCard("Sent", sent, SemanticRed, directionFilter == DirectionFilter.SENT, Modifier.weight(1f), onSentClick)
                StatCard("Received", received, BrandGreen, directionFilter == DirectionFilter.RECEIVED, Modifier.weight(1f), onReceivedClick)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NetCard(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Sent", sent, SemanticRed, directionFilter == DirectionFilter.SENT, Modifier.weight(1f), onSentClick)
                    StatCard("Received", received, BrandGreen, directionFilter == DirectionFilter.RECEIVED, Modifier.weight(1f), onReceivedClick)
                }
            }
        }
    }
}

@Composable
private fun TransactionList(transactions: List<Transaction>, groupByDay: Boolean, onRowClick: (Transaction) -> Unit) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    if (!groupByDay) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp, top = 8.dp)) {
            items(transactions, key = { it.id }) { tx -> TransactionRow(tx, timeFormat, onClick = { onRowClick(tx) }) }
        }
        return
    }

    val dayFormat = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    // Group by the FULL date, not the display label. "Mon, 5 Oct" carries no
    // year, so in an all-time list two different years' 5 Oct (same weekday
    // every 5-6-11 years) used to share one header — harmless when the header
    // was just a label, but wrong now that each header also shows a net total.
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val grouped = remember(transactions) { transactions.groupBy { dayKeyFormat.format(Date(it.timestampMillis)) } }

    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        grouped.forEach { (_, dayTransactions) ->
            item {
                DayHeader(
                    label = dayFormat.format(Date(dayTransactions.first().timestampMillis)),
                    // received - sent over the transactions shown for that day
                    // (so it follows any active filter/search, like the rows do)
                    net = dayTransactions.sumOf {
                        when (it.direction) {
                            Direction.RECEIVED -> it.amount
                            Direction.SENT -> -it.amount
                            else -> 0.0
                        }
                    }
                )
            }
            items(dayTransactions, key = { it.id }) { tx -> TransactionRow(tx, timeFormat, onClick = { onRowClick(tx) }) }
        }
    }
}

@Composable
private fun DayHeader(label: String, net: Double) {
    val netColor = when {
        net > 0.0 -> BrandGreen
        net < 0.0 -> SemanticRed
        else -> MutedText
    }
    Row(
        // end = 28.dp lines the net up with the amount column of the rows below
        // (16.dp row margin + 12.dp row padding).
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 28.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MutedText)
        Text(
            "${if (net >= 0) "+" else "−"}${formatInr(kotlin.math.abs(net))}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = netColor
        )
    }
}

@Composable
internal fun TransactionRow(tx: Transaction, timeFormat: SimpleDateFormat, onClick: () -> Unit) {
    val isSent = tx.direction == Direction.SENT
    val iconBg = when { tx.needsReview -> SemanticOrange.copy(alpha = 0.12f); isSent -> SemanticRed.copy(alpha = 0.12f); else -> BrandGreen.copy(alpha = 0.12f) }
    val iconTint = when { tx.needsReview -> SemanticOrange; isSent -> SemanticRed; else -> BrandGreen }
    val icon = when { tx.needsReview -> Icons.Filled.PriorityHigh; isSent -> Icons.Filled.ArrowUpward; else -> Icons.Filled.ArrowDownward }
    val category = Category.fromNameOrNull(tx.category)

    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, onClick = onClick) {
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
                    style = MaterialTheme.typography.bodySmall, color = if (tx.needsReview) SemanticOrange else MutedText
                )
                if (!tx.note.isNullOrBlank()) Text("📝 ${tx.note}", style = MaterialTheme.typography.bodySmall, color = MutedText, maxLines = 1)
            }
            Text("${if (isSent) "-" else "+"}${formatInr(tx.amount)}", fontWeight = FontWeight.SemiBold, color = if (isSent) SemanticRed else BrandGreen)
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
private fun TransactionDetailDialog(
    transaction: Transaction,
    allTransactions: List<Transaction>,
    onDismiss: () -> Unit,
    onSave: (Transaction) -> Unit,
    onSaveForMerchant: (Transaction) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // Set when a category change should be offered to every transaction from the same merchant.
    var pendingRule by remember { mutableStateOf<Transaction?>(null) }
    val isManual = transaction.bankOrSource == "Cash"
    // Needs-review transactions get full editing too (amount/direction/merchant),
    // since that's exactly the case where the parser's guess needs correcting —
    // unlike a confidently-parsed bank message, where amount/direction should
    // stay tied to what the bank actually reported. See REQUIREMENTS.md ยง2.14.
    val fullyEditable = isManual || transaction.needsReview

    var amountText by remember { mutableStateOf(if (fullyEditable) "%.2f".format(transaction.amount) else "") }
    var direction by remember { mutableStateOf(transaction.direction) }
    var merchant by remember { mutableStateOf(transaction.merchantOrContact.orEmpty()) }
    var category by remember { mutableStateOf(Category.fromNameOrNull(transaction.category) ?: Category.OTHER) }
    var note by remember { mutableStateOf(transaction.note.orEmpty()) }
    var tags by remember { mutableStateOf(transaction.tags.orEmpty()) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = SemanticRed) } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
        return
    }

    pendingRule?.let { updated ->
        val newCategory = Category.fromNameOrNull(updated.category) ?: Category.OTHER
        val keys = listOfNotNull(
            CategoryRules.keyFor(updated.merchantOrContact),
            CategoryRules.keyFor(transaction.merchantOrContact)
        ).toSet()
        val others = CategoryRules.idsToChange(allTransactions, keys, newCategory.name, transaction.id).size
        AlertDialog(
            // Tapping outside goes back to editing rather than saving either way.
            onDismissRequest = { pendingRule = null },
            title = { Text("Always use ${newCategory.emoji} ${newCategory.label}?") },
            text = {
                Text(
                    (if (others > 0) {
                        "$others other transaction${if (others == 1) "" else "s"} from ${updated.merchantOrContact} will change too, and future ones will use it automatically."
                    } else {
                        "Future transactions from ${updated.merchantOrContact} will use it automatically."
                    }) + " You can undo this in Settings > Category rules."
                )
            },
            confirmButton = { TextButton(onClick = { onSaveForMerchant(updated) }) { Text("Yes, all") } },
            dismissButton = { TextButton(onClick = { onSave(updated) }) { Text("Just this one") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(transaction.merchantOrContact ?: "Unknown") },
        text = {
            Column {
                if (transaction.needsReview) {
                    Surface(shape = RoundedCornerShape(8.dp), color = SemanticOrange.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.PriorityHigh, contentDescription = null, tint = SemanticOrange, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "The amount or direction couldn't be confidently parsed. Fix it below or save as-is to mark it reviewed.",
                                style = MaterialTheme.typography.bodySmall, color = SemanticBrown
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (fullyEditable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = direction == Direction.SENT, onClick = { direction = Direction.SENT }, label = { Text("Sent") })
                        FilterChip(selected = direction == Direction.RECEIVED, onClick = { direction = Direction.RECEIVED }, label = { Text("Received") })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Text("${formatInr(transaction.amount)} • ${transaction.bankOrSource}", style = MaterialTheme.typography.bodyMedium, color = MutedText)
                    if (transaction.balanceAfter != null) {
                        Text("Balance after: ${formatInr(transaction.balanceAfter)}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                // Merchant/counterparty display name is always editable, even
                // for a confidently-parsed real bank transaction — it's just
                // a display label (e.g. renaming an ugly "VPA xyz@bank" to a
                // real name), not a financial fact, unlike amount/direction
                // which stay locked to what the bank actually reported for
                // non-manual, non-needsReview transactions. See ยง2.14.
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
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { confirmingDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = SemanticRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete transaction", color = SemanticRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = if (fullyEditable) {
                        transaction.copy(
                            amount = amountText.toDoubleOrNull() ?: transaction.amount,
                            direction = direction,
                            merchantOrContact = merchant.trim().takeIf { it.isNotBlank() },
                            category = category.name,
                            note = note.trim().takeIf { it.isNotBlank() },
                            tags = tags.trim().takeIf { it.isNotBlank() },
                            needsReview = false // user has now confirmed/corrected this, whether or not they changed the values
                        )
                    } else {
                        transaction.copy(
                            merchantOrContact = merchant.trim().takeIf { it.isNotBlank() },
                            category = category.name,
                            note = note.trim().takeIf { it.isNotBlank() },
                            tags = tags.trim().takeIf { it.isNotBlank() }
                        )
                    }
                    // A real category change on a merchant we can identify is offered
                    // to every transaction from that merchant (and to future ones).
                    if (CategoryRules.shouldAsk(CategoryRuleStore.getAll(context), updated.merchantOrContact, transaction.category, category.name)) {
                        pendingRule = updated
                    } else {
                        onSave(updated)
                    }
                },
                enabled = !fullyEditable || amountText.toDoubleOrNull() != null
            ) { Text(if (transaction.needsReview) "Save & mark reviewed" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- MANUAL CASH ENTRY ----------

@Composable
internal fun ManualEntryDialog(onDismiss: () -> Unit, onSave: (Transaction) -> Unit) {
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
