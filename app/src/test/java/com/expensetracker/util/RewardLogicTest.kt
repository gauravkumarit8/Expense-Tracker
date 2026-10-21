package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import org.junit.Test
import com.autoexpensetracker.util.RewardLogic as R
import java.util.Calendar
import java.util.TimeZone
private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long { val c = Calendar.getInstance(); c.clear(); c.set(y, m, d, h, 0, 0); return c.timeInMillis }

class RewardLogicTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    @Test
    fun allChecks() {
    val oct = ms(2026, Calendar.OCTOBER, 20)
    // ---- indexes
    check("month idx consecutive", R.monthIndex(ms(2027, Calendar.JANUARY, 1)) - R.monthIndex(ms(2026, Calendar.DECEMBER, 31)) == 1)
    check("month idx same month", R.monthIndex(ms(2026, Calendar.OCTOBER, 1, 0)) == R.monthIndex(ms(2026, Calendar.OCTOBER, 31, 23)))
    check("day idx next day", R.dayIndex(ms(2026, Calendar.OCTOBER, 21, 0)) - R.dayIndex(ms(2026, Calendar.OCTOBER, 20, 23)) == 1)
    check("day idx same day", R.dayIndex(ms(2026, Calendar.OCTOBER, 20, 0)) == R.dayIndex(ms(2026, Calendar.OCTOBER, 20, 23)))
    val ist = TimeZone.getTimeZone("Asia/Kolkata")
    // 2026-10-20 23:00 IST = 17:30 UTC same day; 2026-10-21 00:30 IST = 19:00 UTC Oct 20
    val c1 = Calendar.getInstance(ist); c1.clear(); c1.set(2026, Calendar.OCTOBER, 21, 0, 30, 0)
    val c2 = Calendar.getInstance(ist); c2.clear(); c2.set(2026, Calendar.OCTOBER, 20, 23, 30, 0)
    check("day idx respects tz", R.dayIndex(c1.timeInMillis, ist) - R.dayIndex(c2.timeInMillis, ist) == 1)

    // ---- monthly unlock lifecycle
    var u = R.NO_UNLOCK
    check("fresh: not available", !R.isUnlockAvailable(u, oct))
    check("fresh: not spent", !R.isUnlockSpent(u, oct))
    u = R.earnUnlock(u, oct)
    check("earned: available", R.isUnlockAvailable(u, oct))
    check("earned: not spent", !R.isUnlockSpent(u, oct))
    check("earned persists later in month", R.isUnlockAvailable(u, ms(2026, Calendar.OCTOBER, 31, 23)))
    val u2 = R.earnUnlock(u, oct)
    check("earn twice same", u2 == u)
    u = R.spendUnlock(u, oct)
    check("spent: not available", !R.isUnlockAvailable(u, oct))
    check("spent: flagged spent", R.isUnlockSpent(u, oct))
    check("spent persists same month", R.isUnlockSpent(u, ms(2026, Calendar.OCTOBER, 31, 23)))
    check("earn again same month stays spent", R.isUnlockSpent(R.earnUnlock(u, oct), oct))
    val nov = ms(2026, Calendar.NOVEMBER, 1, 0)
    check("resets next month: not spent", !R.isUnlockSpent(u, nov))
    check("resets next month: not available", !R.isUnlockAvailable(u, nov))
    val u3 = R.earnUnlock(u, nov)
    check("can earn next month", R.isUnlockAvailable(u3, nov))
    check("spend without earn no-op", R.spendUnlock(R.NO_UNLOCK, oct) == R.NO_UNLOCK)
    check("spend stale month no-op", R.spendUnlock(R.earnUnlock(R.NO_UNLOCK, oct), nov).usedMonth == null)
    // year rollover
    val dec = ms(2026, Calendar.DECEMBER, 31, 23); val jan = ms(2027, Calendar.JANUARY, 1, 0)
    val ud = R.spendUnlock(R.earnUnlock(R.NO_UNLOCK, dec), dec)
    check("dec spent", R.isUnlockSpent(ud, dec))
    check("jan reset", !R.isUnlockSpent(ud, jan) && !R.isUnlockAvailable(ud, jan))
    // clock set back a month must not hand out a second unlock
    val back = ms(2026, Calendar.SEPTEMBER, 15)
    val spentOct = R.spendUnlock(R.earnUnlock(R.NO_UNLOCK, oct), oct)
    check("clock back 1 month: still spent", R.isUnlockSpent(spentOct, back))
    check("clock back 1 month: cannot re-earn into new unlock", !R.isUnlockAvailable(R.earnUnlock(spentOct, back), back))
    // clock set far back (beyond tolerance): real clock wins (documented limit)
    val farBack = ms(2026, Calendar.JUNE, 15)
    check("clock back 4 months: beyond tolerance", !R.isUnlockSpent(spentOct, farBack))
    // clock jumped far ahead by mistake then restored must not freeze allowance until then
    val ahead = ms(2030, Calendar.MARCH, 10)
    val uAhead = R.earnUnlock(R.NO_UNLOCK, ahead)
    check("far-ahead earn is for that month", R.isUnlockAvailable(uAhead, ahead))
    check("restored clock not frozen to far future", R.effective(R.monthIndex(oct), R.monthIndex(ahead), R.MONTH_TOLERANCE) == R.monthIndex(oct))

    // ---- search visit prompt
    val t = TimeZone.getDefault()
    val day = ms(2026, Calendar.OCTOBER, 20, 9)
    var d = R.NO_DAY_STATE
    for (i in 1..4) { d = R.recordSearchVisit(d, day + i * 1000L); check("no prompt at visit $i", !R.shouldPromptSearch(d, day + i * 1000L)) }
    check("visits counted 4", d.searchVisits == 4)
    d = R.recordSearchVisit(d, day + 9000L)
    check("prompt on 5th visit", R.shouldPromptSearch(d, day + 9000L))
    d = R.markPrompted(d, day + 9000L)
    check("not again same day after prompt", !R.shouldPromptSearch(d, day + 9000L))
    d = R.recordSearchVisit(d, day + 10000L)
    check("not again on 6th visit", !R.shouldPromptSearch(d, day + 10000L))
    val nextDay = ms(2026, Calendar.OCTOBER, 21, 9)
    check("new day: counter restarts", R.recordSearchVisit(d, nextDay).searchVisits == 1)
    check("new day: no prompt on first visit", !R.shouldPromptSearch(R.recordSearchVisit(d, nextDay), nextDay))
    var d2 = R.NO_DAY_STATE
    for (i in 1..5) d2 = R.recordSearchVisit(d2, nextDay + i * 1000L)
    check("new day: prompt again on 5th", R.shouldPromptSearch(d2, nextDay + 5000L))
    // already ad-free: no prompt
    var d3 = R.NO_DAY_STATE
    for (i in 1..5) d3 = R.recordSearchVisit(d3, day + i * 1000L)
    d3 = R.grantAdFreeDay(d3, day + 6000L)
    check("ad-free: no prompt", !R.shouldPromptSearch(d3, day + 6000L))
    check("ad-free today", R.isAdFreeToday(d3, day + 6000L))
    check("ad-free ends next day", !R.isAdFreeToday(d3, nextDay))
    check("not ad-free by default", !R.isAdFreeToday(R.NO_DAY_STATE, day))
    check("midnight boundary", R.isAdFreeToday(d3, ms(2026, Calendar.OCTOBER, 20, 23)) && !R.isAdFreeToday(d3, ms(2026, Calendar.OCTOBER, 21, 0)))
    // clock back a day must not hand out another ad-free day or reset the counter
    val backDay = ms(2026, Calendar.OCTOBER, 19, 9)
    check("clock back 1 day keeps ad-free", R.isAdFreeToday(d3, backDay))
    check("clock back 1 day keeps counter running", R.recordSearchVisit(d, backDay).searchVisits == d.searchVisits + 1)
    check("clock back 1 day: no new prompt", !R.shouldPromptSearch(R.recordSearchVisit(d, backDay), backDay))
    // threshold constant
    check("threshold is 4", R.SEARCH_VISIT_THRESHOLD == 4)
    checks.assertAll()
    }
}
