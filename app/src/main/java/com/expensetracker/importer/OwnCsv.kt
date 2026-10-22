package com.autoexpensetracker.importer

import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reads back the CSV that this app's own "Export to CSV" writes (see
 * `CsvExporter`), so history can be moved to a new phone: export on the old
 * one, import on the new one.
 *
 * Deliberately separate from [StatementCsvParser]: a bank statement has
 * debit/credit columns and a narration, while this file already carries
 * direction, merchant, category, note, tags, balance and needs-review per
 * row, and must come back with all of them.
 *
 * Pure Kotlin (no Android, no database) so it can be compiled and tested
 * on its own.
 */
object OwnCsv {

    class ParseException(message: String) : Exception(message)

    data class Row(
        val timestampMillis: Long,
        val direction: Direction,
        val amount: Double,
        val merchant: String?,
        val bankOrSource: String,
        val category: String?,
        val note: String?,
        val tags: String?,
        val balanceAfter: Double?,
        val needsReview: Boolean
    )

    data class ParseResult(val rows: List<Row>, val skippedRowCount: Int)

    /** True if the first record looks like the header this app's CSV export writes. */
    fun looksLikeOwnExport(csvText: String): Boolean {
        val header = firstRecordNormalized(csvText) ?: return false
        return header.contains("date") && header.contains("direction") &&
            header.contains("amount") && header.contains("banksource")
    }

    fun parse(csvText: String): ParseResult {
        val records = parseRecords(csvText)
        if (records.isEmpty()) throw ParseException("The file is empty")
        val header = records[0].map { normalizeHeader(it) }
        fun col(name: String): Int = header.indexOf(name)

        val dateIdx = col("date")
        val timeIdx = col("time")
        val tsIdx = col("timestampms")
        val tsSecIdx = col("timestamps")
        val dirIdx = col("direction")
        val amountIdx = col("amount")
        val merchantIdx = col("merchant")
        val bankIdx = col("banksource")
        val categoryIdx = col("category")
        val noteIdx = col("note")
        val tagsIdx = col("tags")
        val balanceIdx = col("balanceafter")
        val reviewIdx = col("needsreview")

        if (dirIdx < 0 || amountIdx < 0 || (dateIdx < 0 && tsIdx < 0 && tsSecIdx < 0)) {
            throw ParseException("This doesn't look like a file exported from Expense Tracker")
        }

        val dateFormats = DATE_PATTERNS.map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = false } }
        val timeFormats = TIME_PATTERNS.map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = false } }

        val rows = ArrayList<Row>()
        var skipped = 0
        for (r in 1 until records.size) {
            val f = records[r]
            fun get(i: Int): String = if (i >= 0 && i < f.size) f[i].trim() else ""

            val direction = parseDirection(get(dirIdx))
            val amount = parseAmount(get(amountIdx))
            val ts = parseTimestamp(get(tsIdx), get(tsSecIdx), get(dateIdx), get(timeIdx), dateFormats, timeFormats)
            if (direction == null || amount == null || ts == null) { skipped++; continue }

            val bank = unguard(get(bankIdx)).trim()
            rows.add(
                Row(
                    timestampMillis = ts,
                    direction = direction,
                    amount = amount,
                    merchant = blankToNull(unguard(get(merchantIdx))),
                    bankOrSource = if (bank.isEmpty()) "Imported" else bank,
                    category = normalizeCategory(unguard(get(categoryIdx))),
                    note = blankToNull(unguard(get(noteIdx))),
                    tags = blankToNull(unguard(get(tagsIdx))),
                    balanceAfter = parseBalance(get(balanceIdx)),
                    needsReview = parseYesNo(get(reviewIdx))
                )
            )
        }
        return ParseResult(rows, skipped)
    }

    /**
     * Identity of a row for duplicate detection: account, the second it
     * happened in, amount in paise and direction. Exports carry whole
     * seconds, so comparing at second level matches rows already in the
     * database whose timestamps still have milliseconds.
     */
    fun dedupeKey(accountKey: String, timestampMillis: Long, amount: Double, direction: Direction): String =
        accountKey + "|" + (timestampMillis / 1000L) + "|" + Math.round(amount * 100.0) + "|" + direction.name

    /**
     * For each row key in file order: -1 if the row is already present, or
     * its 0-based occurrence number among identical keys if it should be
     * inserted. [existing] says how many rows with each key the app already
     * has. Two genuinely separate payments of the same amount in the same
     * second still both import, while re-importing a file adds nothing.
     */
    fun plan(keys: List<String>, existing: Map<String, Int>): IntArray {
        val seen = HashMap<String, Int>()
        val result = IntArray(keys.size)
        for (i in keys.indices) {
            val key = keys[i]
            val nth = seen[key] ?: 0
            seen[key] = nth + 1
            val have = existing[key] ?: 0
            result[i] = if (nth < have) -1 else nth
        }
        return result
    }

    // ---------- parsing helpers ----------

    private val DATE_PATTERNS = listOf("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "yyyy/MM/dd", "dd-MMM-yyyy", "dd MMM yyyy")
    private val TIME_PATTERNS = listOf("HH:mm:ss", "HH:mm", "h:mm:ss a", "h:mm a")

    private const val MIN_TS = 946684800000L   // 2000-01-01
    private const val MAX_TS = 4102444800000L  // 2100-01-01

    private fun firstRecordNormalized(csvText: String): List<String>? {
        val records = parseRecords(csvText)
        if (records.isEmpty()) return null
        return records[0].map { normalizeHeader(it) }
    }

    private fun normalizeHeader(h: String): String {
        val sb = StringBuilder()
        for (ch in h) if (ch.isLetterOrDigit()) sb.append(Character.toLowerCase(ch))
        return sb.toString()
    }

    /**
     * RFC 4180 reader: quoted fields may contain commas, doubled quotes and
     * line breaks (the exporter quotes any field containing one), so records
     * are read across lines rather than line by line. Skips a leading BOM
     * and blank records.
     */
    /** Test-visible name for [parseRecords]. */
    internal fun parseRecordsForTest(text: String): List<List<String>> = parseRecords(text)

    internal fun parseRecords(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        val n = text.length
        var i = if (n > 0 && text[0] == '\uFEFF') 1 else 0
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { cur.append('"'); i++ } else inQuotes = false
                } else {
                    cur.append(c)
                }
            } else if (c == '"') {
                inQuotes = true
            } else if (c == ',') {
                fields.add(cur.toString()); cur.setLength(0)
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                fields.add(cur.toString()); cur.setLength(0)
                if (!(fields.size == 1 && fields[0].isBlank())) records.add(fields)
                fields = ArrayList()
            } else {
                cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(cur.toString())
            if (!(fields.size == 1 && fields[0].isBlank())) records.add(fields)
        }
        return records
    }

    /** The exporter prefixes a ' to text starting with = + - @ so spreadsheets don't run it as a formula; undo that. */
    private fun unguard(s: String): String =
        if (s.length >= 2 && s[0] == '\'' && "=+-@\t\r".indexOf(s[1]) >= 0) s.substring(1) else s

    private fun blankToNull(s: String): String? { val t = s.trim(); return if (t.isEmpty()) null else t }

    private fun parseDirection(raw: String): Direction? {
        for (d in Direction.values()) if (d.name.equals(raw.trim(), ignoreCase = true)) return d
        return null
    }

    private fun normalizeCategory(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        for (c in Category.values()) {
            if (c.name.equals(t, ignoreCase = true) || c.label.equals(t, ignoreCase = true)) return c.name
        }
        return null
    }

    private fun parseYesNo(raw: String): Boolean {
        val t = raw.trim()
        return t.equals("yes", ignoreCase = true) || t.equals("true", ignoreCase = true) || t == "1"
    }

    /** Non-ASCII digits (a device set to an Arabic or Devanagari locale writes them) to 0-9. */
    private fun asciiDigits(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.isDigit() && ch !in '0'..'9') {
                val d = Character.digit(ch, 10)
                sb.append(if (d >= 0) ('0' + d) else ch)
            } else sb.append(ch)
        }
        return sb.toString()
    }

    /** Positive number; tolerates a rupee sign, "Rs", spaces and a decimal comma ("12,50"). */
    private fun parseAmount(raw: String): Double? {
        val v = parseNumber(raw) ?: return null
        return if (v > 0.0 && !v.isInfinite() && !v.isNaN()) v else null
    }

    private fun parseBalance(raw: String): Double? {
        val v = parseNumber(raw) ?: return null
        return if (v.isInfinite() || v.isNaN()) null else v
    }

    private fun parseNumber(raw: String): Double? {
        var s = asciiDigits(raw.trim()).replace("₹", "").replace("Rs.", "").replace("Rs", "").replace(" ", "")
        if (s.isEmpty()) return null
        val commas = s.count { it == ',' }
        if (commas == 1 && !s.contains('.')) {
            val after = s.length - s.indexOf(',') - 1
            s = if (after in 1..2) s.replace(',', '.') else s.replace(",", "")
        } else {
            s = s.replace(",", "")
        }
        return s.toDoubleOrNull()
    }

    private fun parseTimestamp(
        tsRaw: String,
        tsSecRaw: String,
        dateRaw: String,
        timeRaw: String,
        dateFormats: List<SimpleDateFormat>,
        timeFormats: List<SimpleDateFormat>
    ): Long? {
        // The exact epoch column wins when it is a plain whole number (older
        // files: milliseconds; current files: seconds). A spreadsheet that has
        // re-saved the file may have turned it into 1.76E+12, which has lost
        // digits - that falls through to Date + Time.
        val ts = asciiDigits(tsRaw.trim())
        if (ts.length in 12..13 && ts.all { it in '0'..'9' }) {
            val v = ts.toLongOrNull()
            if (v != null && v in MIN_TS..MAX_TS) return v
        }
        // Current exports write whole epoch seconds (10 digits) instead.
        val sec = asciiDigits(tsSecRaw.trim())
        if (sec.length == 10 && sec.all { it in '0'..'9' }) {
            val v = sec.toLongOrNull()
            if (v != null && v * 1000L in MIN_TS..MAX_TS) return v * 1000L
        }
        val dateText = asciiDigits(dateRaw.trim())
        if (dateText.isEmpty()) return null
        var date: java.util.Date? = null
        for (f in dateFormats) {
            val pos = ParsePosition(0)
            val d = f.parse(dateText, pos)
            if (d != null && pos.index == dateText.length) { date = d; break }
        }
        if (date == null) return null

        val cal = Calendar.getInstance()
        cal.timeInMillis = date.time
        val timeText = asciiDigits(timeRaw.trim())
        var timeSet = false
        if (timeText.isNotEmpty()) {
            for (f in timeFormats) {
                val pos = ParsePosition(0)
                val t = f.parse(timeText, pos)
                if (t != null && pos.index == timeText.length) {
                    val tc = Calendar.getInstance()
                    tc.timeInMillis = t.time
                    cal.set(Calendar.HOUR_OF_DAY, tc.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, tc.get(Calendar.MINUTE))
                    cal.set(Calendar.SECOND, tc.get(Calendar.SECOND))
                    timeSet = true
                    break
                }
            }
        }
        // A date with no usable time becomes midday, so a timezone shift can't move it to another day.
        if (!timeSet) {
            cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        }
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
