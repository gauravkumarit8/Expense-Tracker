package com.autoexpensetracker.util

/**
 * Pure rules behind the Search screen (no Android, no Room types) so they
 * can be compiled and tested on their own.
 */
object SearchLogic {

    // Currency prefix is optional; the number is plain digits, Western
    // grouping (1,500,000) or Indian grouping (1,50,000). Anything else -
    // "1,2,3", "12,34", "1,5000" - is not an amount.
    private val AMOUNT = Regex(
        "^(?:₹|rs\\.?|inr)?\\s*(\\d+(?:\\.\\d{1,2})?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d{1,2}(?:,\\d{2})*,\\d{3}(?:\\.\\d{1,2})?)$",
        RegexOption.IGNORE_CASE
    )

    /** The search text as an exact rupee amount, or null if it isn't one. Accepts 340, ₹340, Rs. 1,500, 1,50,000, 99.50. */
    fun parseAmountQuery(raw: String): Double? {
        val match = AMOUNT.matchEntire(raw.trim()) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    /** Exact match to the paisa - "contains" would make 340 find 1340 and 3400. */
    fun amountMatches(txAmount: Double, queryAmount: Double): Boolean =
        Math.abs(txAmount - queryAmount) < 0.005

    /**
     * Whether the typed text names this category, by display label ("Food &
     * Dining") or enum name ("FOOD"). Needs two characters so a single typed
     * letter doesn't light up half the categories.
     */
    fun categoryMatches(query: String, categoryName: String?, categoryLabel: String?): Boolean {
        val q = query.trim()
        if (q.length < 2) return false
        return (categoryName != null && categoryName.contains(q, ignoreCase = true)) ||
            (categoryLabel != null && categoryLabel.contains(q, ignoreCase = true))
    }

    const val MAX_RECENT = 8
    private const val MIN_RECORDABLE_LENGTH = 2

    /** Collapses runs of whitespace and trims; the form a query is stored and compared in. */
    fun normalizeQuery(raw: String): String {
        val sb = StringBuilder()
        var lastSpace = true
        for (ch in raw) {
            if (ch.isWhitespace()) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(ch)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /**
     * New recent-searches list after searching for [query]: newest first,
     * de-duplicated ignoring case (searching "swiggy" after "Swiggy" moves it
     * to the top rather than adding a second entry), capped at [cap]. Queries
     * shorter than two characters are not worth remembering.
     */
    fun addRecent(existing: List<String>, query: String, cap: Int = MAX_RECENT): List<String> {
        val q = normalizeQuery(query)
        if (q.length < MIN_RECORDABLE_LENGTH) return existing
        val result = ArrayList<String>()
        result.add(q)
        for (e in existing) {
            if (!e.equals(q, ignoreCase = true)) result.add(e)
        }
        return if (result.size > cap) result.subList(0, cap).toList() else result
    }

    fun removeRecent(existing: List<String>, query: String): List<String> =
        existing.filter { !it.equals(query, ignoreCase = true) }

    /** One entry per line - safe because the search box is single-line. */
    fun encodeRecent(items: List<String>): String = items.joinToString("\n")

    fun decodeRecent(stored: String?): List<String> {
        if (stored == null) return emptyList()
        val out = ArrayList<String>()
        for (line in stored.split("\n")) {
            val q = normalizeQuery(line)
            if (q.isNotEmpty() && out.none { it.equals(q, ignoreCase = true) }) out.add(q)
            if (out.size >= MAX_RECENT) break
        }
        return out
    }

    /** Category names ordered by total spend, highest first, at most [n]; null categories are skipped. */
    fun topCategoryNames(spends: List<Pair<String?, Double>>, n: Int): List<String> {
        val totals = LinkedHashMap<String, Double>()
        for (s in spends) {
            val name = s.first ?: continue
            totals[name] = (totals[name] ?: 0.0) + s.second
        }
        return totals.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }
}
