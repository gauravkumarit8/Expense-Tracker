package com.autoexpensetracker.listener

import android.content.ComponentName
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autoexpensetracker.BuildConfig
import com.autoexpensetracker.worker.ParseAndStoreWorker

/**
 * Primary transaction-capture path (see REQUIREMENTS.md Architecture ยง1).
 *
 * Requires the user to grant "Notification access" — a special permission
 * separate from READ_SMS, requested via a dedicated onboarding screen that
 * deep-links to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.
 *
 * IMPORTANT: This receives ALL notifications from ALL apps, not just banks.
 * The filtering below decides which ones are even worth reading; the rest
 * (OTP/promo/non-financial text) is filtered downstream in TransactionParser.
 *
 * 2026-09-12 rework: the previous version only forwarded a hardcoded list of
 * ~9 packages (Google Messages, Samsung Messages, GPay, PhonePe, Paytm,
 * ICICI, HDFC, SBI apps). That silently dropped every notification from:
 *  - any bank not in that list (Axis, Kotak, Yes Bank, IDFC First, PNB, BoB,
 *    Canara, IndusInd, RBL, Federal, IDBI, Standard Chartered, Citi, Amex...)
 *  - any non-AOSP/Samsung default SMS app (MIUI/ColorOS/OxygenOS/FuntouchOS
 *    messaging, Truecaller-as-default-SMS, Textra, Chomp SMS, etc.)
 * even with Notification access fully granted, because the app never even
 * looked at the notification's content — it never got past the package
 * check. Fixed by (a) resolving the OS's actual default SMS app package at
 * runtime instead of guessing OEM package names, (b) keeping an expanded,
 * best-effort list of known bank/UPI/wallet/SMS apps as a fast-path, and
 * (c) a content-shape fallback so an unlisted bank/SMS app is still picked
 * up as long as its sender ID looks like a real DLT-registered header
 * (e.g. "HDFCBK", "VM-SBIINB") rather than a contact name — this is what
 * makes it work for banks we don't know about by name.
 */
class NotificationCaptureService : NotificationListenerService() {

    // Best-effort fast path: still worth keeping so we don't have to run the
    // sender-shape heuristic against every single notification on the
    // device. NOT the only path anymore — see onNotificationPosted.
    private val knownFinancePackagePrefixes = listOf(
        // Default/common SMS clients
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.messaging",
        "com.samsung.android.messaging",
        "com.truecaller",
        "com.textra",
        "com.p1.chompsms",
        // UPI / wallets
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "com.mobikwik_new",
        "com.freecharge.android",
        // Bank apps (best-effort — expand as needed; NOT required for
        // capture to work, since the shape-based fallback below covers
        // banks not listed here)
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.sbi.SBIFreedomPlus",
        "com.axis.mobile",
        "com.msf.kbank.mobile", // Kotak
        "com.yesbank",
        "com.idfcfirstbank.optimus",
        "com.pnbindia",
        "com.bankofbaroda.mobankplus",
        "com.canarabank.mobility",
        "com.indusind.mobile",
        "com.rblbank.mobank",
        "com.federal.mobile",
        "com.idbibank.paisa",
        "com.sc.breezebanking.in",
        "com.citi.citimobile"
    )

    // Indian bank/UPI SMS sender IDs are DLT-registered "headers": 6
    // alphanumeric characters, optionally prefixed with a 2-letter telecom
    // code and hyphen (e.g. "HDFCBK", "VM-HDFCBK", "AD-SBIINB", "JD-ICICIB").
    // Real contact names or app titles essentially never match this shape
    // (they have spaces, lowercase letters, punctuation), so this is a safe
    // way to recognize "this looks like a bank/SMS alert" from a package we
    // don't otherwise know about, without having to hardcode every bank.
    private val senderIdShape = Regex("^(?:[A-Z]{2}-)?[A-Z0-9]{6}\$")

    private var cachedDefaultSmsPackage: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshDefaultSmsPackage()
    }

    // NotificationListenerService connections can be dropped by the OS
    // (low memory, OEM battery managers on Xiaomi/Vivo/Oppo/Samsung) without
    // any user action. Without requesting a rebind here, capture silently
    // stops until the user manually re-toggles the permission or reboots —
    // this was previously unhandled entirely.
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (BuildConfig.DEBUG) {
            android.util.Log.w("ExpenseTrackerDEBUG", "Notification listener disconnected — requesting rebind")
        }
        requestRebind(ComponentName(applicationContext, NotificationCaptureService::class.java))
    }

    private fun refreshDefaultSmsPackage() {
        cachedDefaultSmsPackage = try {
            Telephony.Sms.getDefaultSmsPackage(applicationContext)
        } catch (e: Exception) {
            null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()

        // Prefer the full expanded text (bigText) when present — some
        // banking apps put the complete transaction message there and leave
        // "android.text" as a truncated one-liner or a bundling summary
        // like "3 new messages".
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val plainText = extras.getCharSequence("android.text")?.toString().orEmpty()

        // Grouped/bundled conversations (MessagingStyle, common in Google
        // Messages/Samsung Messages when multiple SMS arrive close together)
        // expose each individual line here. Without reading this, a burst
        // of transaction SMS can collapse into a single summary notification
        // and all but the last message get lost.
        val textLines = extras.getCharSequenceArray("android.textLines")
            ?.map { it.toString() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val isKnownFinancePackage = knownFinancePackagePrefixes.any { pkg.startsWith(it) }
        val isDefaultSmsApp = cachedDefaultSmsPackage != null && pkg == cachedDefaultSmsPackage
        val looksLikeBankSender = senderIdShape.matches(title.trim())

        if (!isKnownFinancePackage && !isDefaultSmsApp && !looksLikeBankSender) return

        val senderForMatching = title.ifBlank { pkg }

        // Process each bundled line as its own candidate transaction so a
        // burst of SMS doesn't collapse into one capture; fall back to
        // bigText, then plain text, when there's no bundling.
        val candidateTexts = if (textLines.isNotEmpty()) textLines else listOf(bigText.ifBlank { plainText })

        for (text in candidateTexts) {
            if (text.isBlank()) continue
            val combined = "$title $text"

            if (BuildConfig.DEBUG) {
                // TEMP DEV-ONLY DIAGNOSTIC — logs raw notification text locally
                // to this device's logcat so regex patterns in bank_patterns.json
                // can be tuned against real message formats during development.
                // Gated by BuildConfig.DEBUG so it can NEVER ship in a release
                // build. Remove this block entirely once parser accuracy is
                // validated — see REQUIREMENTS.md ยง3.5/Open Items.
                android.util.Log.d(
                    "ExpenseTrackerDEBUG",
                    "pkg=$pkg title=[$title] known=$isKnownFinancePackage defaultSms=$isDefaultSmsApp shape=$looksLikeBankSender text=[$text]"
                )
            }

            // Hand off immediately to a WorkManager job. We do NOT parse inline
            // here — keeps this callback (which the OS expects to return fast)
            // lightweight, and WorkManager handles retry/battery constraints.
            val request = OneTimeWorkRequestBuilder<ParseAndStoreWorker>()
                .setInputData(
                    workDataOf(
                        ParseAndStoreWorker.KEY_SENDER to senderForMatching,
                        ParseAndStoreWorker.KEY_TEXT to combined,
                        ParseAndStoreWorker.KEY_TIMESTAMP to sbn.postTime
                    )
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueue(request)
        }
    }
}