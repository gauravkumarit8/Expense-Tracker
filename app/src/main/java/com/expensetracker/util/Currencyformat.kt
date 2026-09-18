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

/** e.g. formatInrWhole(30000.0) -> "₹30,000" — for places a decimal is just noise (budget limits, category totals rounded to the rupee). */
fun formatInrWhole(amount: Double): String = "₹${inrWholeFormat.format(amount)}"