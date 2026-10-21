package com.autoexpensetracker.pdf

import com.autoexpensetracker.data.Category
import com.autoexpensetracker.data.Direction
import com.autoexpensetracker.data.Transaction
import java.util.Calendar

/**
 * Builds the content of a day, month or year report from transactions.
 * Pure: no Android, no database.
 *
 * Totals follow the rules used everywhere else in the app:
 *  - "Spent" is SENT and "Received" is RECEIVED;
 *  - rows that still need review ARE counted (they are real transactions
 *    whose amount or direction the parser was unsure about) and are marked
 *    with "*" in the list;
 *  - UNKNOWN-direction rows are listed but count as neither spent nor
 *    received.
 * The statement lists transactions oldest first, the way a bank's does.
 *
 * Text is kept to plain characters: no emoji, "-" for minus, "..." for
 * truncation, so it draws in any default font.
 */
object PdfReportBuilder {

    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    class Range(val start: Long, val end: Long)

    fun rangeOf(period: PdfPeriod): Range {
        val c = Calendar.getInstance()
        c.clear()
        return when (period) {
            is PdfPeriod.Day -> {
                c.set(period.year, period.month, period.day, 0, 0, 0)
                val start = c.timeInMillis
                c.add(Calendar.DAY_OF_MONTH, 1)
                Range(start, c.timeInMillis - 1)
            }
            is PdfPeriod.Month -> {
                c.set(period.year, period.month, 1, 0, 0, 0)
                val start = c.timeInMillis
                c.add(Calendar.MONTH, 1)
                Range(start, c.timeInMillis - 1)
            }
            is PdfPeriod.Year -> {
                c.set(period.year, Calendar.JANUARY, 1, 0, 0, 0)
                val start = c.timeInMillis
                c.add(Calendar.YEAR, 1)
                Range(start, c.timeInMillis - 1)
            }
        }
    }

    fun inPeriod(transactions: List<Transaction>, period: PdfPeriod): List<Transaction> {
        val r = rangeOf(period)
        return transactions
            .filter { it.timestampMillis >= r.start && it.timestampMillis <= r.end }
            .sortedWith(compareBy({ it.timestampMillis }, { it.id }))
    }

    fun countIn(transactions: List<Transaction>, period: PdfPeriod): Int = inPeriod(transactions, period).size

    fun periodLabel(period: PdfPeriod): String = when (period) {
        is PdfPeriod.Day -> "${period.day} ${MONTHS[period.month]} ${period.year}"
        is PdfPeriod.Month -> "${MONTHS[period.month]} ${period.year}"
        is PdfPeriod.Year -> "${period.year}"
    }

    fun fileName(period: PdfPeriod): String = when (period) {
        is PdfPeriod.Day -> "expense_report_${period.year}-${two(period.month + 1)}-${two(period.day)}.pdf"
        is PdfPeriod.Month -> "expense_report_${period.year}-${two(period.month + 1)}.pdf"
        is PdfPeriod.Year -> "expense_report_${period.year}.pdf"
    }

    fun build(
        transactions: List<Transaction>,
        period: PdfPeriod,
        currencyPrefix: String,
        nowMillis: Long
    ): List<Block> {
        val txs = inPeriod(transactions, period)

        var spent = 0.0
        var received = 0.0
        var unknown = 0
        var review = 0
        for (t in txs) {
            when (t.direction) {
                Direction.SENT -> spent += t.amount
                Direction.RECEIVED -> received += t.amount
                Direction.UNKNOWN -> unknown++
            }
            if (t.needsReview) review++
        }
        fun money(v: Double) = PdfMoney.format(v, currencyPrefix)

        val blocks = ArrayList<Block>()
        blocks.add(Block.Title("Expense Report - " + periodLabel(period), "Generated on " + dateOnly(nowMillis)))

        blocks.add(Block.Heading("Summary"))
        blocks.add(
            Block.KeyValues(
                listOf(
                    "Spent" to money(spent),
                    "Received" to money(received),
                    "Net (received - spent)" to money(received - spent),
                    "Transactions" to txs.size.toString()
                )
            )
        )
        if (unknown > 0) {
            blocks.add(Block.Note(plural(unknown, "transaction") + " with an unknown direction " + (if (unknown == 1) "is" else "are") + " listed but not included in Spent or Received."))
        }
        if (review > 0) {
            blocks.add(Block.Note("* marks " + plural(review, "transaction") + " still waiting for review; the amount or direction may be wrong. They are included in the totals."))
        }

        if (period is PdfPeriod.Year) {
            blocks.add(Block.Heading("Month by month"))
            blocks.add(monthTable(txs, period.year, nowMillis, currencyPrefix))
        }

        val categoryRows = categoryRows(txs, spent, currencyPrefix)
        if (categoryRows.isNotEmpty()) {
            blocks.add(Block.Heading("Spending by category"))
            blocks.add(
                Block.Table(
                    listOf(
                        PdfColumn("Category", 0.55f, Align.LEFT),
                        PdfColumn("Share", 0.15f, Align.RIGHT),
                        PdfColumn("Amount", 0.30f, Align.RIGHT)
                    ),
                    categoryRows + listOf(listOf("Total spent", "", money(spent))),
                    boldLastRow = true
                )
            )
        }

        blocks.add(Block.Heading("Transactions (oldest first)"))
        val rows = ArrayList<List<String>>()
        for (t in txs) {
            val merchant = (if (t.needsReview) "* " else "") + clean(t.merchantOrContact ?: "-").ifEmpty { "-" }
            val amountText = when (t.direction) {
                Direction.SENT -> "-" + money(t.amount)
                Direction.RECEIVED -> "+" + money(t.amount)
                Direction.UNKNOWN -> money(t.amount) + " (?)"
            }
            rows.add(
                listOf(
                    stamp(t.timestampMillis, period is PdfPeriod.Day),
                    merchant,
                    clean(t.bankOrSource),
                    categoryLabel(t.category),
                    amountText
                )
            )
        }
        blocks.add(
            Block.Table(
                listOf(
                    PdfColumn(if (period is PdfPeriod.Day) "Time" else "Date", 0.15f, Align.LEFT),
                    PdfColumn("Merchant", 0.31f, Align.LEFT),
                    PdfColumn("Account", 0.17f, Align.LEFT),
                    PdfColumn("Category", 0.15f, Align.LEFT),
                    PdfColumn("Amount", 0.22f, Align.RIGHT)
                ),
                rows
            )
        )
        return blocks
    }

    private fun monthTable(txs: List<Transaction>, year: Int, nowMillis: Long, prefix: String): Block.Table {
        val spent = DoubleArray(12)
        val received = DoubleArray(12)
        val counts = IntArray(12)
        val cal = Calendar.getInstance()
        for (t in txs) {
            cal.timeInMillis = t.timestampMillis
            val m = cal.get(Calendar.MONTH)
            counts[m]++
            if (t.direction == Direction.SENT) spent[m] += t.amount
            if (t.direction == Direction.RECEIVED) received[m] += t.amount
        }
        // Stop at the current month for the current year; every month otherwise.
        // Never stop before the last month that has a transaction (a phone
        // clock set back, say), or the table total would disagree with the summary.
        cal.timeInMillis = nowMillis
        var lastMonth = if (cal.get(Calendar.YEAR) == year) cal.get(Calendar.MONTH) else 11
        for (m in 0..11) if (counts[m] > 0 && m > lastMonth) lastMonth = m

        val rows = ArrayList<List<String>>()
        var totalSpent = 0.0
        var totalReceived = 0.0
        var totalCount = 0
        for (m in 0..lastMonth) {
            totalSpent += spent[m]; totalReceived += received[m]; totalCount += counts[m]
            rows.add(
                listOf(
                    MONTHS[m],
                    PdfMoney.format(spent[m], prefix),
                    PdfMoney.format(received[m], prefix),
                    PdfMoney.format(received[m] - spent[m], prefix),
                    counts[m].toString()
                )
            )
        }
        rows.add(
            listOf(
                "Total",
                PdfMoney.format(totalSpent, prefix),
                PdfMoney.format(totalReceived, prefix),
                PdfMoney.format(totalReceived - totalSpent, prefix),
                totalCount.toString()
            )
        )
        return Block.Table(
            listOf(
                PdfColumn("Month", 0.20f, Align.LEFT),
                PdfColumn("Spent", 0.25f, Align.RIGHT),
                PdfColumn("Received", 0.25f, Align.RIGHT),
                PdfColumn("Net", 0.20f, Align.RIGHT),
                PdfColumn("Txns", 0.10f, Align.RIGHT)
            ),
            rows,
            boldLastRow = true
        )
    }

    private fun categoryRows(txs: List<Transaction>, totalSpent: Double, prefix: String): List<List<String>> {
        if (totalSpent <= 0.0) return emptyList()
        val totals = LinkedHashMap<String, Double>()
        for (t in txs) {
            if (t.direction != Direction.SENT) continue
            val label = categoryLabel(t.category).let { if (it == "-") "Other" else it }
            totals[label] = (totals[label] ?: 0.0) + t.amount
        }
        return totals.entries
            .sortedByDescending { it.value }
            .map { listOf(it.key, share(it.value, totalSpent), PdfMoney.format(it.value, prefix)) }
    }

    internal fun share(amount: Double, total: Double): String {
        if (total <= 0.0 || amount <= 0.0) return "0%"
        val pct = amount / total * 100.0
        return if (pct < 1.0) "<1%" else pct.toInt().toString() + "%"
    }

    private fun categoryLabel(name: String?): String {
        val c = Category.fromNameOrNull(name) ?: return "-"
        return clean(c.label)
    }

    private fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    private fun dateOnly(millis: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "14:05" for a day report, "12 Oct 14:05" otherwise (the year is in the title). */
    private fun stamp(millis: Long, timeOnly: Boolean): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        val time = two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE))
        if (timeOnly) return time
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)].substring(0, 3)} $time"
    }

    /**
     * Text safe for any default font: drops emoji, pictographs, variation
     * selectors and zero-width joiners; turns typographic dashes, the
     * Unicode minus, curly quotes and the ellipsis character into plain
     * ASCII; flattens line breaks and repeated spaces. Letters of any
     * script are kept.
     */
    internal fun clean(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = Character.codePointAt(raw, i)
            i += Character.charCount(cp)
            when {
                cp == 0x2212 || cp == 0x2013 || cp == 0x2014 || cp == 0x2010 || cp == 0x2011 -> sb.append('-')
                cp == 0x2018 || cp == 0x2019 -> sb.append('\'')
                cp == 0x201C || cp == 0x201D -> sb.append('"')
                cp == 0x2026 -> sb.append("...")
                cp == 0x200D || cp == 0x200B || (cp in 0xFE00..0xFE0F) -> { }
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF -> { }
                cp == 0xA0 || Character.isWhitespace(cp) || Character.isISOControl(cp) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        val out = StringBuilder()
        var lastSpace = true
        for (ch in sb) {
            if (ch == ' ') {
                if (!lastSpace) out.append(' ')
                lastSpace = true
            } else {
                out.append(ch)
                lastSpace = false
            }
        }
        return out.toString().trim()
    }
}
