package com.autoexpensetracker.backup

import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.pdf.PdfPeriod
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Builds a CSV file from transactions, for opening in Excel/Sheets and for
 * moving transaction history to another phone: `OwnCsv` (importer package)
 * reads this exact layout back. It carries transactions only; reminders,
 * budgets and manual balances travel in the JSON backup (BackupPayload).
 *
 * Written for the way people actually open it:
 *  - UTF-8 WITH a byte-order mark ([toBytes]). Excel on Windows assumes a
 *    legacy code page for a BOM-less file and shows Hindi or accented
 *    merchant names as garbage; Sheets, Numbers and the importer here all
 *    accept the BOM.
 *  - CRLF line endings (RFC 4180), so even Notepad shows separate rows.
 *  - "Signed Amount": negative for money sent, positive for money received,
 *    blank when the direction is unknown, so a plain SUM gives the net.
 *    ("Amount" stays positive, which is what the importer reads.)
 *  - The category by its display name ("Food & Dining"), not "FOOD".
 *  - Dates, times and numbers use a fixed locale (US), so a phone set to a
 *    language with other digits, a decimal comma or a non-Gregorian calendar
 *    still writes a file that reads back.
 *  - The last column is the exact time in epoch SECONDS. It survives a
 *    change of time zone between phones, which Date and Time cannot, and ten
 *    digits display in full in Excel where a 13-digit millisecond value is
 *    shown as 1.76E+12. (Files from earlier builds carry "Timestamp (ms)";
 *    the importer still reads those.)
 */
object CsvExporter {

    const val BOM = "\uFEFF"
    private const val EOL = "\r\n"

    private val HEADER = listOf(
        "Date", "Time", "Direction", "Amount", "Signed Amount", "Merchant", "Bank/Source",
        "Category", "Note", "Tags", "Balance After", "Needs Review", "Timestamp (s)"
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** The CSV text, newest transaction first, without the BOM. */
    fun toCsv(transactions: List<Transaction>): String {
        val header = HEADER.joinToString(",")

        val rows = transactions.sortedByDescending { it.timestampMillis }.map { tx ->
            val date = Date(tx.timestampMillis)
            val signed = when (tx.direction) {
                Direction.SENT -> "-" + "%.2f".format(Locale.US, tx.amount)
                Direction.RECEIVED -> "%.2f".format(Locale.US, tx.amount)
                Direction.UNKNOWN -> ""
            }
            listOf(
                dateFormat.format(date),
                timeFormat.format(date),
                tx.direction.name,
                "%.2f".format(Locale.US, tx.amount),
                signed,
                neutralizeFormula(tx.merchantOrContact.orEmpty()),
                neutralizeFormula(tx.bankOrSource),
                neutralizeFormula(Category.fromNameOrNull(tx.category)?.label.orEmpty()),
                neutralizeFormula(tx.note.orEmpty()),
                neutralizeFormula(tx.tags.orEmpty()),
                tx.balanceAfter?.let { "%.2f".format(Locale.US, it) } ?: "",
                if (tx.needsReview) "Yes" else "No",
                (tx.timestampMillis / 1000L).toString()
            ).joinToString(",") { escapeCsvField(it) }
        }

        return (listOf(header) + rows).joinToString(EOL) + EOL
    }

    /** What is written to the file: [toCsv] as UTF-8 preceded by the byte-order mark. */
    fun toBytes(transactions: List<Transaction>): ByteArray =
        (BOM + toCsv(transactions)).toByteArray(Charsets.UTF_8)

    /**
     * File name for an export: "expense_tracker_2026-10-12.csv" for a
     * whole-history export (today's date), or named for the day, month or
     * year when one period is exported. Digits are always ASCII.
     */
    fun fileName(period: PdfPeriod?, nowMillis: Long): String {
        fun two(n: Int) = if (n < 10) "0$n" else n.toString()
        return when (period) {
            is PdfPeriod.Day -> "expense_tracker_${period.year}-${two(period.month + 1)}-${two(period.day)}.csv"
            is PdfPeriod.Month -> "expense_tracker_${period.year}-${two(period.month + 1)}.csv"
            is PdfPeriod.Year -> "expense_tracker_${period.year}.csv"
            null -> {
                // Locale.US pins the Gregorian calendar: a Thai-calendar locale
                // would otherwise name the file for year 2569.
                val c = Calendar.getInstance(Locale.US)
                c.timeInMillis = nowMillis
                "expense_tracker_${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-${two(c.get(Calendar.DAY_OF_MONTH))}.csv"
            }
        }
    }

    /**
     * CSV/formula injection guard. Text fields here come from notification
     * titles/bodies and free-text notes; a value starting with = + - @ (or
     * a tab/CR) is executed as a formula when the file is opened in Excel
     * or Sheets. Prefixing a single quote makes spreadsheets treat it as
     * plain text. Applied ONLY to free-text columns - never to Amount,
     * Signed Amount or Balance After, where a leading "-" is a legitimate
     * negative number.
     */
    private fun neutralizeFormula(field: String): String =
        if (field.isNotEmpty() && field[0] in "=+-@\t\r") "'" + field else field

    /**
     * Wraps a field in quotes and doubles internal quotes if it contains a
     * comma, quote, or any line break. A bare carriage return counts: left
     * unquoted it ends the row in most readers and splits one transaction
     * into two.
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
}
