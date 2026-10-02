package com.autoexpensetracker.util

/**
 * Groups merchant strings that are the same real-world merchant written
 * differently — "SWIGGY BANGALORE", "SWIGGY*BANGALORE" and "Swiggy" should
 * all count as one merchant, not three. Previously both [RecurringDetector]
 * and [SubscriptionPriceChangeDetector] grouped by trim+lowercase only, so
 * any punctuation, city suffix, or trailing reference number appended to a
 * merchant string (very common in UPI/card transaction descriptions)
 * silently split what should have been one recurring merchant into several
 * with too few occurrences each to ever be detected.
 *
 * Deliberately simple, matching the project's other detectors (no ML, no
 * edit-distance/similarity scoring): strip punctuation, drop purely-numeric
 * tokens (reference numbers, order IDs) and a fixed list of generic
 * transaction-type words (upi/pos/neft/...), and use the first remaining
 * token as the canonical "brand" key.
 *
 * Known tradeoff, accepted deliberately: this can OVER-merge two distinct
 * things that share a first word — "GOOGLE *YOUTUBEPREM" and
 * "GOOGLE *YOUTUBEMUSIC" both key to "google". This is safe in practice
 * because both callers still require every transaction in the resulting
 * group to be within their own amount-consistency tolerance of the
 * group's average — two genuinely different subscriptions almost always
 * have different prices, so an over-merged group simply fails that check
 * and produces no suggestion, rather than a wrong one. Verified against
 * constructed cases (including a specific "UPI-SWIGGY-500200" vs
 * "UPI-ZOMATO-500201" trap, to confirm a shared "UPI-" prefix doesn't
 * merge unrelated brands) before being wired in — see the decision log.
 */
object MerchantMatcher {

    private val GENERIC_PREFIX_TOKENS = setOf(
        "upi", "pos", "neft", "imps", "rtgs", "atm", "ach", "nach", "txn",
        "vpa", "pay", "payment", "pymt", "bill", "ref", "via", "to", "from", "the"
    )

    fun canonicalKey(rawMerchant: String): String {
        val tokens = tokenize(rawMerchant)
        if (tokens.isEmpty()) return rawMerchant.trim().lowercase()
        val first = tokens[0]
        // A very short first token ("hp", "sbi") is often not distinctive
        // enough on its own to safely group by; fold in the next token too
        // when one exists.
        return if (first.length < 3 && tokens.size > 1) "$first ${tokens[1]}" else first
    }

    private fun tokenize(raw: String): List<String> {
        val normalized = raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        val allTokens = normalized.split(" ")
        val kept = allTokens.filter { it.isNotEmpty() && !it.all { c -> c.isDigit() } && it !in GENERIC_PREFIX_TOKENS }
        // Never return empty - fall back to the unfiltered tokens rather
        // than losing the merchant entirely (e.g. a merchant string that's
        // ONLY generic words/numbers after normalization).
        return kept.ifEmpty { allTokens }
    }
}
