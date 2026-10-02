package com.autoexpensetracker.parser

import android.content.Context
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Turns a raw (sender, text, timestamp) tuple into a structured Transaction,
 * or null if the message doesn't look like a transaction at all (OTP, promo,
 * unrelated notification, etc).
 *
 * The raw `text` parameter is used only transiently inside this function.
 * Callers must not persist it — only the returned Transaction (structured
 * fields + hash) should be stored. See REQUIREMENTS.md Security ยง2.
 */
class TransactionParser(context: Context) {

    private val config = BankPatternsLoader.load(context)
    // Whole-word matchers for excludeKeywords. These used to be plain
    // substring checks, so "OTP" matched inside merchant names like DotPe
    // or Hotpot and "ipo" matched inside Tripod, silently dropping real
    // transactions. Compiled once per parser instance.
    private val excludePatterns: List<Regex> = config.excludeKeywords.map {
        Regex("\\b" + Regex.escape(it.lowercase()) + "\\b")
    }

    fun parse(sender: String, text: String, timestampMillis: Long): Transaction? {
        val lower = text.lowercase()

        // 1. Hard exclude: OTP / verification messages must never be treated
        //    as transactions, and their content must not be retained.
        //
        //    IMPORTANT: this must not fire on the standard "Never share your
        //    OTP/PIN/CVV with anyone" disclaimer that real banks append to
        //    completely legitimate transaction SMS — a blanket substring
        //    check for "otp" anywhere in the message was doing exactly that,
        //    silently dropping real transactions purely because of this
        //    boilerplate trailer. Confirmed via a real Union Bank of India
        //    credit message ending "...Avl Bal Rs:2527.63.Never Share
        //    OTP/PIN/CVV-Union Bank of India" — this is very likely the
        //    actual cause of "some messages track, some don't" reported for
        //    this bank (and potentially any other bank using similar
        //    disclaimer phrasing, not a Union-Bank-specific bug). See
        //    REQUIREMENTS.md Decision Log 2026-09-20.
        //
        //    Fix: strip just that specific disclaimer clause before running
        //    the exclude-keyword scan, rather than loosening the scan
        //    itself — a genuine OTP-delivery message's substantive content
        //    ("Your OTP for login is 445566") doesn't match this narrow
        //    disclaimer pattern and remains correctly excluded either way.
        val textForExcludeCheck = SECURITY_DISCLAIMER_REGEX.replace(lower, " ")
        if (excludePatterns.any { it.containsMatchIn(textForExcludeCheck) }) {
            return null
        }

        // 2. Must look transactional at all
        if (config.transactionKeywords.none { lower.contains(it.lowercase()) }) {
            return null
        }

        // 2b. Must also reference an account/payment context, not just a
        // direction word. "credited"/"received"/"paid" alone are common
        // outside banking too — a telecom recharge confirmation
        // ("recharge... successfully credited to your Airtel number") or
        // an e-commerce order confirmation ("we have received your order
        // of amount INR 660") both contain a transactionKeyword but are
        // not transaction alerts, and were previously misparsed as money
        // received. Real bank/UPI alerts consistently reference an
        // account, card, or UPI context; recharge/order confirmations
        // essentially never do. See REQUIREMENTS.md Decision Log
        // 2026-09-12 (false positives found via real sample messages).
        if (ACCOUNT_CONTEXT_KEYWORDS.none { lower.contains(it) }) {
            return null
        }

        val hash = sha256(text)

        // Special case: "X paid you ₹Y" phrasing (seen from GPay-style
        // own-app notifications, as opposed to SMS bank alerts). This is
        // RECEIVED — the generic direction-keyword check below would
        // misclassify it as SENT purely because the word "paid" appears,
        // without noticing "paid YOU" means the other party paid the user.
        // Handled as its own branch because the name comes before the
        // amount here, the reverse of every bank_patterns.json regex's
        // (amount, then counterparty) group convention — trying to force
        // this into the shared convention isn't worth the complexity for
        // one phrasing. See REQUIREMENTS.md Decision Log 2026-08-20.
        val paidYouMatch = safeFind(PAID_YOU_REGEX, text)
        if (paidYouMatch != null) {
            val name = paidYouMatch.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            val amt = paidYouMatch.groupValues.getOrNull(2)?.replace(",", "")?.toDoubleOrNull()
            return Transaction(
                amount = amt ?: 0.0,
                direction = Direction.RECEIVED,
                merchantOrContact = name,
                bankOrSource = "UPI", // the raw title here is the whole descriptive sentence, not a clean bank/app code — see NotificationCaptureService
                timestampMillis = timestampMillis,
                category = Categorizer.categorize(name, text).name,
                balanceAfter = null,
                rawTextHash = hash,
                needsReview = amt == null
            )
        }

        // Determine direction FIRST from explicit keywords, independently of
        // which regex happens to match. This avoids a bug where a credit
        // message like "Rs.1.00 credited TO HDFC Bank A/c..." spuriously
        // matched the debit pattern's "to X" clause (since "to" is a common
        // preposition, not exclusive to debit messages) and got
        // misclassified as SENT. See REQUIREMENTS.md Decision Log 2026-08-16.
        val direction = detectDirection(text)

        // 3. Try sender-specific patterns first, then a generic UPI fallback
        val genericEntry = config.patterns.first { it.senderMatch == "GENERIC_UPI" }
        val specificEntry = config.patterns.firstOrNull { pattern ->
            pattern.senderMatch != "GENERIC_UPI" &&
                pattern.senderMatch.split("|").any { sender.contains(it, ignoreCase = true) }
        }
        val entry = specificEntry ?: genericEntry

        // Only run the regex matching the direction we already determined —
        // never try both and let whichever matches "win".
        val match = when (direction) {
            Direction.SENT -> safeFind(entry.debitedRegex, text) ?: (if (entry !== genericEntry) safeFind(genericEntry.debitedRegex, text) else null)
            Direction.RECEIVED -> safeFind(entry.creditedRegex, text) ?: (if (entry !== genericEntry) safeFind(genericEntry.creditedRegex, text) else null)
            Direction.UNKNOWN -> null
        }

        var amountStr = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val counterparty = match?.groupValues?.getOrNull(2)

        if (amountStr == null && direction != Direction.UNKNOWN) {
            amountStr = safeFind(FALLBACK_AMOUNT_REGEX, text)
                ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

        val amount = amountStr?.replace(",", "")?.toDoubleOrNull()
        val trimmedCounterparty = counterparty?.trim()?.takeIf { it.isNotBlank() }

        // Balance extraction is bank-agnostic (verified against real ECS and
        // Slice samples using "Avl Bal"/"Avl. Bal." phrasing) — applied to
        // every message rather than per-bank, since it's a single common
        // convention across banks. Absent if the message doesn't include it.
        val balanceMatch = safeFind(BALANCE_REGEX, text)
        val balanceAfter = balanceMatch?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()

        // If we couldn't confidently extract an amount, still record it but
        // flag for manual review rather than silently dropping it.
        return Transaction(
            amount = amount ?: 0.0,
            direction = direction,
            merchantOrContact = trimmedCounterparty,
            bankOrSource = sender,
            timestampMillis = timestampMillis,
            category = Categorizer.categorize(trimmedCounterparty, text).name,
            balanceAfter = balanceAfter,
            rawTextHash = hash,
            needsReview = amount == null || direction == Direction.UNKNOWN
        )
    }

    companion object {
        // 2026-09-06 fix: added optional ":" alongside the existing optional
        // "." after "rs" — a real Union Bank sample uses "Avl Bal Rs:15259.20"
        // (colon, not period), which the original rs\.? alternative couldn't
        // match at all, silently dropping balanceAfter for that bank's
        // messages. Same colon gap existed in bank_patterns.json's Union
        // Bank amount regexes, fixed alongside this.
        private const val BALANCE_REGEX = "(?i)avl\\.?\\s*bal\\.?\\s*[-:]?\\s*(?:rs\\.?:?|inr)\\s?([0-9,]+(?:\\.[0-9]{1,2})?)"
        private const val PAID_YOU_REGEX = "(?i)^(?:mr\\.?|mrs\\.?|ms\\.?)?\\s*([A-Za-z ]{2,60}?)\\s+paid you\\s+(?:rs\\.?|inr|₹)\\s?([0-9,]+(?:\\.[0-9]{1,2})?)"

        // Matches the standard "never/do not share your OTP/PIN/CVV..."
        // disclaimer clause that most Indian banks append to transaction
        // SMS. Deliberately narrow — requires "share" immediately followed
        // by one of otp/pin/cvv/password/card, so it only strips genuine
        // disclaimer boilerplate and can't accidentally eat substantive
        // message content. See the parse() comment above for why this
        // exists (a real Union Bank message was being silently dropped
        // because of this exact clause).
        // 2026-09-21: broadened to also match "one time password" (not
        // just "password") — same false-rejection bug, different
        // spelled-out wording. A message like "...Never share your one
        // time password with anyone -SBI" was falling through this
        // regex because "one time" sat between "your" and "password",
        // breaking the original adjacency assumption. Verified this
        // exact variant would otherwise still silently drop a real
        // transaction the same way the original Union Bank case did.
        // 2026-09-28: also accepts "disclose"/"reveal", an optional
        // "this"/"the" before the item, and "and"/"&" as list separators
        // (e.g. "Never disclose your OTP/PIN", "Do not share this OTP",
        // "Never share your card details, PIN and OTP").
        private const val DISCLAIMER_ITEM =
            "(?:otp|pin|cvv|password|(?:one\\s*time\\s*password)|card\\s*(?:details|number)?)"
        private val SECURITY_DISCLAIMER_REGEX = Regex(
            "(?:never|do\\s*not|don't)\\s+(?:share|disclose|reveal)\\s+(?:your\\s+|this\\s+|the\\s+)?" +
                DISCLAIMER_ITEM +
                "(?:\\s*(?:/|,|&|or|and)\\s*" + DISCLAIMER_ITEM + ")*\\b",
            RegexOption.IGNORE_CASE
        )

        private val STRONG_CREDIT_REGEX = Regex("\\b(credited|received)\\b", RegexOption.IGNORE_CASE)
        private val STRONG_DEBIT_REGEX = Regex("\\b(debited|sent|spent|withdrawn)\\b", RegexOption.IGNORE_CASE)
        private val WEAK_DEBIT_REGEX = Regex("\\b(paid|payment)\\b", RegexOption.IGNORE_CASE)

        /**
         * Direction from the FIRST strong keyword in the message.
         *
         * This used to be "credited/received anywhere => RECEIVED", which
         * misclassified debit alerts that mention a credit later on, e.g.
         * "Rs 500 debited from A/c ...; RAJESH credited", IMPS alerts
         * naming the beneficiary account as "credited", or a debit
         * followed by "cashback will be credited". The keyword that
         * appears first states what happened to the user's own account.
         * "paid"/"payment" are weak signals (they also appear in credit
         * messages such as "Payment of Rs X received"), so they only
         * decide when no strong keyword is present.
         */
        internal fun detectDirection(text: String): Direction {
            val credit = STRONG_CREDIT_REGEX.find(text)?.range?.first
            val debit = STRONG_DEBIT_REGEX.find(text)?.range?.first
            return when {
                credit != null && debit != null -> if (debit < credit) Direction.SENT else Direction.RECEIVED
                credit != null -> Direction.RECEIVED
                debit != null -> Direction.SENT
                WEAK_DEBIT_REGEX.containsMatchIn(text) -> Direction.SENT
                else -> Direction.UNKNOWN
            }
        }

        // Shared across all parser instances. A new single-thread executor
        // was previously created per TransactionParser and never shut down,
        // leaking a thread each time. Daemon threads in a cached pool, so a
        // regex that outlives its timeout can't block later parses.
        private val regexExecutor: java.util.concurrent.ExecutorService =
            Executors.newCachedThreadPool { r -> Thread(r, "regex-timeout").apply { isDaemon = true } }

        // Real bank/UPI alerts almost always reference one of these; a
        // recharge or order confirmation almost never does. Lowercase —
        // matched against the already-lowercased message text.
        private val ACCOUNT_CONTEXT_KEYWORDS = listOf(
            "a/c", "ac no", "acct", "account", "upi", "bank", "card ending",
            "card no", "wallet", "avl bal", "avl. bal"
        )

        // Fallback for messages that state the direction word immediately
        // next to the amount but skip any currency token entirely — e.g.
        // "A/C X8100 debited by 75.00 on date..." (no "Rs"/"INR"/"₹"
        // anywhere in the message). The primary per-bank/generic regexes
        // require a currency token before the digits and so silently miss
        // this phrasing (amount comes back null, needsReview=true even
        // though the number was right there). Anchored on "<direction
        // word> by" specifically — deliberately narrow, so it doesn't
        // accidentally grab a phone number or reference number elsewhere
        // in the message. Applied only when the primary match found no
        // amount.
        private const val FALLBACK_AMOUNT_REGEX =
            "(?i)\\b(?:debited|credited|withdrawn|spent|paid|received)\\s+by\\s+(?:rs\\.?|inr|₹)?\\s?([0-9,]+(?:\\.[0-9]{1,2})?)"
    }

    /** Runs regex.find with a hard timeout to prevent ReDoS from hanging the parser. */
    private fun safeFind(pattern: String, text: String): MatchResult? {
        val task = Callable { Regex(pattern).find(text) }
        val future = regexExecutor.submit(task)
        return try {
            future.get(200, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}