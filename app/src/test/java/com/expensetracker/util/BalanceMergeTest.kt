package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.util.BalanceMerge as B

class BalanceMergeTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)
    private fun c(src: String, amt: Double, at: Long) = B.Candidate(src, amt, at)

    @Test
    fun allChecks() {
        val none = emptySet<String>()
        // auto only
        var r = B.resolve(listOf(c("JK-SLCBNK-T", 100.0, 10), c("JK-SLCBNK-T", 90.0, 20)), emptyList(), none)
        check("auto only: latest wins", r.size == 1 && r[0].amount == 90.0 && !r[0].isManual)
        // THE BUG: manual edit, then a newer alert must take over again
        r = B.resolve(listOf(c("JK-SLCBNK-T", 90.0, 20), c("JK-SLCBNK-T", 70.0, 50)), listOf(c("JK-SLCBNK-T", 500.0, 30)), none)
        check("newer alert beats older manual edit", r.size == 1 && r[0].amount == 70.0 && !r[0].isManual)
        // manual edit newer than the last alert holds
        r = B.resolve(listOf(c("JK-SLCBNK-T", 90.0, 20)), listOf(c("JK-SLCBNK-T", 500.0, 30)), none)
        check("manual newer than alert holds", r.size == 1 && r[0].amount == 500.0 && r[0].isManual)
        // an alert OLDER than the edit (late / back-filled) must not override it
        r = B.resolve(listOf(c("JK-SLCBNK-T", 90.0, 25)), listOf(c("JK-SLCBNK-T", 500.0, 30)), none)
        check("older alert does not override", r[0].amount == 500.0 && r[0].isManual)
        // tie goes to the typed figure
        r = B.resolve(listOf(c("JK-SLCBNK-T", 90.0, 30)), listOf(c("JK-SLCBNK-T", 500.0, 30)), none)
        check("tie: manual wins", r[0].amount == 500.0 && r[0].isManual)
        // manual under another route prefix still matches by account key
        r = B.resolve(listOf(c("JK-SLCBNK-T", 70.0, 50)), listOf(c("JD-SLCBNK-T", 500.0, 30)), none)
        check("route variants share a key (newer alert wins)", r.size == 1 && r[0].amount == 70.0)
        r = B.resolve(listOf(c("JK-SLCBNK-T", 70.0, 20)), listOf(c("JD-SLCBNK-T", 500.0, 30)), none)
        check("route variants share a key (manual newer wins, one card)", r.size == 1 && r[0].amount == 500.0)
        // manual only (cash account) never disappears
        r = B.resolve(emptyList(), listOf(c("Cash", 1200.0, 5)), none)
        check("manual only shown", r.size == 1 && r[0].isManual && r[0].amount == 1200.0)
        // several manual edits: latest one is compared
        r = B.resolve(listOf(c("HDFCBN", 10.0, 40)), listOf(c("HDFCBN", 1.0, 10), c("HDFCBN", 2.0, 35)), none)
        check("latest of several manual entries is compared", r[0].amount == 10.0 && !r[0].isManual)
        // hidden
        r = B.resolve(listOf(c("JK-SLCBNK-T", 70.0, 50)), emptyList(), setOf("JD-SLCBNK-T"))
        check("hidden auto card (any route) is not shown", r.isEmpty())
        r = B.resolve(emptyList(), listOf(c("Cash", 1.0, 5)), setOf("Cash"))
        check("hidden manual card is not shown", r.isEmpty())
        r = B.resolve(listOf(c("JK-SLCBNK-T", 90.0, 20)), listOf(c("JD-SLCBNK-T", 500.0, 30)), setOf("JK-SLCBNK-T"))
        check("auto hidden but a manual card exists: manual shown", r.size == 1 && r[0].isManual)
        // different banks never merge; ordering newest first
        r = B.resolve(listOf(c("JK-SLCBNK-T", 1.0, 10), c("JK-CENTBK-T", 2.0, 20)), emptyList(), none)
        check("different banks stay separate, newest first", r.size == 2 && r[0].source == "JK-CENTBK-T")
        check("empty", B.resolve(emptyList(), emptyList(), none).isEmpty())
        checks.assertAll()
    }
}
