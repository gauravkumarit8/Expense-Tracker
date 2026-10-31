package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.util.MonthPace as M

class BankNamesMonthPaceTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)
    private val money: (Double) -> String = { "Rs" + Math.round(it) }
    private fun input(spent: Double, day: Int, days: Int = 30, last: Double = 0.0, limit: Double = 0.0, bSpent: Double = 0.0) =
        M.Input(spent, day, days, last, limit, bSpent)

    @Test
    fun allChecks() {
        // ---- BankNames
        check("central bank, full sender id", BankNames.friendly("JK-CENTBK-T") == "Central Bank of India")
        check("central bank, other route prefix", BankNames.friendly("AD-CENTBK-S") == "Central Bank of India")
        check("hdfc bare code", BankNames.friendly("HDFCBK") == "HDFC Bank")
        check("hdfc other code", BankNames.friendly("VM-HDFCBN-T") == "HDFC Bank")
        check("sbi internet banking", BankNames.friendly("VM-SBIINB-S") == "State Bank of India")
        check("sbi card is not plain sbi", BankNames.friendly("SBICRD") == "SBI Card")
        check("icici", BankNames.friendly("JD-ICICIB-T") == "ICICI Bank")
        check("axis", BankNames.friendly("AXISBK") == "Axis Bank")
        check("slice", BankNames.friendly("JD-SLCBNK-T") == "Slice")
        check("union bank", BankNames.friendly("UNIONB") == "Union Bank of India")
        check("unknown code is returned unchanged", BankNames.friendly("XY-ZZQQWW-T") == "XY-ZZQQWW-T")
        check("cash is not renamed", BankNames.friendly("Cash") == "Cash")
        check("user label with a space is never renamed", BankNames.friendly("SBI Savings") == "SBI Savings")
        check("user label is kept exactly", BankNames.friendly("Mom's wallet") == "Mom's wallet")
        check("typed lowercase bank code is named", BankNames.friendly("hdfc") == "HDFC Bank")
        check("empty stays empty", BankNames.friendly("") == "")
        check("raw code kept for grouping", BankSourceMatcher.accountKey("JK-CENTBK-T") == "CENTBK")

        // ---- MonthPace: projection
        var p = M.compute(input(3000.0, 10))
        check("projection: 3000 in 10 of 30 days = 9000", p.projected != null && Math.round(p.projected!!) == 9000L)
        check("no projection before day 5", M.compute(input(3000.0, 4)).projected == null)
        check("projection starts on day 5", M.compute(input(500.0, 5)).projected != null)
        check("no spending, no projection", M.compute(input(0.0, 20)).projected == null)
        p = M.compute(input(9000.0, 30))
        check("last day: projection equals spent", p.projected != null && Math.round(p.projected!!) == 9000L)
        check("days left counts today", M.compute(input(100.0, 10, 30)).daysLeft == 21 && M.compute(input(100.0, 30, 30)).daysLeft == 1)
        check("february length used", Math.round(M.compute(input(1400.0, 14, 28)).projected!!) == 2800L)
        // ---- vs last month
        p = M.compute(input(3000.0, 10, 30, last = 7500.0))
        check("20% more than last month: 9000 vs 7500", p.changePercent == 20)
        p = M.compute(input(3000.0, 10, 30, last = 12000.0))
        check("25% less than last month", p.changePercent == -25)
        check("no last month, no comparison", M.compute(input(3000.0, 10)).changePercent == null)
        check("no comparison without a projection", M.compute(input(3000.0, 3, 30, last = 5000.0)).changePercent == null)
        // ---- budgets
        p = M.compute(input(2000.0, 10, 30, limit = 10000.0, bSpent = 4000.0))
        check("budget left 6000 over 21 days", p.budgetLeft == 6000.0 && p.perDayAllowance != null && Math.round(p.perDayAllowance!!) == 286L)
        p = M.compute(input(2000.0, 10, 30, limit = 10000.0, bSpent = 11500.0))
        check("over budget: negative left, no allowance", p.budgetLeft == -1500.0 && p.perDayAllowance == null)
        p = M.compute(input(2000.0, 10, 30, limit = 10000.0, bSpent = 10000.0))
        check("exactly used up: zero left, no allowance", p.budgetLeft == 0.0 && p.perDayAllowance == null)
        check("no budgets: no budget line", M.compute(input(2000.0, 10)).budgetLeft == null)
        check("budget line works even early in the month (no projection yet)", M.hasContent(M.compute(input(100.0, 2, 30, limit = 5000.0, bSpent = 100.0))))
        check("nothing to show: no spend, no budgets", !M.hasContent(M.compute(input(0.0, 12))))
        check("early and no budgets: nothing", !M.hasContent(M.compute(input(900.0, 2))))
        // ---- wording
        var l = M.lines(M.compute(input(3000.0, 10, 30, last = 7500.0, limit = 10000.0, bSpent = 4000.0)), money)
        check("three lines when everything applies", l.size == 3)
        check("projection line", l[0] == "On pace to spend about Rs9000 this month.")
        check("comparison line", l[1] == "20% more than last month (Rs7500).")
        check("allowance line", l[2] == "To stay within your budgets, spend about Rs286 each of the next 21 days.")
        l = M.lines(M.compute(input(3000.0, 10, 30, last = 9300.0)), money)
        check("within 10% reads about the same", l[1] == "About the same as last month (Rs9300).")
        l = M.lines(M.compute(input(3000.0, 10, 30, last = 10000.0)), money)
        check("10% less is reported", l[1] == "10% less than last month (Rs10000).")
        l = M.lines(M.compute(input(100.0, 12, 30, limit = 1000.0, bSpent = 1600.0)), money)
        check("over budget wording", l.contains("You are Rs600 over your budgets this month."))
        l = M.lines(M.compute(input(100.0, 12, 30, limit = 1000.0, bSpent = 1000.0)), money)
        check("used up wording", l.contains("Your budgets are used up for this month."))
        l = M.lines(M.compute(input(100.0, 30, 30, limit = 3000.0, bSpent = 1000.0)), money)
        check("last day wording says today", l.any { it.endsWith("spend about Rs2000 today.") })
        check("no content: no lines", M.lines(M.compute(input(0.0, 12)), money).isEmpty())
        // ---- robustness
        check("day 0 does not crash or divide by zero", M.compute(input(100.0, 0)).daysLeft > 0)
        check("daysInMonth smaller than day is repaired", M.compute(input(100.0, 31, 30)).daysLeft == 1)
        checks.assertAll()
    }
}
