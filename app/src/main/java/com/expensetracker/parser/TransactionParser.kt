package com.expensetracker.parser

import android.content.Context
import com.expensetracker.data.Direction
import com.expensetracker.data.Transaction
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
        if (config.excludeKeywords.any { lower.contains(it.lowercase()) }) {
            return null
        }

        // 2. Must look transactional at all
        if (config.transactionKeywords.none { lower.contains(it.lowercase()) }) {
            return null
        }

        val hash = sha256(text)

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

        val amountStr = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val counterparty = match?.groupValues?.getOrNull(2)

        val amount = amountStr?.replace(",", "")?.toDoubleOrNull()
        val trimmedCounterparty = counterparty?.trim()?.takeIf { it.isNotBlank() }

        // If we couldn't confidently extract an amount, still record it but
        // flag for manual review rather than silently dropping it.
        return Transaction(
            amount = amount ?: 0.0,
            direction = direction,
            merchantOrContact = trimmedCounterparty,
            bankOrSource = sender,
            timestampMillis = timestampMillis,
            category = Categorizer.categorize(trimmedCounterparty, text).name,
            rawTextHash = hash,
            needsReview = amount == null || direction == Direction.UNKNOWN
        )
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