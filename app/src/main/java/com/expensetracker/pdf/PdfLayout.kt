package com.autoexpensetracker.pdf

/**
 * Turns report [Block]s into positioned drawing instructions, page by page
 * (A4, in points). Pure: the only thing it needs from the platform is a
 * [TextMeasurer].
 *
 * Rules:
 *  - A table's header row repeats at the top of every page the table spans.
 *  - A heading is never left alone at the bottom of a page: it moves to the
 *    next page unless the heading, its spacing above AND below, and the
 *    first piece of what follows all fit.
 *  - Cell text that is too wide is cut with "..." so nothing overflows.
 *  - Every page gets a footer with its number and the page count.
 */
object PdfLayout {

    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val MARGIN = 40f
    const val TOP = 40f
    const val CONTENT_BOTTOM = PAGE_H - 56f
    const val CONTENT_W = PAGE_W - 2f * MARGIN

    internal const val TITLE_SIZE = 20f
    internal const val TITLE_H = 26f
    internal const val SUB_SIZE = 10.5f
    internal const val SUB_H = 16f
    internal const val AFTER_TITLE = 10f
    internal const val HEADING_SIZE = 13f
    internal const val HEADING_H = 18f
    internal const val GAP_BEFORE_HEADING = 14f
    internal const val GAP_AFTER_HEADING = 6f
    internal const val KV_SIZE = 10.5f
    internal const val KV_H = 16f
    internal const val KV_VALUE_X = MARGIN + 170f
    internal const val ROW_SIZE = 9f
    internal const val ROW_H = 15f
    internal const val HEADER_H = 18f
    internal const val NOTE_SIZE = 8.5f
    internal const val NOTE_H = 12f
    internal const val BLOCK_GAP = 6f
    internal const val CELL_PAD = 4f
    internal const val FOOTER_SIZE = 8f
    internal const val FOOTER_Y = PAGE_H - 24f

    fun layout(blocks: List<Block>, footerLabel: String, measurer: TextMeasurer): List<LayoutPage> {
        val b = Builder(measurer)
        b.newPage()
        for (i in blocks.indices) {
            val block = blocks[i]
            val next = if (i + 1 < blocks.size) blocks[i + 1] else null
            when (block) {
                is Block.Title -> b.title(block)
                is Block.Heading -> b.heading(block, minFollowHeight(next))
                is Block.KeyValues -> b.keyValues(block)
                is Block.Table -> b.table(block)
                is Block.Note -> b.note(block)
            }
        }
        return b.finish(footerLabel)
    }

    /** The smallest piece of [block] that must sit on the same page as a heading above it. */
    internal fun minFollowHeight(block: Block?): Float = when (block) {
        null -> 0f
        is Block.Title -> TITLE_H + SUB_H
        is Block.Heading -> HEADING_H
        is Block.KeyValues -> if (block.rows.isEmpty()) 0f else KV_H
        is Block.Table -> HEADER_H + (if (block.rows.isEmpty()) 0f else ROW_H)
        is Block.Note -> NOTE_H
    }

    /** [text] shortened with "..." so it is no wider than [maxWidth]. */
    internal fun fit(text: String, maxWidth: Float, size: Float, bold: Boolean, m: TextMeasurer): String {
        if (m.width(text, size, bold) <= maxWidth) return text
        var n = text.length
        while (n > 0 && m.width(text.substring(0, n).trimEnd() + "...", size, bold) > maxWidth) n--
        val cut = text.substring(0, n).trimEnd() + "..."
        return if (m.width(cut, size, bold) <= maxWidth) cut else ""
    }

    private class Builder(val m: TextMeasurer) {
        private val pages = ArrayList<ArrayList<DrawOp>>()
        private var ops = ArrayList<DrawOp>()
        private var y = TOP

        fun newPage() {
            ops = ArrayList()
            pages.add(ops)
            y = TOP
        }

        private fun ensure(height: Float) {
            if (y + height > CONTENT_BOTTOM) newPage()
        }

        fun title(t: Block.Title) {
            ensure(TITLE_H + SUB_H + AFTER_TITLE)
            ops.add(DrawOp.Text(MARGIN, y + TITLE_SIZE, fit(t.text, CONTENT_W, TITLE_SIZE, true, m), TITLE_SIZE, true, Align.LEFT, false))
            y += TITLE_H
            ops.add(DrawOp.Text(MARGIN, y + SUB_SIZE, fit(t.subtitle, CONTENT_W, SUB_SIZE, false, m), SUB_SIZE, false, Align.LEFT, true))
            y += SUB_H + AFTER_TITLE
        }

        fun heading(h: Block.Heading, followHeight: Float) {
            // The reservation counts BOTH gaps. An earlier version added the
            // gap above the heading after checking that it fit, so a heading
            // could pass the check and then run over the bottom margin.
            var gapBefore = if (y > TOP) GAP_BEFORE_HEADING else 0f
            val needed = gapBefore + HEADING_H + GAP_AFTER_HEADING + followHeight
            if (y + needed > CONTENT_BOTTOM) {
                newPage()
                gapBefore = 0f
            }
            y += gapBefore
            ops.add(DrawOp.Text(MARGIN, y + HEADING_SIZE, fit(h.text, CONTENT_W, HEADING_SIZE, true, m), HEADING_SIZE, true, Align.LEFT, false))
            y += HEADING_H + GAP_AFTER_HEADING
        }

        fun keyValues(kv: Block.KeyValues) {
            for (row in kv.rows) {
                ensure(KV_H)
                ops.add(DrawOp.Text(MARGIN, y + KV_SIZE, fit(row.first, KV_VALUE_X - MARGIN - CELL_PAD, KV_SIZE, false, m), KV_SIZE, false, Align.LEFT, true))
                ops.add(DrawOp.Text(KV_VALUE_X, y + KV_SIZE, fit(row.second, PAGE_W - MARGIN - KV_VALUE_X, KV_SIZE, true, m), KV_SIZE, true, Align.LEFT, false))
                y += KV_H
            }
            y += BLOCK_GAP
        }

        fun note(n: Block.Note) {
            ensure(NOTE_H)
            ops.add(DrawOp.Text(MARGIN, y + NOTE_SIZE, fit(n.text, CONTENT_W, NOTE_SIZE, false, m), NOTE_SIZE, false, Align.LEFT, true))
            y += NOTE_H + BLOCK_GAP
        }

        fun table(t: Block.Table) {
            var totalWeight = 0f
            for (c in t.columns) totalWeight += c.weight
            val widths = FloatArray(t.columns.size)
            val xs = FloatArray(t.columns.size)
            var x = MARGIN
            for (i in t.columns.indices) {
                widths[i] = CONTENT_W * t.columns[i].weight / totalWeight
                xs[i] = x
                x += widths[i]
            }

            fun header() {
                ops.add(DrawOp.FillRect(MARGIN, y, CONTENT_W, HEADER_H))
                for (i in t.columns.indices) {
                    val c = t.columns[i]
                    val text = fit(c.header, widths[i] - 2f * CELL_PAD, ROW_SIZE, true, m)
                    val tx = if (c.align == Align.LEFT) xs[i] + CELL_PAD else xs[i] + widths[i] - CELL_PAD
                    ops.add(DrawOp.Text(tx, y + HEADER_H - 5.5f, text, ROW_SIZE, true, c.align, false))
                }
                y += HEADER_H
            }

            ensure(HEADER_H + (if (t.rows.isEmpty()) 0f else ROW_H))
            header()
            for (r in t.rows.indices) {
                if (y + ROW_H > CONTENT_BOTTOM) {
                    newPage()
                    header()
                }
                val bold = t.boldLastRow && r == t.rows.size - 1
                val row = t.rows[r]
                for (i in t.columns.indices) {
                    val c = t.columns[i]
                    val cell = if (i < row.size) row[i] else ""
                    val text = fit(cell, widths[i] - 2f * CELL_PAD, ROW_SIZE, bold, m)
                    val tx = if (c.align == Align.LEFT) xs[i] + CELL_PAD else xs[i] + widths[i] - CELL_PAD
                    ops.add(DrawOp.Text(tx, y + ROW_H - 4.5f, text, ROW_SIZE, bold, c.align, false))
                }
                ops.add(DrawOp.Line(MARGIN, y + ROW_H, MARGIN + CONTENT_W, y + ROW_H, 0.5f, true))
                y += ROW_H
            }
            y += BLOCK_GAP
        }

        fun finish(footerLabel: String): List<LayoutPage> {
            val count = pages.size
            val out = ArrayList<LayoutPage>()
            for (i in pages.indices) {
                val p = ArrayList<DrawOp>(pages[i])
                p.add(DrawOp.Text(MARGIN, FOOTER_Y, footerLabel, FOOTER_SIZE, false, Align.LEFT, true))
                p.add(DrawOp.Text(PAGE_W - MARGIN, FOOTER_Y, "Page ${i + 1} of $count", FOOTER_SIZE, false, Align.RIGHT, true))
                out.add(LayoutPage(p))
            }
            return out
        }
    }
}
