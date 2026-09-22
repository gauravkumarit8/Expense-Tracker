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
    // Single-thread executor used only to enforce a hard timeout per regex
    // evaluation, guarding against catastrophic backtracking (ReDoS) if a
    // pattern is ever misconfigured. See REQUIREMENTS.md Security ยง6.
    private val regexExecutor = Executors.newSingleThreadExecutor()

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
        if (config.excludeKeywords.any { textForExcludeCheck.contains(it.lowercase()) }) {
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
        val direction = when {
            Regex("\\b(credited|received)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Direction.RECEIVED
            Regex("\\b(debited|sent|spent|withdrawn|paid|payment)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Direction.SENT
            else -> Direction.UNKNOWN
        }

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
        private val SECURITY_DISCLAIMER_REGEX = Regex(
            "(?:never|do\\s*not|don't)\\s+share\\s+(?:your\\s+)?" +
                "(?:otp|pin|cvv|password|card\\s*(?:details|number)?)" +
                "(?:\\s*(?:/|,|or)\\s*(?:otp|pin|cvv|password|card\\s*(?:details|number)?))*\\b",
            RegexOption.IGNORE_CASE
        )

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