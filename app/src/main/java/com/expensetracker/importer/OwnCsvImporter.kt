package com.autoexpensetracker.importer

import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.data.TransactionDao
import com.autoexpensetracker.util.BankSourceMatcher
import java.security.MessageDigest

/**
 * Adds the rows of a CSV written by this app's "Export to CSV" back into the
 * database, keeping merchant, category, note, tags, balance and
 * needs-review. Additive: nothing existing is touched or deleted.
 *
 * Duplicates are detected by field (account, second, amount, direction)
 * against everything already in the app, NOT by the notification-text hash
 * or the 90-second cross-source window that live capture uses:
 *  - exported rows have no original notification text, so a hash can't match
 *    the copies already on the same phone;
 *  - the cross-source window would drop two real payments of the same amount
 *    made within 90 seconds of each other.
 * Importing the same file twice, or importing on the phone it came from,
 * therefore adds nothing - and a payment that really happened twice still
 * comes back twice.
 */
object OwnCsvImporter {

    suspend fun import(dao: TransactionDao, csvText: String): ImportResult {
        val parsed = OwnCsv.parse(csvText) // throws OwnCsv.ParseException - caller surfaces it

        val existing = HashMap<String, Int>()
        for (tx in dao.getAllOnce()) {
            val key = OwnCsv.dedupeKey(BankSourceMatcher.accountKey(tx.bankOrSource), tx.timestampMillis, tx.amount, tx.direction)
            existing[key] = (existing[key] ?: 0) + 1
        }

        val keys = parsed.rows.map {
            OwnCsv.dedupeKey(BankSourceMatcher.accountKey(it.bankOrSource), it.timestampMillis, it.amount, it.direction)
        }
        val plan = OwnCsv.plan(keys, existing)

        var imported = 0
        var duplicates = 0
        for (i in parsed.rows.indices) {
            val nth = plan[i]
            if (nth < 0) { duplicates++; continue }
            val row = parsed.rows[i]
            val id = dao.insert(
                Transaction(
                    amount = row.amount,
                    direction = row.direction,
                    merchantOrContact = row.merchant,
                    bankOrSource = row.bankOrSource,
                    timestampMillis = row.timestampMillis,
                    category = row.category,
                    note = row.note,
                    tags = row.tags,
                    balanceAfter = row.balanceAfter,
                    // No original notification text exists for an exported row.
                    // Derived from the row's identity so it is stable; used only
                    // as the dedup column's value, not for security.
                    rawTextHash = sha256("csvhistory|" + keys[i] + "|" + nth),
                    needsReview = row.needsReview
                )
            )
            if (id > 0) imported++ else duplicates++
        }
        return ImportResult(imported, duplicates, parsed.skippedRowCount)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
