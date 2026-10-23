package com.autoexpensetracker.util

import com.autoexpensetracker.data.Transaction

/**
 * "Always put this merchant in this category." Pure rules (no Android, no
 * storage); [CategoryRuleStore] keeps them on the phone.
 *
 * A rule is keyed by the merchant's whole normalised name, NOT by
 * [MerchantMatcher.canonicalKey], which keeps only the first word on purpose
 * (fine for spotting a subscription, since the amounts must also agree). For
 * a category that is too coarse: "Ravi Kumar" and "Ravi Shankar" both key to
 * "ravi", and a rule made for one person would silently re-label the other.
 * The key here keeps every meaningful word, so "SWIGGY" and "UPI-SWIGGY-5002"
 * share a rule while "Ravi Kumar" and "Ravi Shankar" do not. The cost is that
 * "SWIGGY BANGALORE" is a separate merchant from "SWIGGY"; the user simply
 * applies the rule again, which is safer than a wrong guess.
 */
object CategoryRules {

    const val MAX_RULES = 500

    // Transaction-type words that appear around a merchant name but are not part of it.
    private val GENERIC = setOf(
        "upi", "pos", "neft", "imps", "rtgs", "atm", "ach", "nach", "txn",
        "vpa", "pay", "payment", "pymt", "bill", "ref", "via", "to", "from", "the"
    )

    /** [key] identifies the merchant, [displayName] is what the user typed or saw, [category] is a Category enum name. */
    data class Rule(val key: String, val displayName: String, val category: String)

    /**
     * The merchant's rule key, or null when there is nothing safe to key on
     * (no merchant, or only numbers and generic words such as "UPI 482913").
     * Letters of any script are kept, so Hindi names work.
     */
    fun keyFor(merchant: String?): String? {
        if (merchant == null) return null
        val sb = StringBuilder()
        for (ch in merchant) sb.append(if (Character.isLetterOrDigit(ch)) Character.toLowerCase(ch) else ' ')
        val tokens = sb.toString().split(" ").filter { it.isNotEmpty() }
        val kept = tokens.filter { t -> !t.all { it.isDigit() } && t !in GENERIC }
        return if (kept.isEmpty()) null else kept.joinToString(" ")
    }

    fun lookup(rules: List<Rule>, merchant: String?): Rule? {
        val key = keyFor(merchant) ?: return null
        return rules.firstOrNull { it.key == key }
    }

    /** Adds or replaces the rule for [rule].key, newest first, keeping at most [MAX_RULES]. */
    fun upsert(rules: List<Rule>, rule: Rule): List<Rule> {
        val out = ArrayList<Rule>()
        out.add(rule)
        for (r in rules) if (r.key != rule.key) out.add(r)
        return if (out.size > MAX_RULES) out.subList(0, MAX_RULES).toList() else out
    }

    fun remove(rules: List<Rule>, key: String): List<Rule> = rules.filter { it.key != key }

    /**
     * Ids of the OTHER transactions from the same merchant whose category
     * would change. A missing category counts as "OTHER", which is what the
     * app displays for it.
     */
    fun idsToChange(all: List<Transaction>, keys: Set<String>, newCategory: String, excludeId: Long): List<Long> =
        all.filter { tx ->
            tx.id != excludeId &&
                (tx.category ?: "OTHER") != newCategory &&
                keyFor(tx.merchantOrContact).let { it != null && it in keys }
        }.map { it.id }

    /**
     * Whether saving a category change should ask "apply to every
     * transaction from this merchant?": the merchant can be keyed, the
     * category really changed, and a rule for it does not already say so.
     */
    fun shouldAsk(existing: List<Rule>, merchant: String?, oldCategory: String?, newCategory: String): Boolean {
        if (keyFor(merchant) == null) return false
        if ((oldCategory ?: "OTHER") == newCategory) return false
        return lookup(existing, merchant)?.category != newCategory
    }

    // ---------- storage format: one rule per line, fields separated by a tab ----------

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encodeLine(r: Rule): String = clean(r.category) + "\t" + clean(r.key) + "\t" + clean(r.displayName)

    fun decodeLine(line: String): Rule? {
        val parts = line.split("\t")
        if (parts.size < 3) return null
        val category = parts[0].trim()
        val key = parts[1].trim()
        if (category.isEmpty() || key.isEmpty()) return null
        return Rule(key, parts.subList(2, parts.size).joinToString(" ").trim().ifEmpty { key }, category)
    }

    fun encode(rules: List<Rule>): String = rules.joinToString("\n") { encodeLine(it) }

    /** Tolerates anything: a blank or damaged store reads as no rules. Duplicates keep the first. */
    fun decode(stored: String?): List<Rule> {
        if (stored == null) return emptyList()
        val out = ArrayList<Rule>()
        val seen = HashSet<String>()
        for (line in stored.split("\n")) {
            val r = decodeLine(line) ?: continue
            if (seen.add(r.key)) out.add(r)
            if (out.size >= MAX_RULES) break
        }
        return out
    }
}
