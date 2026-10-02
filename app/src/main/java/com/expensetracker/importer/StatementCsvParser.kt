package com.autoexpensetracker.importer

import com.autoexpensetracker.data.Direction
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses a bank statement CSV export into structured rows. Notification
 * capture only sees data from the day the app is installed forward — this
 * lets a new user backfill months of history from a statement their bank
 * already lets them download.
 *
 * Deliberately auto-detects column layout from a fixed set of header-name
 * variants seen across common Indian bank exports (HDFC/ICICI/SBI/Axis
 * style), rather than a manual column-mapping UI — simpler for a first
 * version, at the cost of not handling a bank whose export uses headers
 * outside this list. See [DATE_HEADERS] etc. below for exactly what's
 * recognized; [ParseException] is thrown with a specific reason when
 * nothing matches, rather than silently importing nothing.
 *
 * Verified against constructed samples mimicking HDFC/ICICI/SBI-style
 * exports (debit+credit columns, a single amount+Dr/Cr-type column,
 * comma-formatted amounts, quoted narrations with embedded commas, and a
 * row with an unparseable date) before being wired into the app — see the
 * decision log for 2026-09-28. Not yet verified against a REAL downloaded
 * statement from any bank; the header-variant list is what's most commonly
 * documented, not something pulled from an actual export file.
 */
object StatementCsvParser {

    data class ParsedRow(
        val timestampMillis: Long,
        val amount: Double,
        val direction: Direction,
        val description: String,
        val balanceAfter: Double?
    )

    data class ParseResult(
        val rows: List<ParsedRow>,
        val skippedRowCount: Int
    )

    class ParseException(message: String) : Exception(message)

    private val DATE_HEADERS = setOf("date", "txndate", "transactiondate", "valuedate", "postingdate")
    private val DESC_HEADERS = setOf("narration", "description", "particulars", "transactionremarks", "details", "remarks")
    private val DEBIT_HEADERS = setOf("debit", "withdrawalamt", "withdrawalamount", "dr", "debitamount", "withdrawal")
    private val CREDIT_HEADERS = setOf("credit", "depositamt", "depositamount", "cr", "creditamount", "deposit")
    private val BALANCE_HEADERS = setOf("balance", "closingbalance", "availablebalance", "balanceamount")
    private val AMOUNT_HEADERS = setOf("amount", "transactionamount")
    private val TYPE_HEADERS = setOf("type", "drcr", "crdr", "transactiontype")

    // Tried in order; first successful parse wins. Two-digit-year and
    // US-style m/d/y are last since they're the most ambiguous.
    private val DATE_FORMATS = listOf(
        "dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd", "dd MMM yyyy", "dd-MMM-yyyy",
        "dd/MM/yy", "MM/dd/yyyy"
    ).map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = false } }

    fun parse(csvText: String): ParseResult {
        val lines = csvText.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ParseException("The file is empty")

        val headerRaw = splitCsvLine(lines[0])
        val headerNorm = headerRaw.map { normalizeHeader(it) }

        fun findColumn(candidates: Set<String>): Int? = headerNorm.indexOfFirst { it in candidates }.takeIf { it >= 0 }

        val dateIdx = findColumn(DATE_HEADERS)
            ?: throw ParseException("Couldn't find a date column in the header row")
        val descIdx = findColumn(DESC_HEADERS)
        val debitIdx = findColumn(DEBIT_HEADERS)
        val creditIdx = findColumn(CREDIT_HEADERS)
        val balanceIdx = findColumn(BALANCE_HEADERS)
        val amountIdx = findColumn(AMOUNT_HEADERS)
        val typeIdx = findColumn(TYPE_HEADERS)

        val hasSplitColumns = debitIdx != null || creditIdx != null
        val hasCombinedColumns = amountIdx != null && typeIdx != null
        if (!hasSplitColumns && !hasCombinedColumns) {
            throw ParseException("Couldn't find debit/credit columns or an amount+type column in the header row")
        }

        val rows = mutableListOf<ParsedRow>()
        var skipped = 0

        for (line in lines.drop(1)) {
            val fields = splitCsvLine(line)
            if (dateIdx >= fields.size) { skipped++; continue }
            val date = parseDate(fields[dateIdx])
            if (date == null) { skipped++; continue }

            val description = descIdx?.takeIf { it < fields.size }?.let { fields[it] }.orEmpty()
            val balance = balanceIdx?.takeIf { it < fields.size }?.let { parsePlainDecimal(fields[it]) }

            var direction: Direction? = null
            var amount: Double? = null

            if (hasSplitColumns) {
                val debit = debitIdx?.takeIf { it < fields.size }?.let { parseAmount(fields[it]) }
                val credit = creditIdx?.takeIf { it < fields.size }?.let { parseAmount(fields[it]) }
                if (debit != null) {
                    direction = Direction.SENT; amount = debit
                } else if (credit != null) {
                    direction = Direction.RECEIVED; amount = credit
                }
            } else {
                val amt = amountIdx?.takeIf { it < fields.size }?.let { parseAmount(fields[it]) }
                val type = typeIdx?.takeIf { it < fields.size }?.let { fields[it].trim().lowercase() }.orEmpty()
                if (amt != null) {
                    when (type) {
                        "dr", "debit", "withdrawal" -> { direction = Direction.SENT; amount = amt }
                        "cr", "credit", "deposit" -> { direction = Direction.RECEIVED; amount = amt }
                    }
                }
            }

            if (direction == null || amount == null) { skipped++; continue }
            rows.add(ParsedRow(date.time, amount, direction, description, balance))
        }

        return ParseResult(rows, skipped)
    }

    private fun normalizeHeader(h: String): String = h.trim().lowercase().filter { it.isLetterOrDigit() }

    /** Amount that must be a positive number to count (blank/zero/"-" means "not this column"). */
    private fun parseAmount(raw: String): Double? {
        val cleaned = raw.trim().replace(",", "").replace("₹", "").replace(Regex("(?i)rs\\.?"), "")
        if (cleaned.isEmpty() || cleaned == "-") return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return value.takeIf { it > 0.0 }
    }

    private fun parsePlainDecimal(raw: String): Double? =
        raw.trim().replace(",", "").replace("₹", "").toDoubleOrNull()

    private fun parseDate(raw: String): java.util.Date? {
        val trimmed = raw.trim()
        for (format in DATE_FORMATS) {
            try {
                return format.parse(trimmed)
            } catch (e: java.text.ParseException) {
                // try the next format
            }
        }
        return null
    }

    /** Minimal RFC4180 line splitter: handles quoted fields containing commas and escaped ("") quotes. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { fields.add(current.toString()); current.setLength(0) }
                    else -> current.append(c)
                }
            }
            i++
        }
        fields.add(current.toString())
        return fields.map { it.trim() }
    }
}
