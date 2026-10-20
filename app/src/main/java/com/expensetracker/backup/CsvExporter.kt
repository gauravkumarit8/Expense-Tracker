package com.autoexpensetracker.backup

import com.autoexpensetracker.data.Transaction
import java.text.SimpleDateFormat
import java.util.*

/**
 * Builds a CSV file from transactions, for opening in Excel/Sheets and for
 * moving transaction history to another phone: `OwnCsv` (importer package)
 * reads this exact layout back with every column intact. It carries
 * transactions only; reminders, budgets and manual balances travel in the
 * JSON backup (BackupPayload).
 *
 * Dates, times and numbers are written with a fixed locale (US), so a phone
 * set to a language that uses other digits or a decimal comma still writes a
 * file that reads back. The last column is the exact timestamp in epoch
 * milliseconds: it survives a change of time zone between the two phones,
 * which the local Date and Time columns cannot.
 */
object CsvExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun toCsv(transactions: List<Transaction>): String {
        val header = listOf(
            "Date", "Time", "Direction", "Amount", "Merchant", "Bank/Source",
            "Category", "Note", "Tags", "Balance After", "Needs Review", "Timestamp (ms)"
        ).joinToString(",")

        val rows = transactions.sortedByDescending { it.timestampMillis }.map { tx ->
            val date = Date(tx.timestampMillis)
            listOf(
                dateFormat.format(date),
                timeFormat.format(date),
                tx.direction.name,
                "%.2f".format(Locale.US, tx.amount),
                neutralizeFormula(tx.merchantOrContact.orEmpty()),
                neutralizeFormula(tx.bankOrSource),
                neutralizeFormula(tx.category.orEmpty()),
                neutralizeFormula(tx.note.orEmpty()),
                neutralizeFormula(tx.tags.orEmpty()),
                tx.balanceAfter?.let { "%.2f".format(Locale.US, it) } ?: "",
                if (tx.needsReview) "Yes" else "No",
                tx.timestampMillis.toString()
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