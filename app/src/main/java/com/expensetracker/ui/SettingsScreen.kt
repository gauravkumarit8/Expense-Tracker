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

// ---------- BACKUP & RESTORE ----------

@Composable
internal fun BackupRestoreDialog(onDismiss: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var confirmingImport by remember { mutableStateOf(false) }

    if (confirmingImport) {
        AlertDialog(
            onDismissRequest = { confirmingImport = false },
            title = { Text("Restore from backup?") },
            text = { Text("This replaces ALL current transactions, reminders, and budgets with the contents of the backup file. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmingImport = false; onImport() }) {
                    Text("Replace everything", color = SemanticRed)
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
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = SemanticOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "The backup file is NOT encrypted. Save it somewhere private, not a publicly shared folder.",
                            style = MaterialTheme.typography.bodySmall, color = SemanticBrown
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

// ---------- SETTINGS SCREEN ----------

@Composable
internal fun SettingsScreen(
    notificationAccessGranted: Boolean,
    onEnableNotificationAccess: () -> Unit,
    onBackupRestoreClick: () -> Unit,
    onCsvExportClick: () -> Unit,
    isPro: Boolean,
    onUpgradeClick: () -> Unit,
    appLockEnabled: Boolean,
    canUseAppLock: Boolean,
    onAppLockToggle: (Boolean) -> Unit,
    onDeleteAllData: () -> Unit,
    canRequestAds: Boolean,
    privacyOptionsRequired: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dailyCheckInEnabled: Boolean,
    onDailyCheckInToggle: (Boolean) -> Unit,
    onShowPrivacyOptions: () -> Unit
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }

    if (showThemePicker) {
        ThemePickerDialog(
            current = themeMode,
            onDismiss = { showThemePicker = false },
            onSelect = { mode -> onThemeModeChange(mode); showThemePicker = false }
        )
    }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        } catch (e: Exception) { "—" }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Membership", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            SettingsRow(
                icon = if (isPro) Icons.Filled.WorkspacePremium else Icons.Filled.Star,
                title = if (isPro) "Expense Tracker Pro" else "Upgrade to Pro",
                subtitle = if (isPro) "Thanks for supporting the app!" else "CSV export and more, ad-free, no lending upsells",
                onClick = onUpgradeClick,
                tint = if (isPro) SemanticGold else SemanticIndigo
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text("Appearance", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            SettingsRow(
                icon = Icons.Filled.DarkMode,
                title = "Theme",
                subtitle = when (themeMode) {
                    ThemeMode.SYSTEM -> "System default"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                    ThemeMode.AMOLED -> "AMOLED (pure black)"
                },
                onClick = { showThemePicker = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        item {
            SettingsToggleRow(
                icon = Icons.Filled.NotificationsActive,
                title = "Daily check-in reminder",
                subtitle = if (dailyCheckInEnabled) "A daily nudge to review today's spending" else "Get a daily nudge to review your spending",
                checked = dailyCheckInEnabled,
                enabled = true,
                onCheckedChange = onDailyCheckInToggle
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text("Security", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            SettingsToggleRow(
                icon = Icons.Filled.Fingerprint,
                title = "App lock",
                subtitle = if (!canUseAppLock) "Set up a fingerprint, face unlock, or screen lock in your device settings first"
                    else if (appLockEnabled) "Biometric or device PIN required to open the app"
                    else "Require biometric or device PIN to open the app",
                checked = appLockEnabled,
                enabled = canUseAppLock,
                onCheckedChange = onAppLockToggle
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text("Data & Privacy", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
        }
        // Backup & Restore and CSV Export are Pro-only (REQUIREMENTS.md
        // ยง2.17 amendment, 2026-09-02) — fully hidden for free-tier users
        // rather than shown grayed-out with an upsell nudge. That's a
        // deliberate simplicity tradeoff, not a limitation of the
        // requirePro() gate itself (see the tradeoff note in the Decision
        // Log if reconsidering a grayed-out variant later).
        if (isPro) {
            item {
                SettingsRow(
                    icon = Icons.Filled.CloudSync,
                    title = "Backup & Restore",
                    subtitle = "Export or restore your data as a file",
                    onClick = onBackupRestoreClick
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.TableChart,
                    title = "Export to CSV",
                    subtitle = "For opening in Excel, Sheets, etc. — not for restoring",
                    onClick = onCsvExportClick
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        item {
            SettingsRow(
                icon = if (notificationAccessGranted) Icons.Filled.NotificationsActive else Icons.Filled.Warning,
                title = "Notification access",
                subtitle = if (notificationAccessGranted) "Granted — bank/UPI alerts are being captured" else "Not granted — tap to enable in Settings",
                onClick = onEnableNotificationAccess,
                tint = if (notificationAccessGranted) BrandGreen else SemanticOrange
            )
            // Only shown when the UMP SDK determines this user's region
            // requires an always-available way to revisit their ad-consent
            // choice (e.g. EEA/UK) — GDPR requires consent be revocable,
            // not just gatherable once at first launch. See ยง2.23.
            if (privacyOptionsRequired) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Privacy & Ad Consent",
                    subtitle = "Review or change your ad personalization choice",
                    onClick = onShowPrivacyOptions
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text("Danger zone", style = MaterialTheme.typography.labelLarge, color = SemanticRed)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            SettingsRow(
                icon = Icons.Filled.DeleteForever,
                title = "Delete all data",
                subtitle = "Permanently erases all transactions, reminders, and budgets",
                onClick = { showDeleteConfirm = true },
                tint = SemanticRed
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Text("About", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Expense Tracker v$versionName", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Your transactions, budgets, and reminders stay on this device and are never transmitted anywhere. Subscriptions, ads, and app updates use Google's own services.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            if (!isPro && canRequestAds) {
                Spacer(modifier = Modifier.height(20.dp))
                BannerAdView()
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete all data?") },
            text = { Text("This permanently erases every transaction, reminder, and budget on this device. This cannot be undone — consider exporting a backup first.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDeleteAllData() }) {
                    Text("Delete everything", color = SemanticRed)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = SemanticIndigo
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, onClick = onClick) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = SemanticGray)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (enabled) SemanticIndigo else SemanticGray, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, color = if (enabled) Color.Unspecified else SemanticGray)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

// ---------- APP LOCK SCREEN ----------

@Composable
private fun ThemePickerDialog(current: ThemeMode, onDismiss: () -> Unit, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to "System default",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
        ThemeMode.AMOLED to "AMOLED (pure black)"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(mode) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun LockScreen(onUnlockClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = SemanticIndigo)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Expense Tracker is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Verify it's you to view your transactions",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onUnlockClick) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Unlock")
            }
        }
    }
}

// ---------- UPGRADE TO PRO ----------

@Composable
internal fun UpgradeDialog(products: List<ProductDetails>, onDismiss: () -> Unit, onSelectProduct: (ProductDetails) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upgrade to Pro") },
        text = {
            Column {
                Text(
                    "Support development and unlock CSV export, backup & restore, and an ad-free experience. No lending upsells, no data ever leaves your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (products.isEmpty()) {
                    Text(
                        "Subscription options aren't available right now. This may mean the app hasn't been published with Pro products configured yet.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                } else {
                    // Yearly first, and visually distinguished with a "Best
                    // Value" badge — a static callout rather than a
                    // computed percentage, since reliably parsing savings
                    // out of a locale-formatted price string (e.g. "₹999.00")
                    // back into a number is fragile across currencies and
                    // not worth the risk for a cosmetic badge.
                    val sorted = products.sortedByDescending { it.productId == BillingManager.PRODUCT_ID_YEARLY }
                    sorted.forEach { product ->
                        val offer = product.subscriptionOfferDetails?.firstOrNull()
                        val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                        val price = phase?.formattedPrice ?: "—"
                        val isYearly = product.productId == BillingManager.PRODUCT_ID_YEARLY
                        val period = if (isYearly) "per year" else "per month"

                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isYearly) Color(0xFFEDF7ED) else Color(0xFFF7F7F9),
                            border = if (isYearly) BorderStroke(1.dp, BrandGreen) else null,
                            onClick = { onSelectProduct(product) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (isYearly) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = BrandGreen) {
                                        Text(
                                            "BEST VALUE", color = Color.White, fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isYearly) "Yearly" else "Monthly", fontWeight = FontWeight.Medium)
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(price, fontWeight = FontWeight.SemiBold)
                                        Text(period, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

/**
 * Reached by tapping the Membership row once already Pro — replaces the
 * previous (bugged) behavior of reopening UpgradeDialog, which made no
 * sense for an existing subscriber. See REQUIREMENTS.md ยง12.
 *
 * Offers exactly the two things Play Billing actually supports here:
 * switching to the other plan (in-app, via BillingManager.launchPlanChangeFlow)
 * and managing/cancelling on Google Play (the only sanctioned way to
 * cancel — Play Billing has no in-app cancel API for third-party apps by
 * design).
 */
@Composable
internal fun ManageSubscriptionDialog(
    activeProductId: String?,
    products: List<ProductDetails>,
    onSwitchPlan: (ProductDetails) -> Unit,
    onManageOnPlayStore: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentLabel = when (activeProductId) {
        BillingManager.PRODUCT_ID_MONTHLY -> "Monthly"
        BillingManager.PRODUCT_ID_YEARLY -> "Yearly"
        else -> "Pro"
    }
    val otherProduct = products.firstOrNull { it.productId != activeProductId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Subscription") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = SemanticGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("You're on the $currentLabel plan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(20.dp))

                if (otherProduct != null) {
                    val offer = otherProduct.subscriptionOfferDetails?.firstOrNull()
                    val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "—"
                    val otherLabel = if (otherProduct.productId == BillingManager.PRODUCT_ID_YEARLY) "Yearly" else "Monthly"
                    OutlinedButton(onClick = { onSwitchPlan(otherProduct) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Switch to $otherLabel — $price")
                    }
                    Text(
                        "Takes effect immediately; any remaining time on your current plan is credited toward the new one.",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                }

                TextButton(onClick = onManageOnPlayStore, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage or cancel on Google Play")
                }
                Text(
                    "Opens Google Play's own subscription screen — the only place cancellation happens, so it's always fully in your control.",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
