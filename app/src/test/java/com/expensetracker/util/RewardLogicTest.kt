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

    // ---- monthly unlock lifecycle (4 uses a month, an ad before each)
    val L = R.MONTHLY_UNLOCK_LIMIT
    check("limit is 4", L == 4)
    var u = R.NO_UNLOCK
    check("fresh: not available", !R.isUnlockAvailable(u, oct))
    check("fresh: not spent", !R.isUnlockSpent(u, oct))
    check("fresh: 4 left", R.usesLeft(u, oct) == 4)
    u = R.earnUnlock(u, oct)
    check("earned: available", R.isUnlockAvailable(u, oct))
    check("earned: not spent", !R.isUnlockSpent(u, oct))
    check("earned persists later in month", R.isUnlockAvailable(u, ms(2026, Calendar.OCTOBER, 31, 23)))
    check("earn twice before use is the same", R.earnUnlock(u, oct) == u)
    u = R.spendUnlock(u, oct)
    check("after 1 use: needs a new ad", !R.isUnlockAvailable(u, oct))
    check("after 1 use: not spent out", !R.isUnlockSpent(u, oct))
    check("after 1 use: 3 left", R.usesLeft(u, oct) == 3)
    check("double spend is a no-op", R.spendUnlock(u, oct) == u)
    for (i in 2..4) {
        u = R.earnUnlock(u, oct)
        check("use $i: available after ad", R.isUnlockAvailable(u, oct))
        u = R.spendUnlock(u, oct)
        check("use $i: ${L - i} left", R.usesLeft(u, oct) == L - i)
    }
    check("4 uses: spent out", R.isUnlockSpent(u, oct))
    check("4 uses: not available", !R.isUnlockAvailable(u, oct))
    check("spent persists same month", R.isUnlockSpent(u, ms(2026, Calendar.OCTOBER, 31, 23)))
    check("earning with quota used up changes nothing usable", !R.isUnlockAvailable(R.earnUnlock(u, oct), oct))
    check("spend with quota used up is a no-op", R.spendUnlock(R.earnUnlock(u, oct), oct).usesInMonth == 4)
    val nov = ms(2026, Calendar.NOVEMBER, 1, 0)
    check("resets next month: not spent", !R.isUnlockSpent(u, nov))
    check("resets next month: 4 left", R.usesLeft(u, nov) == 4)
    check("resets next month: not available", !R.isUnlockAvailable(u, nov))
    val u3 = R.spendUnlock(R.earnUnlock(u, nov), nov)
    check("can use again next month, counter restarts at 1", R.usesLeft(u3, nov) == 3 && u3.usesInMonth == 1)
    check("spend without earn no-op", R.spendUnlock(R.NO_UNLOCK, oct) == R.NO_UNLOCK)
    check("spend stale month no-op", R.spendUnlock(R.earnUnlock(R.NO_UNLOCK, oct), nov).usedMonth == null)
    check("ad watched but action never ran: stays pending, no use counted", R.usesLeft(R.earnUnlock(R.NO_UNLOCK, oct), oct) == 4)
    check("pending earn from last month is not available", !R.isUnlockAvailable(R.earnUnlock(R.NO_UNLOCK, oct), nov))
    // year rollover
    val dec = ms(2026, Calendar.DECEMBER, 31, 23); val jan = ms(2027, Calendar.JANUARY, 1, 0)
    var ud = R.NO_UNLOCK
    for (i in 1..4) ud = R.spendUnlock(R.earnUnlock(ud, dec), dec)
    check("dec spent out", R.isUnlockSpent(ud, dec))
    check("jan reset", !R.isUnlockSpent(ud, jan) && R.usesLeft(ud, jan) == 4 && !R.isUnlockAvailable(ud, jan))
    // clock set back a month must not hand out more uses
    val back = ms(2026, Calendar.SEPTEMBER, 15)
    var spentOct = R.NO_UNLOCK
    for (i in 1..4) spentOct = R.spendUnlock(R.earnUnlock(spentOct, oct), oct)
    check("clock back 1 month: still spent", R.isUnlockSpent(spentOct, back))
    check("clock back 1 month: cannot re-earn", !R.isUnlockAvailable(R.earnUnlock(spentOct, back), back))
    var partial = R.spendUnlock(R.earnUnlock(R.NO_UNLOCK, oct), oct)
    check("clock back 1 month: count kept (3 left)", R.usesLeft(partial, back) == 3)
    // clock set far back (beyond tolerance): real clock wins (documented limit)
    val farBack = ms(2026, Calendar.JUNE, 15)
    check("clock back 4 months: beyond tolerance", !R.isUnlockSpent(spentOct, farBack))
    // clock jumped far ahead by mistake then restored must not freeze allowance until then
    val ahead = ms(2030, Calendar.MARCH, 10)
    val uAhead = R.earnUnlock(R.NO_UNLOCK, ahead)
    check("far-ahead earn is for that month", R.isUnlockAvailable(uAhead, ahead))
    check("restored clock not frozen to far future", R.effective(R.monthIndex(oct), R.monthIndex(ahead), R.MONTH_TOLERANCE) == R.monthIndex(oct))
    // ---- upgrade from the old one-use-a-month state (no counter stored)
    val mOct = R.monthIndex(oct)
    val legacySpent = R.fromStored(mOct, mOct, mOct, null)
    check("legacy spent: 3 left, not a free unspent unlock", R.usesLeft(legacySpent, oct) == 3 && !R.isUnlockAvailable(legacySpent, oct))
    check("legacy spent: next use needs an ad", R.isUnlockAvailable(R.earnUnlock(legacySpent, oct), oct))
    val legacyPending = R.fromStored(mOct, null, mOct, null)
    check("legacy earned-not-used stays available", R.isUnlockAvailable(legacyPending, oct) && R.usesLeft(legacyPending, oct) == 4)
    check("legacy empty", R.fromStored(null, null, null, null) == R.NO_UNLOCK)
    check("legacy spent last month: fresh this month", R.usesLeft(R.fromStored(mOct - 1, mOct - 1, mOct - 1, null), oct) == 4)
    check("new state round-trips", R.fromStored(null, mOct, mOct, 2) == R.MonthlyUnlock(null, mOct, mOct, 2))

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
