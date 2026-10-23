package com.autoexpensetracker.backup

import com.autoexpensetracker.Checks
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import com.autoexpensetracker.importer.OwnCsv
import com.autoexpensetracker.pdf.PdfPeriod
import org.junit.Test
import java.util.Calendar
import java.util.Locale

private fun ms(y: Int, m: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0): Long {
    val c = Calendar.getInstance(); c.clear(); c.set(y, m, d, h, mi, s); return c.timeInMillis
}

private fun tx(id: Long, amount: Double, dir: Direction, merchant: String?, at: Long, category: String? = null, note: String? = null) =
    Transaction(rawTextHash = "h", id = id, amount = amount, direction = dir, merchantOrContact = merchant,
        bankOrSource = "HDFCBK", timestampMillis = at, category = category, note = note)

class CsvExporterTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
        val t = ms(2026, Calendar.OCTOBER, 12, 14, 5, 9)
        val sent = tx(1, 340.0, Direction.SENT, "Swiggy", t, category = "FOOD")
        val received = tx(2, 1500.5, Direction.RECEIVED, "Salary", t + 60_000, category = "TRANSFER")
        val unknown = tx(3, 10.0, Direction.UNKNOWN, "Cash", t + 120_000)
        val csv = CsvExporter.toCsv(listOf(sent, received, unknown))
        val records = OwnCsv.parseRecordsForTest(csv)
        val header = records[0]

        // ---- layout
        check("header order", header == listOf("Date", "Time", "Direction", "Amount", "Signed Amount", "Merchant",
            "Bank/Source", "Category", "Note", "Tags", "Balance After", "Needs Review", "Timestamp (s)"))
        check("one row per transaction", records.size == 4)
        check("every row has every column", records.all { it.size == header.size })

        fun col(row: List<String>, name: String) = row[header.indexOf(name)]
        val byMerchant = records.drop(1).associateBy { col(it, "Merchant") }

        // ---- signed amount
        check("sent is negative", col(byMerchant["Swiggy"]!!, "Signed Amount") == "-340.00")
        check("received is positive", col(byMerchant["Salary"]!!, "Signed Amount") == "1500.50")
        check("unknown is blank", col(byMerchant["Cash"]!!, "Signed Amount") == "")
        check("amount stays positive", col(byMerchant["Swiggy"]!!, "Amount") == "340.00")
        val net = byMerchant.values.map { r -> col(r, "Signed Amount").let { if (it.isEmpty()) 0.0 else it.toDouble() } }.sum()
        check("signed amounts sum to the net", Math.abs(net - (1500.5 - 340.0)) < 0.001)

        // ---- category label, not the internal name
        check("category shown by label", col(byMerchant["Swiggy"]!!, "Category") == "Food & Dining")
        check("no category is blank", col(byMerchant["Cash"]!!, "Category") == "")

        // ---- timestamp: ten digits, so Excel shows it in full
        val stamp = col(byMerchant["Swiggy"]!!, "Timestamp (s)")
        check("timestamp is 10 digits", stamp.length == 10 && stamp.all { it in '0'..'9' })
        check("timestamp is epoch seconds", stamp.toLong() == t / 1000L)

        // ---- encoding and line endings
        check("toCsv has no BOM", !csv.startsWith(CsvExporter.BOM))
        val bytes = CsvExporter.toBytes(listOf(sent))
        check("bytes start with UTF-8 BOM", bytes.size > 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        check("bytes decode to BOM + csv", String(bytes, Charsets.UTF_8) == CsvExporter.BOM + CsvExporter.toCsv(listOf(sent)))
        check("rows end in CRLF", csv.endsWith("\r\n") && !csv.replace("\r\n", "").contains('\n') && !csv.replace("\r\n", "").contains('\r'))

        // ---- a bare CR in a note must not split the row
        val withCr = tx(4, 5.0, Direction.SENT, "Shop", t, note = "line one\rline two")
        val crRecords = OwnCsv.parseRecordsForTest(CsvExporter.toCsv(listOf(withCr)))
        check("CR inside a note keeps one row", crRecords.size == 2 && crRecords[1].size == header.size)
        check("CR inside a note survives the round trip", OwnCsv.parse(CsvExporter.toCsv(listOf(withCr))).rows[0].note == "line one\rline two")

        // ---- non-Latin text survives bytes -> text -> import
        val hindi = tx(5, 80.0, Direction.SENT, "कैफ़े चाय", t, category = "FOOD", note = "café – déjà vu")
        val decoded = String(CsvExporter.toBytes(listOf(hindi)), Charsets.UTF_8)
        val back = OwnCsv.parse(decoded).rows[0]
        check("hindi merchant round trip", back.merchant == "कैफ़े चाय")
        check("accents round trip", back.note == "café – déjà vu")
        check("BOM file is recognised as an export", OwnCsv.looksLikeOwnExport(decoded))

        // ---- full round trip keeps the fields that matter
        val rt = OwnCsv.parse(csv).rows.associateBy { it.merchant }
        check("rt amount", rt["Swiggy"]!!.amount == 340.0)
        check("rt direction", rt["Salary"]!!.direction == Direction.RECEIVED && rt["Cash"]!!.direction == Direction.UNKNOWN)
        check("rt category name restored from label", rt["Swiggy"]!!.category == "FOOD" && rt["Salary"]!!.category == "TRANSFER")
        check("rt null category", rt["Cash"]!!.category == null)
        check("rt time to the second", rt["Swiggy"]!!.timestampMillis == (t / 1000L) * 1000L)

        // ---- formula guard still applies to text, never to numbers
        val formula = tx(6, 7.0, Direction.SENT, "=HYPERLINK(\"x\")", t)
        val fRecords = OwnCsv.parseRecordsForTest(CsvExporter.toCsv(listOf(formula)))
        check("formula text neutralised", col(fRecords[1], "Merchant").startsWith("'="))
        check("signed amount not neutralised", col(fRecords[1], "Signed Amount") == "-7.00")
        check("formula round trip", OwnCsv.parse(CsvExporter.toCsv(listOf(formula))).rows[0].merchant == "=HYPERLINK(\"x\")")

        // ---- empty list still gives a valid header-only file
        val empty = OwnCsv.parseRecordsForTest(CsvExporter.toCsv(emptyList()))
        check("empty export is header only", empty.size == 1 && empty[0] == header)

        // ---- file names
        val now = ms(2026, Calendar.OCTOBER, 21)
        check("all-history name", CsvExporter.fileName(null, now) == "expense_tracker_2026-10-21.csv")
        check("day name", CsvExporter.fileName(PdfPeriod.Day(2026, Calendar.MARCH, 5), now) == "expense_tracker_2026-03-05.csv")
        check("month name", CsvExporter.fileName(PdfPeriod.Month(2026, Calendar.OCTOBER), now) == "expense_tracker_2026-10.csv")
        check("year name", CsvExporter.fileName(PdfPeriod.Year(2025), now) == "expense_tracker_2025.csv")
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("th", "TH", "TH"))   // Buddhist calendar, Thai digits on some systems
            check("name is Gregorian and ASCII under a Thai locale", CsvExporter.fileName(null, now) == "expense_tracker_2026-10-21.csv")
            Locale.setDefault(Locale("ar", "EG"))
            check("name is ASCII under an Arabic locale", CsvExporter.fileName(null, now) == "expense_tracker_2026-10-21.csv")
            check("csv body is ASCII digits under an Arabic locale", OwnCsv.parseRecordsForTest(CsvExporter.toCsv(listOf(sent)))[1][3] == "340.00")
        } finally {
            Locale.setDefault(saved)
        }

        checks.assertAll()
    }
}
