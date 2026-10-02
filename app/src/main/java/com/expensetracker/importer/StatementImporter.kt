package com.autoexpensetracker.importer

import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.data.TransactionDao
import com.autoexpensetracker.parser.Categorizer
import java.security.MessageDigest
import java.util.Calendar

data class ImportResult(
    val imported: Int,
    val skippedAsDuplicate: Int,
    val skippedUnparseable: Int
)

/**
 * Statement rows only carry a DATE, not a time of day (most exports show
 * midnight or noon for every row), so the existing 90-second cross-source
 * duplicate window in `TransactionDao.insertIfNotDuplicate` — sized for two
 * apps reporting the same real-world payment moments apart — essentially
 * never fires for a statement row that duplicates an already
 * notification-captured transaction from earlier the same day. This runs
 * a same-CALENDAR-DAY check first (direction + amount, ignoring time of
 * day) specifically for that case, then still calls
 * `insertIfNotDuplicate` as a second safety net for anything with a real
 * timestamp.
 */
object StatementImporter {

    suspend fun import(
        dao: TransactionDao,
        csvText: String,
        accountLabel: String
    ): ImportResult {
        val parsed = StatementCsvParser.parse(csvText) // throws StatementCsvParser.ParseException — caller decides how to surface it
        val source = accountLabel.trim().ifBlank { "Bank Statement" }

        var imported = 0
        var duplicates = 0

        for (row in parsed.rows) {
            if (isLikelyAlreadyCaptured(dao, row)) {
                duplicates++
                continue
            }

            val transaction = Transaction(
                amount = row.amount,
                direction = row.direction,
                merchantOrContact = row.description.trim().takeIf { it.isNotBlank() },
                bankOrSource = source,
                timestampMillis = row.timestampMillis,
                category = Categorizer.categorize(row.description, row.description).name,
                balanceAfter = row.balanceAfter,
                // Statement rows have no original notification text to hash.
                // This exists only so re-importing the exact same CSV twice
                // doesn't create a second copy of every row — it isn't a
                // security-relevant hash the way the notification-capture
                // one is.
                rawTextHash = sha256("import|$source|${row.timestampMillis}|${row.amount}|${row.direction}|${row.description}"),
                needsReview = false
            )

            when (dao.insertIfNotDuplicate(transaction)) {
                is com.autoexpensetracker.data.InsertOutcome.Inserted -> imported++
                is com.autoexpensetracker.data.InsertOutcome.ExactDuplicateSkipped,
                is com.autoexpensetracker.data.InsertOutcome.CrossSourceDuplicateSkipped -> duplicates++
            }
        }

        return ImportResult(imported, duplicates, parsed.skippedRowCount)
    }

    private suspend fun isLikelyAlreadyCaptured(dao: TransactionDao, row: StatementCsvParser.ParsedRow): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = row.timestampMillis }
        val startOfDay = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endOfDay = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        return dao.getNearTimestamp(startOfDay, endOfDay).any { existing ->
            existing.direction == row.direction && amountsMatch(existing.amount, row.amount)
        }
    }

    private fun amountsMatch(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.01

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
