package com.autoexpensetracker.util

import com.autoexpensetracker.data.Transaction
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
 * - Counterparty name, when BOTH sides captured one, must be compatible
 *   (2026-09-21: this was missing entirely — two different people paying
 *   the exact same amount within the 90s window, via different channels,
 *   were being merged into a single transaction, silently dropping one of
 *   two genuinely separate real payments. A real cross-source duplicate is
 *   the SAME payment seen twice, so it has the SAME counterparty on both
 *   sides whenever both paths manage to capture a name; two different
 *   people's payments do not. Left permissive when either side has no
 *   captured name at all — common with the generic UPI fallback pattern —
 *   since that's a real gap in what was actually parsed, not evidence the
 *   payment differs.)
 * - Manual ("Cash") entries are excluded entirely — a deliberate manual
 *   entry should never be silently dropped as a "duplicate" of an
 *   unrelated bank capture that happens to share an amount
 *
 * See REQUIREMENTS.md ยง2.15 for the full reasoning and the alternative
 * (narrowing notification-source capture) that was considered and not
 * chosen, to preserve coverage for wallet-only payments with no bank SMS.
 *
 * Order-independence note (ยง2.15 amendment, 2026-09-02): this matching
 * logic was already order-independent — it looks for *any* existing row
 * matching the criteria below regardless of which `bankOrSource` arrived
 * first. The bug that caused duplicates to still appear in practice was a
 * check-then-insert race condition in the caller
 * (`TransactionDao.insertIfNotDuplicate`), not an ordering assumption here.
 */
object DuplicateDetector {

    private const val WINDOW_MILLIS = 90_000L // 90 seconds

    /**
     * Returns the existing transaction [candidate] is a likely cross-source
     * duplicate of, or null if none matches. Prefer this over
     * [isLikelyDuplicate] when the caller needs to reference *which* row was
     * the original (e.g. for logging or returning `existingId`).
     */
    fun findDuplicate(candidate: Transaction, existing: List<Transaction>): Transaction? {
        if (candidate.bankOrSource == "Cash") return null

        return existing.firstOrNull { other ->
            other.bankOrSource != "Cash" &&
                other.bankOrSource != candidate.bankOrSource &&
                other.direction == candidate.direction &&
                abs(other.amount - candidate.amount) < 0.01 &&
                abs(other.timestampMillis - candidate.timestampMillis) <= WINDOW_MILLIS &&
                other.rawTextHash != candidate.rawTextHash &&
                namesCompatible(other.merchantOrContact, candidate.merchantOrContact)
        }
    }

    /**
     * True if [a] and [b] could plausibly be the same counterparty — either
     * one is missing (can't rule out a match with no name to compare) or
     * they're the same name after normalizing case/whitespace, or one
     * contains the other (handles a full name on one side and a shortened
     * or partial capture on the other, which the two different parsing
     * paths behind a real cross-source duplicate commonly produce).
     */
    private fun namesCompatible(a: String?, b: String?): Boolean {
        val na = a?.trim()?.lowercase()
        val nb = b?.trim()?.lowercase()
        if (na.isNullOrBlank() || nb.isNullOrBlank()) return true
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    fun isLikelyDuplicate(candidate: Transaction, existing: List<Transaction>): Boolean =
        findDuplicate(candidate, existing) != null
}