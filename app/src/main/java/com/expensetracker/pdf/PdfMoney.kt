package com.autoexpensetracker.pdf

/**
 * Every amount in the PDF goes through [format]. The rupee sign (U+20B9)
 * only draws if the device font has it, so the caller chooses [prefix]:
 * the writer checks the font and passes "Rs. " if the glyph is missing.
 * Falling back is that one argument - nothing else changes.
 */
object PdfMoney {

    const val RUPEE_SIGN = "\u20B9"
    const val RUPEE_FALLBACK = "Rs. "

    /** Indian digit grouping (12,34,567.89), two decimals, ASCII minus. */
    fun format(amount: Double, prefix: String): String {
        val paise = Math.round(Math.abs(amount) * 100.0)
        val whole = (paise / 100L).toString()
        val frac = (paise % 100L).toInt()
        val sign = if (amount < 0.0 && paise != 0L) "-" else ""
        return sign + prefix + group(whole) + "." + (if (frac < 10) "0$frac" else "$frac")
    }

    internal fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder(digits.substring(digits.length - 3))
        var rest = digits.substring(0, digits.length - 3)
        while (rest.length > 2) {
            sb.insert(0, rest.substring(rest.length - 2) + ",")
            rest = rest.substring(0, rest.length - 2)
        }
        if (rest.isNotEmpty()) sb.insert(0, "$rest,")
        return sb.toString()
    }
}
