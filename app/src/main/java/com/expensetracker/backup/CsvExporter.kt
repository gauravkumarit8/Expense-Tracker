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
                neutralizeFormula(tx.merchantOrContact.orEmpty()),
                neutralizeFormula(tx.bankOrSource),
                neutralizeFormula(tx.category.orEmpty()),
                neutralizeFormula(tx.note.orEmpty()),
                neutralizeFormula(tx.tags.orEmpty()),
                tx.balanceAfter?.let { "%.2f".format(it) } ?: "",
                if (tx.needsReview) "Yes" else "No"
            ).joinToString(",") { escapeCsvField(it) }
        }

        return (listOf(header) + rows).joinToString("\n")
    }

    /**
     * CSV/formula injection guard. Text fields here come from notification
     * titles/bodies and free-text notes; a value starting with = + - @ (or
     * a tab/CR) is executed as a formula when the file is opened in Excel
     * or Sheets. Prefixing a single quote makes spreadsheets treat it as
     * plain text. Applied ONLY to free-text columns - never to Amount or
     * Balance After, where a leading "-" is a legitimate negative number.
     */
    private fun neutralizeFormula(field: String): String =
        if (field.isNotEmpty() && field[0] in "=+-@\t\r") "'" + field else field

    /** Wraps a field in quotes and escapes internal quotes if it contains a comma, quote, or newline. */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
}