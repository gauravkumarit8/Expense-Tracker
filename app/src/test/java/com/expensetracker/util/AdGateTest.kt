package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test

class AdGateTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun adsOnlyAfterTheAppHasShownValue() {
        check("free user with transactions sees ads", AdGate.showAds(isPro = false, consentResolved = true, adFreeToday = false, transactionCount = 1))
        check("plenty of transactions", AdGate.showAds(false, true, false, 500))
        check("no transactions yet: no ads", !AdGate.showAds(false, true, false, 0))
        check("pro: no ads", !AdGate.showAds(true, true, false, 10))
        check("consent not resolved: no ads", !AdGate.showAds(false, false, false, 10))
        check("ad-free day: no ads", !AdGate.showAds(false, true, true, 10))
        check("pro with nothing: no ads", !AdGate.showAds(true, true, false, 0))
        check("every blocker at once", !AdGate.showAds(true, false, true, 0))
        check("a negative count never counts as having data", !AdGate.showAds(false, true, false, -1))
        // each condition is individually necessary
        for (pro in listOf(false, true)) for (consent in listOf(false, true)) for (free in listOf(false, true)) for (count in listOf(0, 3)) {
            val expected = !pro && consent && !free && count > 0
            check("matrix pro=$pro consent=$consent adFree=$free n=$count", AdGate.showAds(pro, consent, free, count) == expected)
        }
        checks.assertAll()
    }
}
