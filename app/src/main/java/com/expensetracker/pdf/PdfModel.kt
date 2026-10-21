package com.autoexpensetracker.pdf

/**
 * Plain data for the PDF report: what to say (Block), where it lands on a
 * page (DrawOp / LayoutPage) and how to measure text (TextMeasurer). No
 * Android types, so the layout and the report content can be compiled and
 * tested on their own; only PdfReportWriter touches android.graphics.
 */

enum class Align { LEFT, RIGHT }

class PdfColumn(val header: String, val weight: Float, val align: Align)

sealed class Block {
    class Title(val text: String, val subtitle: String) : Block()
    class Heading(val text: String) : Block()
    class KeyValues(val rows: List<Pair<String, String>>) : Block()
    /** [boldLastRow] makes the final row (a totals row) bold. */
    class Table(val columns: List<PdfColumn>, val rows: List<List<String>>, val boldLastRow: Boolean = false) : Block()
    class Note(val text: String) : Block()
}

sealed class DrawOp {
    /** [y] is the text baseline. [gray] is secondary text. */
    class Text(val x: Float, val y: Float, val text: String, val size: Float, val bold: Boolean, val align: Align, val gray: Boolean) : DrawOp()
    /** [light] is a faint separator rather than a rule. */
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val light: Boolean) : DrawOp()
    class FillRect(val x: Float, val y: Float, val w: Float, val h: Float) : DrawOp()
}

class LayoutPage(val ops: List<DrawOp>)

interface TextMeasurer {
    fun width(text: String, size: Float, bold: Boolean): Float
}

/** The period a report covers. Months are 0-based (Calendar.JANUARY = 0), matching MonthRange and DayRange. */
sealed class PdfPeriod {
    data class Day(val year: Int, val month: Int, val day: Int) : PdfPeriod()
    data class Month(val year: Int, val month: Int) : PdfPeriod()
    data class Year(val year: Int) : PdfPeriod()
}

/**
 * A period as a short string and back, so a pending export survives the
 * Activity being recreated while the system file picker is open (a plain
 * `remember` would be lost and the export would silently do nothing).
 */
object PdfPeriods {
    fun encode(p: PdfPeriod): String = when (p) {
        is PdfPeriod.Day -> "D:${p.year}:${p.month}:${p.day}"
        is PdfPeriod.Month -> "M:${p.year}:${p.month}"
        is PdfPeriod.Year -> "Y:${p.year}"
    }

    fun decode(s: String?): PdfPeriod? {
        if (s == null) return null
        val parts = s.split(":")
        val n = IntArray(parts.size - 1)
        for (i in 1 until parts.size) n[i - 1] = parts[i].toIntOrNull() ?: return null
        return when {
            parts[0] == "D" && n.size == 3 && n[1] in 0..11 && n[2] in 1..31 -> PdfPeriod.Day(n[0], n[1], n[2])
            parts[0] == "M" && n.size == 2 && n[1] in 0..11 -> PdfPeriod.Month(n[0], n[1])
            parts[0] == "Y" && n.size == 1 -> PdfPeriod.Year(n[0])
            else -> null
        }
    }
}
