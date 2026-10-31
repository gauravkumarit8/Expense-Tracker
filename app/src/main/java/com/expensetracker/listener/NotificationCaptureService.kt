package com.autoexpensetracker.listener

import android.content.ComponentName
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autoexpensetracker.BuildConfig
import com.autoexpensetracker.worker.TransactionIngestor
import java.util.concurrent.ConcurrentHashMap

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

    // Whether a title looks like a bank/UPI SMS sender ID is decided by
    // SenderShape (tested off-device). The old inline pattern here had no
    // optional "-T"/"-S" traffic suffix, so the most common real form of a
    // sender ID — "JD-SLCBNK-T", "CP-HDFCBN-S" — failed it.

    // Newest message time already handled per notification, so an update of a
    // conversation notification doesn't re-process its whole history.
    private val lastSeenMessageTime = ConcurrentHashMap<String, Long>()

    private var lastDefaultSmsRefreshMillis = 0L

    private var cachedDefaultSmsPackage: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshDefaultSmsPackage()
        CaptureHealthStore.recordConnected(applicationContext)
        readAlertsAlreadyInShade()
    }

    /**
     * Once, the first time the listener connects: run the bank and UPI alerts that are ALREADY
     * in the notification shade through exactly the same filter and parser as a live alert.
     *
     * Why: a notification listener only hears about alerts posted after it connects, so a new
     * user who has just turned access on sees an empty app even if two bank alerts are sitting,
     * unread, in the shade. Reading them gives that user a first real transaction within
     * seconds, which is the point at which the app starts to make sense.
     *
     * Only notifications that pass the normal finance filter are used, nothing else is read,
     * and a repeat of an alert is dropped by the usual duplicate check. It runs once only
     * (not on every reconnect, when the shade can hold days-old alerts already recorded).
     */
    private fun readAlertsAlreadyInShade() {
        if (CaptureHealthStore.hasReadExistingAlerts(applicationContext)) return
        // Marked BEFORE reading: if anything below fails it must not be retried on every connect.
        CaptureHealthStore.markReadExistingAlerts(applicationContext)
        try {
            val existing = activeNotifications ?: return
            for (sbn in existing) {
                try {
                    onNotificationPosted(sbn)
                } catch (e: Exception) {
                    // One odd notification must not stop the rest, or the connection itself.
                }
            }
        } catch (e: Exception) {
            // Reading the shade is a bonus; failing to do so must never break capture.
        }
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
        CaptureHealthStore.recordDisconnected(applicationContext)
        requestRebind(ComponentName(applicationContext, NotificationCaptureService::class.java))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        lastSeenMessageTime.remove(sbn.key)
    }

    private fun refreshDefaultSmsPackage() {
        lastDefaultSmsRefreshMillis = System.currentTimeMillis()
        cachedDefaultSmsPackage = try {
            Telephony.Sms.getDefaultSmsPackage(applicationContext)
        } catch (e: Exception) {
            null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        // The default SMS app was only looked up when the listener connected,
        // so changing it afterwards (e.g. Google Messages -> the phone's own
        // Messages app) left the old value cached until the next reconnect and
        // the new app's notifications were ignored. Re-check at most every 10
        // minutes — often enough to notice a change, rare enough to stay cheap
        // given every notification from every app passes through here.
        if (cachedDefaultSmsPackage == null || System.currentTimeMillis() - lastDefaultSmsRefreshMillis > 10 * 60 * 1000L) {
            refreshDefaultSmsPackage()
        }

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

        // Exact match, not startsWith: package names are unique per device
        // (Android's package manager won't let a second app install under
        // an existing one's exact name unless it's signed with the same
        // key, which is how legitimate app updates work). startsWith
        // matched any package name that merely began with a listed one,
        // e.g. a lookalike app named "com.phonepe.apphelper" would have
        // been trusted as PhonePe itself and had its notifications parsed
        // as real transactions.
        val isKnownFinancePackage = knownFinancePackagePrefixes.any { pkg == it }
        val isDefaultSmsApp = cachedDefaultSmsPackage != null && pkg == cachedDefaultSmsPackage
        val looksLikeBankSender = SenderShape.looksLikeBankSender(title)

        if (!isKnownFinancePackage && !isDefaultSmsApp && !looksLikeBankSender) return

        val senderForMatching = title.ifBlank { pkg }

        // Which message lines to treat as candidate transactions, each with its
        // own timestamp:
        //  1. MessagingStyle conversations (Google Messages and most other SMS
        //     apps): the notification carries the conversation's recent
        //     messages in "android.messages". "android.textLines" is only for
        //     InboxStyle, so the earlier comment claiming it covered
        //     MessagingStyle was wrong — a burst of SMS lost all but the last
        //     one, and "3 new messages" summaries were parsed as content.
        //  2. InboxStyle lines.
        //  3. The expanded text, then the plain text.
        // NotificationLines drops blanks and "N new messages" headers in every
        // case, and (for 1) skips messages already handled for this notification.
        val messagingLines = readMessagingLines(extras)
        val candidates: List<NotifLine> = when {
            messagingLines.isNotEmpty() -> {
                val fresh = NotificationLines.selectNew(messagingLines, lastSeenMessageTime[sbn.key], sbn.postTime)
                NotificationLines.newestTime(messagingLines)?.let {
                    if (lastSeenMessageTime.size > 200) lastSeenMessageTime.clear()
                    lastSeenMessageTime[sbn.key] = it
                }
                fresh
            }
            textLines.isNotEmpty() ->
                NotificationLines.selectNew(textLines.map { NotifLine(it, 0L) }, null, sbn.postTime)
            else ->
                NotificationLines.selectNew(listOf(NotifLine(bigText.ifBlank { plainText }, 0L)), null, sbn.postTime)
        }

        if (candidates.isNotEmpty()) CaptureHealthStore.recordCandidate(applicationContext)

        for (line in candidates) {
            val text = line.text
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

            // Parsed on a background thread and written straight to the
            // encrypted DB. Deliberately NOT handed to WorkManager: it
            // persists its input Data in a plain unencrypted SQLite
            // database, which would put the raw message text (account
            // digits, balances, names) on disk unencrypted. The callback
            // itself returns immediately; nothing here blocks the main
            // thread.
            TransactionIngestor.enqueue(applicationContext, senderForMatching, combined, line.timeMillis)
        }
    }

    /**
     * The recent messages of a MessagingStyle notification, oldest first as the
     * app supplied them. Returns an empty list if the extra is absent or in a
     * shape we don't recognise, in which case the caller falls back to the plain
     * text — so this can only ever ADD coverage, never remove it.
     */
    private fun readMessagingLines(extras: Bundle): List<NotifLine> {
        val raw = try {
            extras.getParcelableArray("android.messages")
        } catch (e: Exception) {
            null
        }
        if (raw == null) return emptyList()
        val lines = ArrayList<NotifLine>()
        for (item in raw) {
            val bundle = item as? Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString() ?: continue
            lines.add(NotifLine(text, bundle.getLong("time", 0L)))
        }
        return lines
    }
}
