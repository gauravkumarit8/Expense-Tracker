package com.autoexpensetracker.importer

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.backup.CsvExporter
import com.autoexpensetracker.data.*
import com.autoexpensetracker.importer.OwnCsv
import java.util.Calendar
import java.util.TimeZone
private fun ms(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int, milli: Int = 0): Long {
    val c = Calendar.getInstance(); c.clear(); c.set(y, m, d, h, mi, s); c.set(Calendar.MILLISECOND, milli); return c.timeInMillis }

class OwnCsvTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
    val t1 = ms(2026, Calendar.OCTOBER, 12, 14, 5, 9, 789)
    val txs = listOf(
        Transaction(rawTextHash = "h", amount = 340.0, direction = Direction.SENT, merchantOrContact = "Swiggy", bankOrSource = "HDFCBK", timestampMillis = t1,
            category = "FOOD", note = "lunch, with \"team\"\nsecond line", tags = "work,team", balanceAfter = 12345.67, needsReview = false),
        Transaction(rawTextHash = "h", amount = 1500.5, direction = Direction.RECEIVED, merchantOrContact = "=SUM(1+1)", bankOrSource = "JD-SLCBNK-T", timestampMillis = t1 + 60000,
            category = null, note = "-5 refund", tags = null, balanceAfter = null, needsReview = true),
        Transaction(rawTextHash = "h", amount = 10.0, direction = Direction.UNKNOWN, merchantOrContact = null, bankOrSource = "Cash", timestampMillis = t1 + 120000),
        Transaction(rawTextHash = "h", amount = 99.99, direction = Direction.SENT, merchantOrContact = "'Tis the shop", bankOrSource = "+919999", timestampMillis = t1 + 180000, category = "BILLS"))
    val csv = CsvExporter.toCsv(txs)

    // --- detection
    check("detect own", OwnCsv.looksLikeOwnExport(csv))
    check("detect statement no", !OwnCsv.looksLikeOwnExport("Date,Narration,Withdrawal Amt,Deposit Amt,Closing Balance\n01/01/2026,UPI,100,,500"))
    check("detect empty no", !OwnCsv.looksLikeOwnExport(""))
    check("detect bom", OwnCsv.looksLikeOwnExport("\uFEFF" + csv))

    // --- full round trip
    val res = OwnCsv.parse(csv)
    check("skipped 0", res.skippedRowCount == 0)
    check("4 rows", res.rows.size == 4)
    val byAmt = res.rows.associateBy { Math.round(it.amount * 100) }
    val a = byAmt[34000L]!!
    check("rt ts exact ms", a.timestampMillis == t1)
    check("rt dir", a.direction == Direction.SENT)
    check("rt merchant", a.merchant == "Swiggy")
    check("rt bank", a.bankOrSource == "HDFCBK")
    check("rt category", a.category == "FOOD")
    check("rt note multiline+quotes", a.note == "lunch, with \"team\"\nsecond line")
    check("rt tags", a.tags == "work,team")
    check("rt balance", a.balanceAfter == 12345.67)
    check("rt review false", !a.needsReview)
    val b = byAmt[150050L]!!
    check("rt formula merchant unguarded", b.merchant == "=SUM(1+1)")
    check("rt minus note unguarded", b.note == "-5 refund")
    check("rt null category", b.category == null)
    check("rt null balance", b.balanceAfter == null)
    check("rt review true", b.needsReview)
    check("rt received", b.direction == Direction.RECEIVED)
    val c = byAmt[1000L]!!
    check("rt unknown dir", c.direction == Direction.UNKNOWN)
    check("rt null merchant", c.merchant == null)
    check("rt cash", c.bankOrSource == "Cash")
    val d = byAmt[9999L]!!
    check("rt apostrophe kept", d.merchant == "'Tis the shop")
    check("rt plus bank unguarded", d.bankOrSource == "+919999")

    // --- old export without the Timestamp column
    val oldCsv = csv.split("\n").joinToString("\n") { line -> line }  // header manip below
    val lines = CsvExporterOld(txs)
    val old = OwnCsv.parse(lines)
    check("old format rows", old.rows.size == 4)
    val oa = old.rows.first { Math.round(it.amount * 100) == 34000L }
    check("old format ts to the second", oa.timestampMillis == (t1 / 1000L) * 1000L)

    // --- timezone: exact ts survives, local date+time does not
    val tsCsv = "Date,Time,Direction,Amount,Bank/Source,Timestamp (ms)\n2026-10-12,14:05:09,SENT,100.00,HDFCBK,${t1 / 1000 * 1000}\n"
    val noTs = "Date,Time,Direction,Amount,Bank/Source\n2026-10-12,14:05:09,SENT,100.00,HDFCBK\n"
    val saved = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    val withTs = OwnCsv.parse(tsCsv).rows[0].timestampMillis
    val withoutTs = OwnCsv.parse(noTs).rows[0].timestampMillis
    TimeZone.setDefault(saved)
    check("tz exact with ts column", withTs == t1 / 1000 * 1000)
    check("tz fallback differs (documented)", withoutTs != t1 / 1000 * 1000 || saved.id == "America/New_York")

    // --- spreadsheet-mangled timestamp falls back to date+time
    val mangled = "Date,Time,Direction,Amount,Bank/Source,Timestamp (ms)\n2026-10-12,14:05:09,SENT,100.00,HDFCBK,1.76044E+12\n"
    check("mangled ts fallback", OwnCsv.parse(mangled).rows[0].timestampMillis == ms(2026, Calendar.OCTOBER, 12, 14, 5, 9))

    // --- number and digit tolerance
    fun amt(s: String): Double? { val r = OwnCsv.parse("Date,Direction,Amount,Bank/Source\n2026-10-12,SENT,\"$s\",X\n"); return r.rows.firstOrNull()?.amount }
    check("decimal comma", amt("12,50") == 12.5)
    check("thousands comma", amt("1,234") == 1234.0)
    check("rupee and commas", amt("₹1,500.50") == 1500.5)
    check("rs prefix", amt("Rs. 99") == 99.0)
    check("zero rejected", amt("0.00") == null)
    check("negative rejected", amt("-5") == null)
    check("garbage rejected", amt("abc") == null)
    val arabic = "Date,Time,Direction,Amount,Bank/Source\n\u0662\u0660\u0662\u0666-\u0661\u0660-\u0661\u0662,\u0661\u0664:\u0660\u0665:\u0660\u0669,SENT,\u0661\u0660\u0660.\u0660\u0660,X\n"
    val ar = OwnCsv.parse(arabic)
    check("arabic digits parse", ar.rows.size == 1 && ar.rows[0].amount == 100.0 && ar.rows[0].timestampMillis == ms(2026, Calendar.OCTOBER, 12, 14, 5, 9))

    // --- other date formats, date-only, am/pm
    fun tsOf(date: String, time: String): Long? = OwnCsv.parse("Date,Time,Direction,Amount,Bank/Source\n$date,$time,SENT,5,X\n").rows.firstOrNull()?.timestampMillis
    check("dd/MM/yyyy", tsOf("12/10/2026", "14:05:09") == ms(2026, Calendar.OCTOBER, 12, 14, 5, 9))
    check("dd-MM-yyyy", tsOf("12-10-2026", "14:05") == ms(2026, Calendar.OCTOBER, 12, 14, 5, 0))
    check("am/pm", tsOf("2026-10-12", "2:05 PM") == ms(2026, Calendar.OCTOBER, 12, 14, 5, 0))
    check("date only noon", tsOf("2026-10-12", "") == ms(2026, Calendar.OCTOBER, 12, 12, 0, 0))
    check("bad date skipped", tsOf("2026-13-45", "10:00") == null)
    check("trailing junk date skipped", tsOf("2026-10-12 xyz", "10:00") == null)

    // --- skipping
    val mixed = "Date,Direction,Amount,Bank/Source\n2026-10-12,SENT,5,X\n2026-10-12,WHAT,5,X\n2026-10-12,SENT,0,X\nnot-a-date,SENT,5,X\n2026-10-13,RECEIVED,7,Y\n"
    val mr = OwnCsv.parse(mixed)
    check("mixed good", mr.rows.size == 2)
    check("mixed skipped", mr.skippedRowCount == 3)

    // --- categories
    fun cat(s: String): String? = OwnCsv.parse("Date,Direction,Amount,Bank/Source,Category\n2026-10-12,SENT,5,X,\"$s\"\n").rows[0].category
    check("cat by label", cat("Food & Dining") == "FOOD")
    check("cat lowercase name", cat("groceries") == "GROCERIES")
    check("cat unknown", cat("Weird") == null)

    // --- CRLF + BOM
    val crlf = "\uFEFFDate,Direction,Amount,Bank/Source\r\n2026-10-12,SENT,5,X\r\n\r\n2026-10-13,SENT,6,X\r\n"
    check("crlf bom", OwnCsv.parse(crlf).rows.size == 2)
    check("no trailing newline", OwnCsv.parse("Date,Direction,Amount,Bank/Source\n2026-10-12,SENT,5,X").rows.size == 1)

    // --- errors
    var threw = false
    try { OwnCsv.parse("Foo,Bar\n1,2") } catch (e: OwnCsv.ParseException) { threw = true }
    check("wrong file throws", threw)
    threw = false
    try { OwnCsv.parse("") } catch (e: OwnCsv.ParseException) { threw = true }
    check("empty throws", threw)

    // --- dedupe key + plan
    check("key second-level", OwnCsv.dedupeKey("HDFCBK", 1000789L, 5.0, Direction.SENT) == OwnCsv.dedupeKey("HDFCBK", 1000000L, 5.0, Direction.SENT))
    check("key differs by second", OwnCsv.dedupeKey("HDFCBK", 1000000L, 5.0, Direction.SENT) != OwnCsv.dedupeKey("HDFCBK", 1001000L, 5.0, Direction.SENT))
    check("key differs by dir", OwnCsv.dedupeKey("X", 1000L, 5.0, Direction.SENT) != OwnCsv.dedupeKey("X", 1000L, 5.0, Direction.RECEIVED))
    check("key paise", OwnCsv.dedupeKey("X", 1000L, 5.004, Direction.SENT) == OwnCsv.dedupeKey("X", 1000L, 5.0, Direction.SENT))
    fun p(keys: List<String>, ex: Map<String, Int>) = OwnCsv.plan(keys, ex).toList()
    check("plan empty db", p(listOf("a", "a", "b"), emptyMap()) == listOf(0, 1, 0))
    check("plan one existing", p(listOf("a", "a", "b"), mapOf("a" to 1)) == listOf(-1, 1, 0))
    check("plan two existing", p(listOf("a", "a", "b"), mapOf("a" to 2)) == listOf(-1, -1, 0))
    check("plan reimport all", p(listOf("a", "a", "b"), mapOf("a" to 2, "b" to 1)) == listOf(-1, -1, -1))
    check("plan interleaved", p(listOf("a", "b", "a"), mapOf("a" to 1)) == listOf(-1, 0, 1))

    checks.assertAll()
    }
}

// What the exporter wrote before the Timestamp column existed (same layout minus the last column).
private fun CsvExporterOld(txs: List<Transaction>): String {
    val full = CsvExporter.toCsv(txs)
    val recs = OwnCsv.parseRecords(full)
    fun esc(f: String) = if (f.contains(",") || f.contains("\"") || f.contains("\n")) "\"" + f.replace("\"", "\"\"") + "\"" else f
    return recs.joinToString("\n") { r -> r.dropLast(1).joinToString(",") { esc(it) } }
}
