package com.autoexpensetracker.util

import android.content.Context
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.data.TransactionDao
import com.autoexpensetracker.util.CategoryRules.Rule

/**
 * SharedPreferences home for [CategoryRules], on this phone only. Included in
 * the JSON backup and wiped by "Delete all data" (the rules contain merchant
 * names, which are personal), like the other preference-backed stores.
 * Plain preferences rather than a database table, so adding the feature
 * needed no Room migration.
 */
object CategoryRuleStore {
    private const val PREFS = "category_rules"
    private const val KEY = "rules"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Rule> = CategoryRules.decode(prefs(context).getString(KEY, null))

    private fun save(context: Context, rules: List<Rule>): List<Rule> {
        prefs(context).edit().putString(KEY, CategoryRules.encode(rules)).apply()
        return rules
    }

    fun upsert(context: Context, rule: Rule): List<Rule> = save(context, CategoryRules.upsert(getAll(context), rule))

    fun remove(context: Context, key: String): List<Rule> = save(context, CategoryRules.remove(getAll(context), key))

    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /** One encoded line per rule, for the backup file. */
    fun exportAll(context: Context): List<String> = getAll(context).map { CategoryRules.encodeLine(it) }

    /** Replaces every rule with the backed-up ones. */
    fun replaceAll(context: Context, lines: List<String>) {
        save(context, CategoryRules.decode(lines.joinToString("\n")))
    }

    /**
     * Applied to each newly captured transaction before it is stored: a
     * merchant with a rule gets the rule's category instead of the keyword
     * guess. Never throws - a failure here must not lose the transaction.
     */
    fun applyTo(context: Context, transaction: Transaction): Transaction =
        try {
            val rule = CategoryRules.lookup(getAll(context), transaction.merchantOrContact)
            if (rule != null) transaction.copy(category = rule.category) else transaction
        } catch (e: Exception) {
            transaction
        }
}

/** The "apply to every transaction from this merchant" action, shared by the three screens that edit a transaction. */
object CategoryRuleActions {

    private const val BATCH = 500 // stays well under SQLite's bound-variable limit

    /**
     * [original] is the transaction before the edit and [edited] after it
     * (already saved). The merchant may have been renamed in the same edit, so
     * a rule is stored under BOTH names: the new one for the user's own view,
     * and the old one because that is what the parser will read from future
     * notifications. Returns how many other transactions were re-categorised.
     */
    suspend fun applyToMerchant(
        context: Context,
        dao: TransactionDao,
        original: Transaction,
        edited: Transaction
    ): Int {
        val category = edited.category ?: return 0
        val keys = LinkedHashMap<String, String>() // key -> display name
        CategoryRules.keyFor(edited.merchantOrContact)?.let { keys[it] = edited.merchantOrContact.orEmpty().trim() }
        CategoryRules.keyFor(original.merchantOrContact)?.let { if (it !in keys) keys[it] = original.merchantOrContact.orEmpty().trim() }
        if (keys.isEmpty()) return 0

        val ids = CategoryRules.idsToChange(dao.getAllOnce(), keys.keys, category, edited.id)
        for (batch in ids.chunked(BATCH)) dao.updateCategoryForIds(batch, category)
        for ((key, name) in keys) CategoryRuleStore.upsert(context, Rule(key, name.ifEmpty { key }, category))
        return ids.size
    }
}
