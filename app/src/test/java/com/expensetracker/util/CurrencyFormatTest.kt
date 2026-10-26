package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test
import java.util.Locale

class CurrencyFormatTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun compactFormatterShortensOnlyWhatDoesNotFit() {
        // below one lakh: exactly the normal formatter, so small cards do not change
        check("small matches full", formatInrCompact(4970.0) == formatInr(4970.0))
        check("just under a lakh matches full", formatInrCompact(99999.99) == formatInr(99999.99))
        check("zero matches full", formatInrCompact(0.0) == formatInr(0.0))
        check("small negative has the minus in front, like the large ones", formatInrCompact(-59.0) == "-" + formatInr(59.0))

        // lakhs
        check("exactly one lakh", formatInrCompact(100000.0) == "₹1 L")
        check("one and a half lakh", formatInrCompact(150000.0) == "₹1.5 L")
        check("the amount from the screenshot", formatInrCompact(153311.91) == "₹1.53 L")
        check("negative lakh keeps the sign", formatInrCompact(-148341.91) == "-₹1.48 L")
        check("ninety-nine lakh", formatInrCompact(9900000.0) == "₹99 L")

        // crores
        check("exactly one crore", formatInrCompact(10000000.0) == "₹1 Cr")
        check("two point four crore", formatInrCompact(24000000.0) == "₹2.4 Cr")
        check("large crore", formatInrCompact(123456789.0) == "₹12.35 Cr")
        check("negative crore", formatInrCompact(-25000000.0) == "-₹2.5 Cr")

        // digits and decimal point stay ASCII whatever the phone's language
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            check("ASCII under an Arabic locale", formatInrCompact(153311.91) == "₹1.53 L")
            Locale.setDefault(Locale("de", "DE"))
            check("decimal point under a German locale", formatInrCompact(153311.91) == "₹1.53 L")
        } finally {
            Locale.setDefault(saved)
        }
        checks.assertAll()
    }
}
