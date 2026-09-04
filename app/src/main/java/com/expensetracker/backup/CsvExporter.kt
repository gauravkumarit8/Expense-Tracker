package com.autoexpensetracker.backup

import com.autoexpensetracker.data.Transaction
import java.text.SimpleDateFormat
import java.util.*

/**
 * Builds a CSV file from transactions, for opening in Excel/Sheets — a
 * distinct use case from BackupPayload's JSON (which is for restoring into
 * this app). CSV drops reminders/budgets and flattens categories/direction
 * to plain text; it is not meant to be re-imported.
 */
object CsvExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun toCsv(transactions: List<Transaction>): String {
        val header = listOf(
            "Date", "Time", "Direction", "Amount", "Merchant", "Bank/Source",
            "Category", "Note", "Tags", "Balance After", "Needs Review"
        ).joinToString(",")

        val rows = transactions.sortedByDescending { it.timestampMillis }.map { tx ->
            val date = Date(tx.timestampMillis)
            listOf(
                dateFormat.format(date),
                timeFormat.format(date),
                tx.direction.name,
                "%.2f".format(tx.amount),
                tx.merchantOrContact.orEmpty(),
                tx.bankOrSource,
                tx.category.orEmpty(),
                tx.note.orEmpty(),
                tx.tags.orEmpty(),
                tx.balanceAfter?.let { "%.2f".format(it) } ?: "",
                if (tx.needsReview) "Yes" else "No"
            ).joinToString(",") { escapeCsvField(it) }
        }

        return (listOf(header) + rows).joinToString("\n")
    }

    /** Wraps a field in quotes and escapes internal quotes if it contains a comma, quote, or newline. */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
}