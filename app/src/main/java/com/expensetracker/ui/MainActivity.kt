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

private enum class Screen(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Filled.Home),
    TRANSACTIONS("Transactions", Icons.Filled.List),
    CHARTS("Charts", Icons.Filled.BarChart),
    BUDGETS("Budgets", Icons.Filled.PieChart),
    REMINDERS("Reminders", Icons.Filled.NotificationsActive)
}

class MainActivity : FragmentActivity() {
    private lateinit var billingManager: BillingManager
    private lateinit var appUpdateHelper: AppUpdateHelper

    // Set once UMP consent is resolved (obtained, or not required) AND the
    // Mobile Ads SDK has been initialized — BannerAdView must not render
    // before this is true. A plain Compose-observable property on the
    // Activity (rather than state declared inside setContent) so the
    // ConsentManager callback — which fires asynchronously, independent of
    // any single composition — can update it directly. See
    // ads/ConsentManager.kt and REQUIREMENTS.md ยง2.23.
    private var canRequestAds by mutableStateOf(false)
    // True if this user's region requires an always-available way to
    // revisit their ad-consent choice — drives the Settings "Privacy & Ad
    // Consent" row's visibility.
    private var privacyOptionsRequired by mutableStateOf(false)

    private val updateFlowLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { /* result ignored — a cancelled/failed update flow just means the banner reappears later */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target API 36 enforces edge-to-edge display unconditionally
        // regardless of this call — but calling it explicitly is what
        // gives the system bars automatic light/dark icon contrast
        // matching the current theme, instead of leaving that to
        // whatever default scrim the OS falls back to when an app hasn't
        // opted in. The deprecated android:statusBarColor/
        // navigationBarColor attributes in themes.xml are superseded by
        // this on API 35+ (ignored there either way) — left in place only
        // for the pre-Compose window on API <35, where they still apply.
        enableEdgeToEdge()
        val db = AppDatabase.getInstance(applicationContext)
        val transactionDao = db.transactionDao()
        val reminderDao = db.reminderDao()
        val budgetDao = db.budgetDao()

        billingManager = BillingManager(applicationContext)
        billingManager.startConnection()
        appUpdateHelper = AppUpdateHelper(this)

        // Gather/refresh ad consent before anything ad-related can render.
        // requestConsentInfoUpdate() needs this Activity, not just an
        // Application Context, which is why this no longer lives in
        // ExpenseTrackerApp.onCreate. Safe to call on every onCreate — the
        // UMP SDK itself decides whether a form actually needs to show.
        ConsentManager.gatherConsent(this) { resolved ->
            canRequestAds = resolved
            privacyOptionsRequired = ConsentManager.isPrivacyOptionsRequired()
        }

        setContent {
            val outerContext = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemePreferenceStore.get(outerContext)) }

            ExpenseTrackerTheme(themeMode = themeMode) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var appLockEnabled by remember { mutableStateOf(AppLockManager.isEnabled(context)) }
                var dailyCheckInEnabled by remember { mutableStateOf(DailyCheckInStore.isEnabled(context)) }
                var isUnlocked by remember { mutableStateOf(!appLockEnabled) }
                // Re-lock every time the app leaves the foreground (covers
                // both "user backgrounds the app" and process death/recreate)
                // rather than only on initial launch, so a lost/stolen
                // unlocked phone doesn't leave financial data exposed after
                // switching away and back.
                LifecycleStartEffect(appLockEnabled) {
                    onStopOrDispose { if (appLockEnabled) isUnlocked = false }
                }

                var notificationAccessGranted by remember { mutableStateOf(NotificationAccessHelper.isEnabled(context)) }
                LifecycleStartEffect(Unit) {
                    notificationAccessGranted = NotificationAccessHelper.isEnabled(context)
                    onStopOrDispose { }
                }

                val isPro by billingManager.isPro.collectAsStateWithLifecycle(initialValue = false)
                val proProducts by billingManager.productDetails.collectAsStateWithLifecycle(initialValue = emptyList())
                val activePurchase by billingManager.activePurchase.collectAsStateWithLifecycle(initialValue = null)
                var showUpgradeDialog by remember { mutableStateOf(false) }
                // 2026-09-07: separate from showUpgradeDialog — tapping the
                // Membership row while already Pro used to incorrectly
                // reopen the "Upgrade to Pro" dialog (nonsensical for an
                // existing subscriber). This drives a distinct
                // ManageSubscriptionDialog instead, see REQUIREMENTS.md ยง12.
                var showManageSubscriptionDialog by remember { mutableStateOf(false) }
                var pendingGatedAction by remember { mutableStateOf<(() -> Unit)?>(null) }

                var showUpdateBanner by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    appUpdateHelper.checkForUpdate(updateFlowLauncher, onUpdateAvailable = { showUpdateBanner = true })
                }

                // Ask for a review at most once per app open, only once
                // ReviewPromptStore's criteria are met (real auto-captured
                // usage, respecting a cooldown and a lifetime cap — see
                // that file for the policy). Deliberately not tied to any
                // particular screen or action — asking right after a
                // "moment of delight" (e.g. right after a capture) would be
                // better, but that moment happens in a background
                // WorkManager job with no Activity to launch the flow from,
                // so the next app open is the earliest safe opportunity.
                LaunchedEffect(Unit) {
                    if (ReviewPromptStore.isEligibleForPrompt(context)) {
                        ReviewHelper.maybeRequestReview(this@MainActivity)
                    }
                }

                /** Runs [action] immediately if Pro, otherwise shows the
                 *  upgrade dialog and runs [action] automatically once a
                 *  purchase succeeds. Demonstrated on CSV export as the
                 *  reference example — see REQUIREMENTS.md ยง2.17 for which
                 *  other features are earmarked to use this same gate. */
                fun requirePro(action: () -> Unit) {
                    if (isPro) action() else { pendingGatedAction = action; showUpgradeDialog = true }
                }
                LaunchedEffect(isPro) {
                    if (isPro) { pendingGatedAction?.invoke(); pendingGatedAction = null; showUpgradeDialog = false }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                val allTransactions by transactionDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
                // Drives the red badge on the Search top-bar icon.
                val needsReviewCount = remember(allTransactions) { allTransactions.count { it.needsReview } }
                var showManualEntry by remember { mutableStateOf(false) }
                var showAddReminder by remember { mutableStateOf(false) }
                var showBackupDialog by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                // Full-screen overlay reached from "See all months" on the
                // Transactions tab — mirrors the existing showSettings pattern
                // rather than adding a bottom-nav tab. See ยง2.19.
                var showMonthlyHistory by remember { mutableStateOf(false) }
                // Search (2026-09-03 rework): also an overlay, not a bottom-nav
                // tab that replaces the current screen's content. Opened from a
                // top-bar icon (like Settings) and closes back to whatever
                // screen — Transactions with its current-month list, Charts,
                // Budgets, or Reminders — was showing underneath, rather than
                // discarding it. See REQUIREMENTS.md ยง2.20 amendment.
                var showSearch by remember { mutableStateOf(false) }
                androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
                androidx.activity.compose.BackHandler(enabled = showMonthlyHistory) { showMonthlyHistory = false }
                androidx.activity.compose.BackHandler(enabled = showSearch) { showSearch = false }
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

                val csvExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/csv")
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        try {
                            val transactions = transactionDao.getAllOnce()
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(com.autoexpensetracker.backup.CsvExporter.toCsv(transactions).toByteArray())
                            }
                            snackbarHostState.showSnackbar("CSV saved (${transactions.size} transactions)")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("CSV export failed: ${e.message}")
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

                val isLocked = appLockEnabled && !isUnlocked

                // First-launch disclosure flow (2026-09-04) — explains what
                // the app reads from notifications and why, before ever
                // prompting for Notification Access. Tracks "has seen the
                // explanation," not "has granted access" — declining on the
                // last page still marks onboarding complete; the existing
                // OnboardingBanner on the Transactions screen keeps
                // reminding them afterward. See REQUIREMENTS.md ยง2.22.
                var hasCompletedOnboarding by remember { mutableStateOf(OnboardingStore.hasCompleted(context)) }

                if (!hasCompletedOnboarding) {
                    OnboardingScreen(
                        notificationAccessGranted = notificationAccessGranted,
                        onEnableNotificationAccess = { context.startActivity(NotificationAccessHelper.settingsIntent()) },
                        onOpenAppInfo = { context.startActivity(BatteryOptimizationHelper.appInfoIntent(context)) },
                        onFinish = {
                            OnboardingStore.setCompleted(context)
                            hasCompletedOnboarding = true
                        }
                    )
                } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when {
                                        isLocked -> "Expense Tracker"
                                        showSettings -> "Settings"
                                        showMonthlyHistory -> "Monthly History"
                                        showSearch -> "Search"
                                        else -> screen.label
                                    }
                                )
                            },
                            navigationIcon = {
                                if (!isLocked && (showSettings || showMonthlyHistory || showSearch)) {
                                    IconButton(onClick = { showSettings = false; showMonthlyHistory = false; showSearch = false }) {
                                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                if (!isLocked && !showSettings && !showMonthlyHistory && !showSearch) {
                                    // Search icon lives in the top bar rather than
                                    // the bottom nav (2026-09-03 rework) — tapping
                                    // it opens SearchReviewScreen as an overlay on
                                    // top of whatever screen is currently showing,
                                    // closing back to it afterward, instead of
                                    // replacing the current-month Transactions view
                                    // with a separate destination. Badge mirrors
                                    // the old always-visible needs-review count.
                                    IconButton(onClick = { showSearch = true }) {
                                        if (needsReviewCount > 0) {
                                            BadgedBox(badge = {
                                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                    Text(if (needsReviewCount > 99) "99+" else "$needsReviewCount")
                                                }
                                            }) {
                                                Icon(Icons.Filled.Search, contentDescription = "Search & review")
                                            }
                                        } else {
                                            Icon(Icons.Filled.Search, contentDescription = "Search")
                                        }
                                    }
                                    IconButton(onClick = { showSettings = true }) {
                                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                    }
                                }
                            }
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!showSettings && !showMonthlyHistory && !showSearch && !isLocked) {
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
                        }
                    },
                    floatingActionButton = {
                        if (!showSettings && !showMonthlyHistory && !showSearch && !isLocked) {
                            when (screen) {
                                Screen.HOME, Screen.TRANSACTIONS -> FloatingActionButton(onClick = { showManualEntry = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add cash transaction")
                                }
                                Screen.REMINDERS -> FloatingActionButton(onClick = { showAddReminder = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add reminder")
                                }
                                else -> {}
                            }
                        }
                    }
                ) { padding ->
                    Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.surfaceVariant) {
                        if (isLocked) {
                            LockScreen(
                                onUnlockClick = {
                                    BiometricAuthHelper.authenticate(
                                        activity = this@MainActivity,
                                        onSuccess = { isUnlocked = true },
                                        onError = { /* cancelled or failed — stays locked, user can retry */ }
                                    )
                                }
                            )
                        } else if (showSettings) {
                            SettingsScreen(
                                notificationAccessGranted = notificationAccessGranted,
                                onEnableNotificationAccess = { context.startActivity(NotificationAccessHelper.settingsIntent()) },
                                // Both rows are now hidden entirely for
                                // free-tier users inside SettingsScreen (see
                                // ยง2.17 amendment) — requirePro here is a
                                // defensive fallback, not the primary gate,
                                // in case either is ever reached another way.
                                onBackupRestoreClick = { requirePro { showBackupDialog = true } },
                                onCsvExportClick = {
                                    requirePro {
                                        val filename = "expense_tracker_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.csv"
                                        csvExportLauncher.launch(filename)
                                    }
                                },
                                isPro = isPro,
                                onUpgradeClick = { if (isPro) showManageSubscriptionDialog = true else showUpgradeDialog = true },
                                appLockEnabled = appLockEnabled,
                                canUseAppLock = AppLockManager.canUseAppLock(context),
                                onAppLockToggle = { wantEnabled ->
                                    if (wantEnabled) {
                                        BiometricAuthHelper.authenticate(
                                            activity = this@MainActivity,
                                            title = "Confirm to enable App Lock",
                                            subtitle = "Verify it's you before turning this on",
                                            onSuccess = {
                                                AppLockManager.setEnabled(context, true)
                                                appLockEnabled = true
                                            }
                                        )
                                    } else {
                                        AppLockManager.setEnabled(context, false)
                                        appLockEnabled = false
                                    }
                                },
                                onDeleteAllData = {
                                    scope.launch {
                                        transactionDao.deleteAll()
                                        reminderDao.deleteAll()
                                        budgetDao.deleteAll()
                                        snackbarHostState.showSnackbar("All data deleted")
                                    }
                                },
                                canRequestAds = canRequestAds,
                                privacyOptionsRequired = privacyOptionsRequired,
                                themeMode = themeMode,
                                onThemeModeChange = { newMode ->
                                    ThemePreferenceStore.set(outerContext, newMode)
                                    themeMode = newMode
                                },
                                dailyCheckInEnabled = dailyCheckInEnabled,
                                onDailyCheckInToggle = { wantEnabled ->
                                    DailyCheckInStore.setEnabled(context, wantEnabled)
                                    dailyCheckInEnabled = wantEnabled
                                    if (wantEnabled) DailyCheckInScheduler.schedule(context) else DailyCheckInScheduler.cancel(context)
                                },
                                onShowPrivacyOptions = {
                                    ConsentManager.showPrivacyOptionsForm(this@MainActivity) {
                                        // Re-resolve after the user
                                        // potentially changed their choice —
                                        // gatherConsent's callback updates
                                        // both canRequestAds and
                                        // privacyOptionsRequired once done.
                                        ConsentManager.gatherConsent(this@MainActivity) { resolved ->
                                            canRequestAds = resolved
                                            privacyOptionsRequired = ConsentManager.isPrivacyOptionsRequired()
                                        }
                                    }
                                }
                            )
                        } else if (showMonthlyHistory) {
                            MonthlyHistoryScreen(
                                transactionDao = transactionDao,
                                allTransactions = allTransactions
                            )
                        } else if (showSearch) {
                            // Overlay, not a destination screen (2026-09-03) —
                            // closes back to whatever `screen` was already
                            // selected underneath (see the Back handling above).
                            SearchReviewScreen(
                                transactionDao = transactionDao,
                                allTransactions = allTransactions
                            )
                        } else {
                            when (screen) {
                                Screen.HOME -> DashboardScreen(
                                    budgetDao = budgetDao,
                                    reminderDao = reminderDao,
                                    allTransactions = allTransactions,
                                    onSeeAllTransactions = { screen = Screen.TRANSACTIONS },
                                    onSeeCharts = { screen = Screen.CHARTS },
                                    onSeeBudgets = { screen = Screen.BUDGETS },
                                    onSeeReminders = { screen = Screen.REMINDERS },
                                    onAddTransaction = { showManualEntry = true }
                                )
                                Screen.TRANSACTIONS -> TransactionsScreen(
                                    transactionDao = transactionDao,
                                    allTransactions = allTransactions,
                                    notificationAccessGranted = notificationAccessGranted,
                                    onEnableNotificationAccess = { context.startActivity(NotificationAccessHelper.settingsIntent()) },
                                    isPro = isPro,
                                    canRequestAds = canRequestAds,
                                    onSeeAllMonths = { showMonthlyHistory = true }
                                )
                                Screen.CHARTS -> ChartsScreen(allTransactions)
                                Screen.BUDGETS -> BudgetsScreen(budgetDao, allTransactions)
                                Screen.REMINDERS -> RemindersScreen(reminderDao, allTransactions)
                            }
                        }
                    }

                    if (showManualEntry) {
                        ManualEntryDialog(
                            onDismiss = { showManualEntry = false },
                            onSave = { tx ->
                                scope.launch {
                                    val id = transactionDao.insert(tx)
                                    if (id > 0) {
                                        com.autoexpensetracker.util.UnusualSpendDetector.checkAndNotify(context, transactionDao, tx.copy(id = id))
                                    }
                                }
                                showManualEntry = false
                            }
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
                    if (showUpgradeDialog) {
                        UpgradeDialog(
                            products = proProducts,
                            onDismiss = { showUpgradeDialog = false; pendingGatedAction = null },
                            onSelectProduct = { product -> billingManager.launchPurchaseFlow(this@MainActivity, product) }
                        )
                    }
                    if (showManageSubscriptionDialog) {
                        ManageSubscriptionDialog(
                            activeProductId = activePurchase?.products?.firstOrNull(),
                            products = proProducts,
                            onSwitchPlan = { newProduct ->
                                billingManager.launchPlanChangeFlow(this@MainActivity, newProduct)
                                showManageSubscriptionDialog = false
                            },
                            onManageOnPlayStore = {
                                context.startActivity(billingManager.manageSubscriptionsIntent())
                                showManageSubscriptionDialog = false
                            },
                            onDismiss = { showManageSubscriptionDialog = false }
                        )
                    }
                    LaunchedEffect(showUpdateBanner) {
                        if (showUpdateBanner) {
                            snackbarHostState.showSnackbar("An update is downloading in the background")
                            showUpdateBanner = false
                        }
                    }
                }
            }
                } // end else (hasCompletedOnboarding)
        }
    }

    override fun onResume() {
        super.onResume()
        // Completes a FLEXIBLE update that finished downloading while the
        // app was backgrounded — a no-op if nothing is pending.
        if (::appUpdateHelper.isInitialized) appUpdateHelper.completeUpdateIfDownloaded()
    }

    override fun onDestroy() {
        billingManager.endConnection()
        super.onDestroy()
    }
}
