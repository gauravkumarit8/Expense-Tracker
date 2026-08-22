package com.expensetracker.util

import com.expensetracker.data.Transaction
import kotlin.math.abs

/**
 * Detects likely cross-source duplicates: the same real payment captured
 * twice because it arrived via two different notification sources (e.g. a
 * payment app's own notification like "X paid you ₹Y" AND the bank's SMS
 * alert for the same UPI transfer, relayed through the default messaging
 * app). These have different raw text, so the existing exact-hash dedup
 * (`TransactionDao.existsByHash`) doesn't catch them.
 *
 * Deliberately narrow criteria to avoid false-positiving on genuinely
 * separate transactions that happen to share an amount (e.g. two ₹100
 * purchases within a few minutes):
 * - Same direction and amount (exact, not fuzzy — UPI amounts don't round)
 * - Different `bankOrSource` (same-source near-duplicates are vanishingly
 *   rare and would already be caught by exact-hash dedup if truly identical)
 * - A tight time window (90s) — cross-source duplicates for the same event
 *   fire within seconds of each other in practice; genuinely different
 *   transactions at the same amount happening within 90s are much rarer
 *   than within a looser multi-minute window
 * - Manual ("Cash") entries are excluded entirely — a deliberate manual
 *   entry should never be silently dropped as a "duplicate" of an
 *   unrelated bank capture that happens to share an amount
 *
 * See REQUIREMENTS.md ยง2.15 for the full reasoning and the alternative
 * (narrowing notification-source capture) that was considered and not
 * chosen, to preserve coverage for wallet-only payments with no bank SMS.
 */
object DuplicateDetector {

    private const val WINDOW_MILLIS = 90_000L // 90 seconds

    fun isLikelyDuplicate(candidate: Transaction, existing: List<Transaction>): Boolean {
        if (candidate.bankOrSource == "Cash") return false

        return existing.any { other ->
            other.bankOrSource != "Cash" &&
                other.bankOrSource != candidate.bankOrSource &&
                other.direction == candidate.direction &&
                abs(other.amount - candidate.amount) < 0.01 &&
                abs(other.timestampMillis - candidate.timestampMillis) <= WINDOW_MILLIS &&
                other.rawTextHash != candidate.rawTextHash
        }
    }
}