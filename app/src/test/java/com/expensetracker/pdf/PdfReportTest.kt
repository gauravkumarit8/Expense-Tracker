package com.autoexpensetracker.pdf

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.data.*
import com.autoexpensetracker.pdf.*
import java.util.Calendar

private fun ms(y: Int, m: Int, d: Int, h: Int = 12, mi: Int = 0): Long { val c = Calendar.getInstance(); c.clear(); c.set(y, m, d, h, mi, 0); return c.timeInMillis }
private object Mono : TextMeasurer { override fun width(text: String, size: Float, bold: Boolean): Float = text.length * size * 0.5f * (if (bold) 1.1f else 1f) }
private fun texts(p: LayoutPage) = p.ops.filterIsInstance<DrawOp.Text>()
private fun body(p: LayoutPage) = texts(p).filter { it.y != PdfLayout.FOOTER_Y }
private const val OCT = Calendar.OCTOBER


class PdfReportTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
    // ---------- money
    check("m340", PdfMoney.format(340.0, "Rs. ") == "Rs. 340.00")
    check("m1500.5", PdfMoney.format(1500.5, "Rs. ") == "Rs. 1,500.50")
    check("m150000", PdfMoney.format(150000.0, "Rs. ") == "Rs. 1,50,000.00")
    check("m1234567.891", PdfMoney.format(1234567.891, "Rs. ") == "Rs. 12,34,567.89")
    check("m999", PdfMoney.format(999.0, "Rs. ") == "Rs. 999.00")
    check("m1000", PdfMoney.format(1000.0, "Rs. ") == "Rs. 1,000.00")
    check("m99999.99", PdfMoney.format(99999.99, "Rs. ") == "Rs. 99,999.99")
    check("m100000", PdfMoney.format(100000.0, "Rs. ") == "Rs. 1,00,000.00")
    check("m1crore", PdfMoney.format(10000000.0, "Rs. ") == "Rs. 1,00,00,000.00")
    check("m-12.5", PdfMoney.format(-12.5, "Rs. ") == "-Rs. 12.50")
    check("m-0.001 no sign", PdfMoney.format(-0.001, "Rs. ") == "Rs. 0.00")
    check("m0", PdfMoney.format(0.0, "Rs. ") == "Rs. 0.00")
    check("m rupee sign", PdfMoney.format(5.0, PdfMoney.RUPEE_SIGN) == "\u20B95.00")
    check("m 5 paise", PdfMoney.format(0.05, "Rs. ") == "Rs. 0.05")

    // ---------- access policy
    val now = ms(2026, OCT, 20)
    check("free months const", PdfAccessPolicy.FREE_MONTHS == 3)
    check("oct free", PdfAccessPolicy.isFree(PdfPeriod.Month(2026, OCT), now))
    check("sep free", PdfAccessPolicy.isFree(PdfPeriod.Month(2026, Calendar.SEPTEMBER), now))
    check("aug free", PdfAccessPolicy.isFree(PdfPeriod.Month(2026, Calendar.AUGUST), now))
    check("jul locked", !PdfAccessPolicy.isFree(PdfPeriod.Month(2026, Calendar.JULY), now))
    check("year locked current", !PdfAccessPolicy.isFree(PdfPeriod.Year(2026), now))
    check("year locked old", !PdfAccessPolicy.isFree(PdfPeriod.Year(2024), now))
    check("day aug free", PdfAccessPolicy.isFree(PdfPeriod.Day(2026, Calendar.AUGUST, 31), now))
    check("day jul locked", !PdfAccessPolicy.isFree(PdfPeriod.Day(2026, Calendar.JULY, 31), now))
    check("future free", PdfAccessPolicy.isFree(PdfPeriod.Month(2026, Calendar.DECEMBER), now))
    val jan = ms(2027, Calendar.JANUARY, 5)
    check("jan27 free", PdfAccessPolicy.isFree(PdfPeriod.Month(2027, Calendar.JANUARY), jan))
    check("dec26 free", PdfAccessPolicy.isFree(PdfPeriod.Month(2026, Calendar.DECEMBER), jan))
    check("nov26 free", PdfAccessPolicy.isFree(PdfPeriod.Month(2026, Calendar.NOVEMBER), jan))
    check("oct26 locked at jan27", !PdfAccessPolicy.isFree(PdfPeriod.Month(2026, OCT), jan))

    // ---------- report builder
    val t1 = Transaction(rawTextHash = "h", id = 1, amount = 340.0, direction = Direction.SENT, merchantOrContact = "Swiggy", bankOrSource = "HDFCBK", timestampMillis = ms(2026, OCT, 12, 14, 5), category = "FOOD")
    val t2 = Transaction(rawTextHash = "h", id = 2, amount = 1000.0, direction = Direction.SENT, merchantOrContact = "Rent", bankOrSource = "HDFCBK", timestampMillis = ms(2026, OCT, 5, 9, 0), category = "BILLS", needsReview = true)
    val t3 = Transaction(rawTextHash = "h", id = 3, amount = 5000.0, direction = Direction.RECEIVED, merchantOrContact = "Salary", bankOrSource = "HDFCBK", timestampMillis = ms(2026, OCT, 1, 10, 0), category = "TRANSFER")
    val t4 = Transaction(rawTextHash = "h", id = 4, amount = 10.0, direction = Direction.UNKNOWN, merchantOrContact = "Caf\u00e9 \u2615 Rock \u2014 50% \u2212 x\u2026", bankOrSource = "Cash", timestampMillis = ms(2026, OCT, 3, 8, 30))
    val t5 = Transaction(rawTextHash = "h", id = 5, amount = 60.5, direction = Direction.SENT, merchantOrContact = null, bankOrSource = "ICICIB", timestampMillis = ms(2026, OCT, 20, 7, 0), category = null)
    val sep = Transaction(rawTextHash = "h", id = 6, amount = 777.0, direction = Direction.SENT, merchantOrContact = "Old", bankOrSource = "HDFCBK", timestampMillis = ms(2026, Calendar.SEPTEMBER, 30, 23, 59), category = "FOOD")
    val nov = Transaction(rawTextHash = "h", id = 7, amount = 888.0, direction = Direction.SENT, merchantOrContact = "Next", bankOrSource = "HDFCBK", timestampMillis = ms(2026, Calendar.NOVEMBER, 1, 0, 0), category = "FOOD")
    val all = listOf(t1, t2, t3, t4, t5, sep, nov)
    val month = PdfPeriod.Month(2026, OCT)
    val blocks = PdfReportBuilder.build(all, month, "Rs. ", ms(2026, OCT, 20, 18, 0))

    check("count in month", PdfReportBuilder.countIn(all, month) == 5)
    check("boundary sep excluded", PdfReportBuilder.inPeriod(all, month).none { it.id == 6L || it.id == 7L })
    check("count empty", PdfReportBuilder.countIn(all, PdfPeriod.Month(2026, Calendar.JANUARY)) == 0)
    val title = blocks[0] as Block.Title
    check("title", title.text == "Expense Report - October 2026")
    check("subtitle", title.subtitle == "Generated on 20 October 2026")
    val kv = blocks.filterIsInstance<Block.KeyValues>()[0].rows.toMap()
    check("spent excludes unknown, includes review", kv["Spent"] == "Rs. 1,400.50")
    check("received", kv["Received"] == "Rs. 5,000.00")
    check("net", kv["Net (received - spent)"] == "Rs. 3,599.50")
    check("txn count includes unknown", kv["Transactions"] == "5")
    val notes = blocks.filterIsInstance<Block.Note>().map { it.text }
    check("unknown note", notes.any { it.startsWith("1 transaction with an unknown direction is listed") })
    check("review note", notes.any { it.startsWith("* marks 1 transaction still waiting") })
    val tables = blocks.filterIsInstance<Block.Table>()
    check("2 tables for month", tables.size == 2)
    val cat = tables[0]
    check("cat order desc", cat.rows.map { it[0] } == listOf("Bills & Utilities", "Food & Dining", "Other", "Total spent"))
    check("cat amounts", cat.rows.map { it[2] } == listOf("Rs. 1,000.00", "Rs. 340.00", "Rs. 60.50", "Rs. 1,400.50"))
    check("cat shares", cat.rows.map { it[1] } == listOf("71%", "24%", "4%", ""))
    check("cat total bold", cat.boldLastRow)
    val tx = tables[1]
    check("oldest first", tx.rows.map { it[1] } == listOf("Salary", "Caf\u00e9 Rock - 50% - x...", "* Rent", "Swiggy", "-"))
    check("date col", tx.rows[3][0] == "12 Oct 14:05")
    check("amount sent", tx.rows[3][4] == "-Rs. 340.00")
    check("amount received", tx.rows[0][4] == "+Rs. 5,000.00")
    check("amount unknown", tx.rows[1][4] == "Rs. 10.00 (?)")
    check("category label", tx.rows[3][3] == "Food & Dining")
    check("null category dash", tx.rows[4][3] == "-")
    check("header Date", tx.columns[0].header == "Date")
    // text hygiene across every block
    val allText = StringBuilder()
    for (b in blocks) when (b) {
        is Block.Title -> allText.append(b.text).append(b.subtitle)
        is Block.Heading -> allText.append(b.text)
        is Block.Note -> allText.append(b.text)
        is Block.KeyValues -> b.rows.forEach { allText.append(it.first).append(it.second) }
        is Block.Table -> { b.rows.forEach { r -> r.forEach { allText.append(it) } } }
    }
    check("no emoji/dash/ellipsis/minus chars", allText.none { it in '\u2000'..'\u2BFF' || Character.isSurrogate(it) })
    check("keeps accented letters", allText.contains("\u00e9"))
    check("headings present", blocks.filterIsInstance<Block.Heading>().map { it.text } == listOf("Summary", "Spending by category", "Transactions (oldest first)"))
    // tie-break on identical timestamps
    val tie = listOf(
        Transaction(rawTextHash = "h", id = 9, amount = 1.0, direction = Direction.SENT, merchantOrContact = "B", bankOrSource = "X", timestampMillis = ms(2026, OCT, 2)),
        Transaction(rawTextHash = "h", id = 8, amount = 1.0, direction = Direction.SENT, merchantOrContact = "A", bankOrSource = "X", timestampMillis = ms(2026, OCT, 2)))
    check("tie by id", PdfReportBuilder.inPeriod(tie, month).map { it.id } == listOf(8L, 9L))
    // day report
    val day = PdfPeriod.Day(2026, OCT, 12)
    val dBlocks = PdfReportBuilder.build(all, day, "Rs. ", ms(2026, OCT, 20))
    val dTx = dBlocks.filterIsInstance<Block.Table>().last()
    check("day one row", dTx.rows.size == 1 && dTx.rows[0][0] == "14:05")
    check("day header Time", dTx.columns[0].header == "Time")
    check("day title", (dBlocks[0] as Block.Title).text == "Expense Report - 12 October 2026")
    check("day end boundary", PdfReportBuilder.countIn(listOf(Transaction(rawTextHash = "h", amount = 1.0, direction = Direction.SENT, merchantOrContact = "e", bankOrSource = "X", timestampMillis = ms(2026, OCT, 12, 23, 59) + 59999)), day) == 1)
    check("day next excluded", PdfReportBuilder.countIn(listOf(Transaction(rawTextHash = "h", amount = 1.0, direction = Direction.SENT, merchantOrContact = "e", bankOrSource = "X", timestampMillis = ms(2026, OCT, 13, 0, 0))), day) == 0)
    // year report
    val yBlocks = PdfReportBuilder.build(all, PdfPeriod.Year(2026), "Rs. ", ms(2026, OCT, 20))
    check("year has month heading", yBlocks.filterIsInstance<Block.Heading>().any { it.text == "Month by month" })
    val mt = yBlocks.filterIsInstance<Block.Table>()[0]
    check("year rows run to last month with data + total", mt.rows.size == 12)
    val noNov = PdfReportBuilder.build(listOf(t1, t2, t3, t4, t5, sep), PdfPeriod.Year(2026), "Rs. ", ms(2026, OCT, 20)).filterIsInstance<Block.Table>()[0]
    check("year rows stop at current month when no later data", noNov.rows.size == 11)
    check("year total row", mt.rows.last()[0] == "Total" && mt.rows.last()[4] == "7")
    check("year sep row", mt.rows[8][0] == "September" && mt.rows[8][1] == "Rs. 777.00")
    check("year total spent", mt.rows.last()[1] == PdfMoney.format(1400.5 + 777.0 + 888.0, "Rs. "))
    val yPast = PdfReportBuilder.build(all, PdfPeriod.Year(2026), "Rs. ", ms(2027, Calendar.MARCH, 1))
    check("past year 12 months + total", yPast.filterIsInstance<Block.Table>()[0].rows.size == 13)
    check("label/filename", PdfReportBuilder.periodLabel(PdfPeriod.Year(2026)) == "2026" &&
        PdfReportBuilder.fileName(month) == "expense_report_2026-10.pdf" &&
        PdfReportBuilder.fileName(PdfPeriod.Day(2026, Calendar.MARCH, 5)) == "expense_report_2026-03-05.pdf" &&
        PdfReportBuilder.fileName(PdfPeriod.Year(2025)) == "expense_report_2025.pdf")
    check("only-income no category table", PdfReportBuilder.build(listOf(t3), month, "Rs. ", now).filterIsInstance<Block.Table>().size == 1)
    check("clean spaces", PdfReportBuilder.clean("  a \n\t b\u00a0c  ") == "a b c")

    // ---------- layout
    val cols = listOf(PdfColumn("Col1", 0.3f, Align.LEFT), PdfColumn("Col2", 0.5f, Align.LEFT), PdfColumn("Amt", 0.2f, Align.RIGHT))
    val rowsB = (0 until 200).map { listOf("r$it", if (it == 7) "x".repeat(300) else "m$it", "$it.00") }
    val lb = listOf(
        Block.Title("T", "sub"), Block.Heading("A"),
        Block.KeyValues(listOf("a" to "1", "b" to "2", "c" to "3", "d" to "4")),
        Block.Heading("B"), Block.Table(cols, rowsB),
        Block.Heading("C"), Block.Table(listOf(PdfColumn("ColX", 1f, Align.LEFT)), listOf(listOf("z1"), listOf("z2")), boldLastRow = true),
        Block.Note("end note"))
    val pages = PdfLayout.layout(lb, "Expense Tracker", Mono)
    check("multi page", pages.size >= 4)
    for (i in pages.indices) {
        val foot = texts(pages[i]).filter { it.y == PdfLayout.FOOTER_Y }.map { it.text }
        check("footer page ${i + 1}", foot.contains("Page ${i + 1} of ${pages.size}") && foot.contains("Expense Tracker"))
        check("in bounds p${i + 1}", body(pages[i]).all { it.y >= PdfLayout.TOP && it.y <= PdfLayout.CONTENT_BOTTOM })
    }
    val firstCol = pages.flatMap { body(it) }.filter { it.x == PdfLayout.MARGIN + PdfLayout.CELL_PAD && it.text.startsWith("r") && it.text.length <= 4 && it.size == PdfLayout.ROW_SIZE && !it.bold }.map { it.text }
    check("every row once, in order", firstCol == (0 until 200).map { "r$it" })
    val pagesWithRows = pages.count { p -> body(p).any { it.text == "r0" || (it.text.startsWith("r") && it.text.length <= 4 && it.size == PdfLayout.ROW_SIZE && !it.bold) } }
    val headers = pages.map { p -> body(p).count { it.text == "Col1" } }.sum()
    check("header repeats on each table page", headers == pagesWithRows && pagesWithRows >= 3)
    for ((i, p) in pages.withIndex()) {
        val ys = body(p).filter { it.size == PdfLayout.ROW_SIZE && !it.bold && it.x == PdfLayout.MARGIN + PdfLayout.CELL_PAD }.map { it.y }
        check("rows increase p${i + 1}", ys == ys.sorted() && ys.toSet().size == ys.size)
        val b = body(p)
        val lastIsHeading = b.isNotEmpty() && b.sortedBy { it.y }.last().size == PdfLayout.HEADING_SIZE
        check("heading not stranded p${i + 1}", !lastIsHeading)
        for (h in b.filter { it.size == PdfLayout.HEADING_SIZE }) check("heading has content after p${i + 1}", b.any { it.y > h.y })
    }
    val longCell = pages.flatMap { body(it) }.first { it.text.startsWith("xxx") }
    val colW = PdfLayout.CONTENT_W * 0.5f
    check("truncated with ...", longCell.text.endsWith("...") && longCell.text.length < 300)
    check("truncated fits column", Mono.width(longCell.text, PdfLayout.ROW_SIZE, false) <= colW - 2 * PdfLayout.CELL_PAD)
    val amt = pages.flatMap { body(it) }.first { it.text == "5.00" }
    check("amount right aligned", amt.align == Align.RIGHT && amt.x == PdfLayout.MARGIN + PdfLayout.CONTENT_W - PdfLayout.CELL_PAD)
    check("bold total row", pages.flatMap { body(it) }.first { it.text == "z2" }.bold && !pages.flatMap { body(it) }.first { it.text == "z1" }.bold)
    check("short text untouched", PdfLayout.fit("abc", 100f, 9f, false, Mono) == "abc")
    check("fit nothing fits", PdfLayout.fit("abcdef", 3f, 9f, false, Mono) == "")

    // heading gap reservation: 39 filler rows leave 64pt, heading + gaps + header + 1 row needs 71
    val filler = listOf(Block.Title("T", "s"),
        Block.KeyValues((0 until 39).map { "k$it" to "v" }),
        Block.Heading("H"),
        Block.Table(listOf(PdfColumn("Only", 1f, Align.LEFT)), listOf(listOf("row1"), listOf("row2"))))
    val fp = PdfLayout.layout(filler, "f", Mono)
    check("gap scenario 2 pages", fp.size == 2)
    val hOp = fp.flatMap { body(it) }.first { it.text == "H" }
    check("heading moved to page 2", body(fp[1]).any { it.text == "H" } && body(fp[0]).none { it.text == "H" })
    check("heading at page top", hOp.y == PdfLayout.TOP + PdfLayout.HEADING_SIZE)
    check("table follows heading on same page", body(fp[1]).any { it.text == "Only" } && body(fp[1]).any { it.text == "row1" })
    // empty table does not crash and draws header
    val ep = PdfLayout.layout(listOf(Block.Heading("E"), Block.Table(cols, emptyList())), "f", Mono)
    check("empty table header only", ep.size == 1 && body(ep[0]).any { it.text == "Col1" })
    // no blocks
    check("no blocks one page", PdfLayout.layout(emptyList(), "f", Mono).size == 1)
    // a big realistic report end to end
    val many = (0 until 600).map { Transaction(rawTextHash = "h", id = it.toLong(), amount = 10.0 + it, direction = if (it % 3 == 0) Direction.RECEIVED else Direction.SENT, merchantOrContact = "Merchant number $it with a rather long name to truncate here", bankOrSource = "JD-SLCBNK-T", timestampMillis = ms(2026, OCT, 1 + it % 28, 8 + it % 10, it % 60), category = "FOOD") }
    val big = PdfLayout.layout(PdfReportBuilder.build(many, month, "Rs. ", now), "Expense Tracker", Mono)
    check("big report paginates", big.size >= 10)
    check("big report in bounds", big.all { p -> body(p).all { it.y <= PdfLayout.CONTENT_BOTTOM } })
    val bigRows = big.map { p -> body(p).count { it.align == Align.RIGHT && it.size == PdfLayout.ROW_SIZE && (it.text.startsWith("-Rs. ") || it.text.startsWith("+Rs. ")) } }.sum()
    check("big report has all 600 amounts", bigRows == 600)

    // ---------- period encode/decode (survives Activity recreation)
    val periods = listOf<PdfPeriod>(PdfPeriod.Day(2026, 9, 12), PdfPeriod.Month(2026, 0), PdfPeriod.Year(2025), PdfPeriod.Day(2024, 1, 29))
    for (p in periods) check("period roundtrip $p", PdfPeriods.decode(PdfPeriods.encode(p)) == p)
    check("decode null", PdfPeriods.decode(null) == null)
    check("decode garbage", PdfPeriods.decode("") == null && PdfPeriods.decode("X:1") == null && PdfPeriods.decode("M:abc:1") == null)
    check("decode wrong arity", PdfPeriods.decode("D:2026:1") == null && PdfPeriods.decode("Y:2026:1") == null && PdfPeriods.decode("M:2026") == null)
    check("decode bad month", PdfPeriods.decode("M:2026:12") == null && PdfPeriods.decode("D:2026:-1:5") == null && PdfPeriods.decode("D:2026:1:0") == null)

    checks.assertAll()
    }
}
