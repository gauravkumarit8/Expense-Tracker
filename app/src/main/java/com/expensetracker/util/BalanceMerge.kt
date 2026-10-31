package com.autoexpensetracker.util

/**
 * Decides which figure the "Account balances" card shows when an account has
 * both an auto-detected balance (parsed from a bank alert) and a balance the
 * user typed in by hand.
 *
 * Rule: the NEWER of the two wins. Before 2026-10-31 a hand-typed figure won
 * forever, so after one manual edit every later bank alert was ignored for
 * that card and the balance never updated again. Now a typed figure holds only
 * until the next captured alert that carries a balance; after that the card
 * follows the bank again. A tie goes to the typed figure (the user's explicit
 * word). Alerts older than the edit (late or back-filled) never override it.
 *
 * Pure (no Android, no storage) so it is unit-tested on its own.
 */
object BalanceMerge {

    data class Candidate(val source: String, val amount: Double, val asOfMillis: Long)

    data class Resolved(val source: String, val amount: Double, val asOfMillis: Long, val isManual: Boolean)

    /**
     * @param auto one candidate per captured transaction that carried a balance
     *             (the latest per account is picked here, grouped by account key)
     * @param manual every manual entry (the latest per account key is used)
     * @param hiddenSources sources the user removed from the list
     */
    fun resolve(auto: List<Candidate>, manual: List<Candidate>, hiddenSources: Set<String>): List<Resolved> {
        val autoLatest = HashMap<String, Candidate>()
        for (c in auto) {
            val key = BankSourceMatcher.accountKey(c.source)
            val cur = autoLatest[key]
            if (cur == null || c.asOfMillis > cur.asOfMillis) autoLatest[key] = c
        }
        val manualLatest = HashMap<String, Candidate>()
        for (c in manual) {
            val key = BankSourceMatcher.accountKey(c.source)
            val cur = manualLatest[key]
            if (cur == null || c.asOfMillis > cur.asOfMillis) manualLatest[key] = c
        }
        // Hiding ("deleting") an AUTO-detected card hides every route variant of that bank.
        val hiddenKeys = HashSet<String>()
        for (h in hiddenSources) hiddenKeys.add(BankSourceMatcher.accountKey(h))

        val out = ArrayList<Resolved>()
        for ((key, a) in autoLatest) {
            if (hiddenKeys.contains(key)) continue
            val m = manualLatest[key]
            if (m == null || a.asOfMillis > m.asOfMillis) {
                out.add(Resolved(a.source, a.amount, a.asOfMillis, isManual = false))
            }
        }
        for ((key, m) in manualLatest) {
            if (hiddenSources.contains(m.source)) continue
            val a = autoLatest[key]
            val autoVisible = a != null && !hiddenKeys.contains(key)
            if (!autoVisible || m.asOfMillis >= a!!.asOfMillis) {
                out.add(Resolved(m.source, m.amount, m.asOfMillis, isManual = true))
            }
        }
        return out.sortedByDescending { it.asOfMillis }
    }
}
