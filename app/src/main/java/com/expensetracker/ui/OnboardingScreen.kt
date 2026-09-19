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

// ---------- FIRST-LAUNCH ONBOARDING / DISCLOSURE FLOW ----------
//
// REQUIREMENTS.md ยง2.22 (added 2026-09-04). Three fixed pages (no pager
// library dependency — kept to a simple page-index int, consistent with
// the project's general "avoid unfamiliar library APIs where plain
// Compose does the job" approach, see the Vico/Canvas-chart decision):
//   1. What the app does
//   2. What we read, and what we never do (the actual disclosure content)
//   3. Grant Notification Access — the actual permission ask, with an
//      explicit "Skip for now" path; this is never allowed to be a dead
//      end, since Play policy requires the app remain usable (manual entry
//      still works) without the permission.
// Shown once ever, gated by OnboardingStore — see MainActivity.onCreate.

private enum class OnboardingPage { WELCOME, TRANSPARENCY, PERMISSION }

@Composable
internal fun OnboardingScreen(
    notificationAccessGranted: Boolean,
    onEnableNotificationAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onFinish: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(OnboardingPage.WELCOME) }
    val pages = OnboardingPage.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    // Fast-forwards past the explainer copy straight to the
                    // actual disclosure + permission page — never skips the
                    // disclosure itself, only the "what this app is" intro.
                    if (page != OnboardingPage.PERMISSION) {
                        TextButton(onClick = { page = OnboardingPage.PERMISSION }) { Text("Skip intro") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    pages.forEach { p ->
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (p == page) MaterialTheme.colorScheme.primary else Color(0xFFE0E0E0))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                when (page) {
                    OnboardingPage.WELCOME, OnboardingPage.TRANSPARENCY -> {
                        Button(
                            onClick = { page = pages[pages.indexOf(page) + 1] },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Next") }
                    }
                    OnboardingPage.PERMISSION -> {
                        Button(onClick = onEnableNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                            Text(if (notificationAccessGranted) "Notification access granted ✓" else "Open notification access settings")
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                            Text("Also check battery settings")
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                            Text(if (notificationAccessGranted) "Continue" else "Skip for now — I'll add transactions manually")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (page) {
                OnboardingPage.WELCOME -> OnboardingWelcomePage()
                OnboardingPage.TRANSPARENCY -> OnboardingTransparencyPage()
                OnboardingPage.PERMISSION -> OnboardingPermissionPage(notificationAccessGranted)
            }
        }
    }
}

@Composable
private fun OnboardingWelcomePage() {
    Icon(
        Icons.Filled.AccountBalanceWallet, contentDescription = null,
        modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text("Track expenses automatically", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Expense Tracker reads transaction alerts from your banking and UPI apps — things like \"Rs.500 debited...\" or \"You received Rs.1200\" — and logs them for you automatically. No typing every purchase by hand.",
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Color(0xFF555555)
    )
}

@Composable
private fun OnboardingTransparencyPage() {
    Icon(
        Icons.Filled.Shield, contentDescription = null,
        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text("What we read — and what we never do", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(20.dp))
    val points = listOf(
        Icons.Filled.NotificationsActive to "We only read notifications from your banking, UPI, and messaging apps — never chats, social apps, or anything else.",
        Icons.Filled.Bolt to "We pull out just the amount, direction, date, and merchant. The original message text is discarded right after — it's never saved.",
        Icons.Filled.CloudOff to "Nothing leaves your phone. Transactions are encrypted and stored only on this device — there's no server to send it to.",
        Icons.Filled.Block to "This app never asks for SMS permissions. Android's Notification Access is the only way we read alerts, and you control it from Settings any time."
    )
    points.forEach { (icon, text) ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF444444))
        }
    }
}

@Composable
private fun OnboardingPermissionPage(notificationAccessGranted: Boolean) {
    Icon(
        if (notificationAccessGranted) Icons.Filled.CheckCircle else Icons.Filled.NotificationsActive,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = if (notificationAccessGranted) BrandGreen else MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        if (notificationAccessGranted) "You're all set" else "One step to turn on auto-capture",
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        if (notificationAccessGranted) {
            "Notification access is already on — bank and UPI alerts will be captured automatically from now on."
        } else {
            "Tap below to open Android's Notification Access screen, then turn on the switch next to Expense Tracker. This is a system screen — Android, not us, controls it, and you can turn it off any time."
        },
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Color(0xFF555555)
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        "You can still add and edit transactions manually if you'd rather not grant this — it just won't auto-capture new ones.",
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = Color.Gray
    )
}
