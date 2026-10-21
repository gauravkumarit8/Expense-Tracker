package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.util.ChartMath
import java.util.Calendar
private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long { val c = Calendar.getInstance(); c.clear(); c.set(y, m, d, h, 0, 0); return c.timeInMillis }

class ChartMathTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
    check("feb 2024", ChartMath.daysInMonth(2024, Calendar.FEBRUARY) == 29)
    check("feb 2026", ChartMath.daysInMonth(2026, Calendar.FEBRUARY) == 28)
    check("oct", ChartMath.daysInMonth(2026, Calendar.OCTOBER) == 31)
    val pts = listOf(
        ms(2026, Calendar.OCTOBER, 1, 0) to 100.0,
        ms(2026, Calendar.OCTOBER, 1, 23) to 50.0,
        ms(2026, Calendar.OCTOBER, 31, 23) to 7.0,
        ms(2026, Calendar.SEPTEMBER, 30, 23) to 999.0,
        ms(2026, Calendar.NOVEMBER, 1, 0) to 999.0,
        ms(2025, Calendar.OCTOBER, 5) to 999.0)
    val d = ChartMath.dailyTotals(pts, 2026, Calendar.OCTOBER)
    check("size", d.size == 31)
    check("day1 sum", d[0] == 150.0)
    check("day31", d[30] == 7.0)
    check("others zero", d.sum() == 157.0)
    check("peak", ChartMath.peakIndex(d) == 0)
    check("peak none", ChartMath.peakIndex(DoubleArray(5)) == -1)
    val now = ms(2026, Calendar.OCTOBER, 19)
    check("elapsed current", ChartMath.elapsedDays(2026, Calendar.OCTOBER, now) == 19)
    check("elapsed past", ChartMath.elapsedDays(2026, Calendar.SEPTEMBER, now) == 30)
    check("elapsed past year", ChartMath.elapsedDays(2025, Calendar.FEBRUARY, now) == 28)
    check("elapsed future", ChartMath.elapsedDays(2026, Calendar.NOVEMBER, now) == 0)
    check("elapsed future yr", ChartMath.elapsedDays(2027, Calendar.JANUARY, now) == 0)
    check("avg", ChartMath.dailyAverage(190.0, 19) == 10.0)
    check("avg zero days", ChartMath.dailyAverage(190.0, 0) == 0.0)
    check("frac half", ChartMath.barFraction(50.0, 100.0) == 0.5f)
    check("frac tiny", ChartMath.barFraction(0.1, 100.0) == 0.02f)
    check("frac zero", ChartMath.barFraction(0.0, 100.0) == 0f)
    check("frac nomax", ChartMath.barFraction(5.0, 0.0) == 0f)
    check("frac over", ChartMath.barFraction(200.0, 100.0) == 1f)
    check("pct 25", ChartMath.percentLabel(25.0, 100.0) == "25%")
    check("pct tiny", ChartMath.percentLabel(0.5, 100.0) == "<1%")
    check("pct 99.9", ChartMath.percentLabel(99.9, 100.0) == "99%")
    check("pct zero", ChartMath.percentLabel(0.0, 100.0) == "0%")
    check("pct no total", ChartMath.percentLabel(5.0, 0.0) == "0%")
    checks.assertAll()
    }
}
