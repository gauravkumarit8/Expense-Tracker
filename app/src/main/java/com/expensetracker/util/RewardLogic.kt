package com.autoexpensetracker.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure rules for the rewarded-ad features (no Android, no storage), so they
 * can be compiled and tested on their own. Two independent things:
 *
 *  1. A MONTHLY UNLOCK: a free user who watches one rewarded ad may use one
 *     Pro action (CSV export or import) once in that calendar month.
 *  2. A DAILY AD-FREE PASS: watching one rewarded ad hides the banner for
 *     the rest of the day. Offered (at most once a day) after the user has
 *     opened Search more than [SEARCH_VISIT_THRESHOLD] times that day.
 *
 * There is no server, so everything lives on the phone and cannot be made
 * tamper-proof. What it does guard against is the ordinary accident: a
 * clock set back a day or a month must not hand out a second allowance. It
 * does that by remembering the latest month/day it has seen and refusing to
 * go backwards, but only within [MONTH_TOLERANCE] / [DAY_TOLERANCE]; a clock
 * set far into the future by mistake must not freeze the allowance until
 * that date, so beyond the tolerance the real clock wins.
 */
object RewardLogic {

    const val SEARCH_VISIT_THRESHOLD = 4
    const val MONTH_TOLERANCE = 1
    const val DAY_TOLERANCE = 2

    private const val DAY_MS = 86_400_000L

    /** Months since year 0, so consecutive months differ by 1 across a year boundary. */
    fun monthIndex(nowMillis: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = nowMillis
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
    }

    /** Whole local days since 1970-01-01, using the time zone's offset at that moment. */
    fun dayIndex(nowMillis: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val local = nowMillis + tz.getOffset(nowMillis)
        return Math.floorDiv(local, DAY_MS).toInt()
    }

    internal fun effective(current: Int, maxSeen: Int?, tolerance: Int): Int =
        if (maxSeen != null && maxSeen > current && maxSeen - current <= tolerance) maxSeen else current

    // ---------- 1. monthly unlock ----------

    data class MonthlyUnlock(val earnedMonth: Int?, val usedMonth: Int?, val maxMonthSeen: Int?)

    val NO_UNLOCK = MonthlyUnlock(null, null, null)

    private fun month(s: MonthlyUnlock, now: Long): Int = effective(monthIndex(now), s.maxMonthSeen, MONTH_TOLERANCE)

    /** Earned this month and not yet spent: the action may run without showing an ad. */
    fun isUnlockAvailable(s: MonthlyUnlock, now: Long): Boolean {
        val m = month(s, now)
        return s.earnedMonth == m && s.usedMonth != m
    }

    /** This month's unlock was earned and already spent: no more until next month. */
    fun isUnlockSpent(s: MonthlyUnlock, now: Long): Boolean {
        val m = month(s, now)
        return s.earnedMonth == m && s.usedMonth == m
    }

    /** Called when the rewarded ad has been watched. Earning twice in a month changes nothing. */
    fun earnUnlock(s: MonthlyUnlock, now: Long): MonthlyUnlock {
        val m = month(s, now)
        return if (s.earnedMonth == m) s.copy(maxMonthSeen = m) else s.copy(earnedMonth = m, maxMonthSeen = m)
    }

    /** Called when the unlocked action has actually completed. */
    fun spendUnlock(s: MonthlyUnlock, now: Long): MonthlyUnlock {
        val m = month(s, now)
        return if (s.earnedMonth == m) s.copy(usedMonth = m, maxMonthSeen = m) else s
    }

    // ---------- 2. daily ad-free pass and search-visit prompt ----------

    data class DayState(
        val adFreeDay: Int?,
        val maxDaySeen: Int?,
        val searchDay: Int?,
        val searchVisits: Int,
        val promptedDay: Int?
    )

    val NO_DAY_STATE = DayState(null, null, null, 0, null)

    private fun day(s: DayState, now: Long, tz: TimeZone): Int = effective(dayIndex(now, tz), s.maxDaySeen, DAY_TOLERANCE)

    fun isAdFreeToday(s: DayState, now: Long, tz: TimeZone = TimeZone.getDefault()): Boolean =
        s.adFreeDay != null && s.adFreeDay == day(s, now, tz)

    fun grantAdFreeDay(s: DayState, now: Long, tz: TimeZone = TimeZone.getDefault()): DayState {
        val d = day(s, now, tz)
        return s.copy(adFreeDay = d, maxDaySeen = d)
    }

    /** Counts one opening of the Search screen today. */
    fun recordSearchVisit(s: DayState, now: Long, tz: TimeZone = TimeZone.getDefault()): DayState {
        val d = day(s, now, tz)
        val visits = if (s.searchDay == d) s.searchVisits + 1 else 1
        return s.copy(searchDay = d, searchVisits = visits, maxDaySeen = d)
    }

    /**
     * Whether to offer the ad-free pass now: more than the threshold of
     * visits today, not already offered today, and not already ad-free. At
     * most one offer a day, whatever the answer, so declining is never
     * followed by a nag.
     */
    fun shouldPromptSearch(s: DayState, now: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        val d = day(s, now, tz)
        return s.searchDay == d && s.searchVisits > SEARCH_VISIT_THRESHOLD &&
            s.promptedDay != d && s.adFreeDay != d
    }

    fun markPrompted(s: DayState, now: Long, tz: TimeZone = TimeZone.getDefault()): DayState {
        val d = day(s, now, tz)
        return s.copy(promptedDay = d, maxDaySeen = d)
    }
}
