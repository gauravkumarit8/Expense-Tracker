package com.autoexpensetracker.pdf

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * The only PDF code that touches Android: measures text with a real Paint
 * and draws the laid-out pages with android.graphics.pdf.PdfDocument (part
 * of the platform - no library). Everything about what goes where is
 * decided earlier, in PdfLayout.
 */
object PdfReportWriter {

    private const val GRAY = 0xFF666666.toInt()
    private const val BLACK = 0xFF111111.toInt()
    private const val RULE_LIGHT = 0xFFDDDDDD.toInt()
    private const val HEADER_FILL = 0xFFEEEEEE.toInt()

    /** The rupee sign if this device's default font can draw it, "Rs. " otherwise. */
    fun currencyPrefix(): String =
        if (Paint().hasGlyph(PdfMoney.RUPEE_SIGN)) PdfMoney.RUPEE_SIGN else PdfMoney.RUPEE_FALLBACK

    class AndroidMeasurer : TextMeasurer {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun width(text: String, size: Float, bold: Boolean): Float {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            return paint.measureText(text)
        }
    }

    /** Draws [pages] into a PDF written to [out]. Blocking; call it off the main thread. */
    fun write(pages: List<LayoutPage>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            for (i in pages.indices) {
                val info = PdfDocument.PageInfo.Builder(PdfLayout.PAGE_W.toInt(), PdfLayout.PAGE_H.toInt(), i + 1).create()
                val page = doc.startPage(info)
                draw(page.canvas, pages[i])
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun draw(canvas: android.graphics.Canvas, page: LayoutPage) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        val fill = Paint().apply { color = HEADER_FILL; style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        for (op in page.ops) {
            when (op) {
                is DrawOp.FillRect -> canvas.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, fill)
                is DrawOp.Line -> {
                    line.color = if (op.light) RULE_LIGHT else BLACK
                    line.strokeWidth = op.width
                    canvas.drawLine(op.x1, op.y1, op.x2, op.y2, line)
                }
                is DrawOp.Text -> {
                    text.textSize = op.size
                    text.typeface = if (op.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    text.color = if (op.gray) GRAY else BLACK
                    text.textAlign = if (op.align == Align.RIGHT) Paint.Align.RIGHT else Paint.Align.LEFT
                    canvas.drawText(op.text, op.x, op.y, text)
                }
            }
        }
    }

    /** Builds and writes the whole report for [period]. Call off the main thread. */
    fun export(
        transactions: List<com.autoexpensetracker.data.Transaction>,
        period: PdfPeriod,
        out: OutputStream,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val blocks = PdfReportBuilder.build(transactions, period, currencyPrefix(), nowMillis)
        val pages = PdfLayout.layout(blocks, "Expense Tracker - Bank & UPI", AndroidMeasurer())
        write(pages, out)
    }
}
