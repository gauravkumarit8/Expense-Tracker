package com.autoexpensetracker.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Formats a rupee amount with Indian-style digit grouping — "2,21,837.21"
 * (lakh/crore grouping), not "221837.21" — plus the ₹ prefix.
 *
 * Every currency display in the app should go through [formatInr] or
 * [formatInrWhole] rather than raw `"₹${"%.2f".format(x)}"` string
 * interpolation. Ungrouped digits get genuinely hard to scan past four
 * digits, and this is a finance app where users routinely see 5-6 digit
 * amounts (salary credits, monthly totals) — grouping isn't cosmetic
 * here, it's the difference between reading a number and counting digits.
 */
private val inrDecimalFormat: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}

private val inrWholeFormat: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 0
}

/** e.g. formatInr(221837.21) -> "₹2,21,837.21" */
fun formatInr(amount: Double): String = "₹${inrDecimalFormat.format(amount)}"

/**
 * For narrow cards where the full amount does not fit: below one lakh it is
 * the normal [formatInr]; from one lakh up it is "₹1.53 L", from one crore
 * "₹2.4 Cr" (at most two decimals, trailing zeros dropped). The sign is kept.
 * The full amount is always available elsewhere on the screen or in the list.
 */
fun formatInrCompact(amount: Double): String {
    val a = Math.abs(amount)
    val sign = if (amount < 0.0) "-" else ""
    return when {
        a >= 1.0E7 -> sign + "₹" + trimmed(a / 1.0E7) + " Cr"
        a >= 1.0E5 -> sign + "₹" + trimmed(a / 1.0E5) + " L"
        // formatInr alone would put the minus after the rupee sign ("₹-59.00"); keep it in front, as above.
        else -> sign + formatInr(a)
    }
}

private fun trimmed(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** e.g. formatInrWhole(30000.0) -> "₹30,000" — for places a decimal is just noise (budget limits, category totals rounded to the rupee). */
fun formatInrWhole(amount: Double): String = "₹${inrWholeFormat.format(amount)}"