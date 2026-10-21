package com.autoexpensetracker.util

import android.content.Context

/**
 * SharedPreferences home for [RewardLogic]'s state. Each call loads the
 * state, applies one pure rule, saves, and returns the new state, so the
 * rules stay in one tested place.
 *
 * Deliberately NOT cleared by "Delete all data": that is the user's
 * financial data, while this is an allowance counter, and clearing it there
 * would be a way to reset the monthly unlock.
 */
object RewardStore {
    private const val PREFS = "reward_state"
    private const val NONE = Int.MIN_VALUE

    private const val K_EARNED = "earned_month"
    private const val K_USED = "used_month"
    private const val K_MAX_MONTH = "max_month_seen"
    private const val K_AD_FREE = "ad_free_day"
    private const val K_MAX_DAY = "max_day_seen"
    private const val K_SEARCH_DAY = "search_day"
    private const val K_SEARCH_VISITS = "search_visits"
    private const val K_PROMPTED = "prompted_day"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.optInt(key: String): Int? {
        val v = getInt(key, NONE)
        return if (v == NONE) null else v
    }

    fun loadUnlock(context: Context): RewardLogic.MonthlyUnlock {
        val p = prefs(context)
        return RewardLogic.MonthlyUnlock(p.optInt(K_EARNED), p.optInt(K_USED), p.optInt(K_MAX_MONTH))
    }

    private fun save(context: Context, u: RewardLogic.MonthlyUnlock): RewardLogic.MonthlyUnlock {
        prefs(context).edit()
            .putInt(K_EARNED, u.earnedMonth ?: NONE)
            .putInt(K_USED, u.usedMonth ?: NONE)
            .putInt(K_MAX_MONTH, u.maxMonthSeen ?: NONE)
            .apply()
        return u
    }

    /** The rewarded ad was watched. */
    fun earnUnlock(context: Context): RewardLogic.MonthlyUnlock =
        save(context, RewardLogic.earnUnlock(loadUnlock(context), System.currentTimeMillis()))

    /** The unlocked action completed. */
    fun spendUnlock(context: Context): RewardLogic.MonthlyUnlock =
        save(context, RewardLogic.spendUnlock(loadUnlock(context), System.currentTimeMillis()))

    fun loadDayState(context: Context): RewardLogic.DayState {
        val p = prefs(context)
        return RewardLogic.DayState(
            adFreeDay = p.optInt(K_AD_FREE),
            maxDaySeen = p.optInt(K_MAX_DAY),
            searchDay = p.optInt(K_SEARCH_DAY),
            searchVisits = p.getInt(K_SEARCH_VISITS, 0),
            promptedDay = p.optInt(K_PROMPTED)
        )
    }

    private fun save(context: Context, s: RewardLogic.DayState): RewardLogic.DayState {
        prefs(context).edit()
            .putInt(K_AD_FREE, s.adFreeDay ?: NONE)
            .putInt(K_MAX_DAY, s.maxDaySeen ?: NONE)
            .putInt(K_SEARCH_DAY, s.searchDay ?: NONE)
            .putInt(K_SEARCH_VISITS, s.searchVisits)
            .putInt(K_PROMPTED, s.promptedDay ?: NONE)
            .apply()
        return s
    }

    fun recordSearchVisit(context: Context): RewardLogic.DayState =
        save(context, RewardLogic.recordSearchVisit(loadDayState(context), System.currentTimeMillis()))

    fun markPrompted(context: Context): RewardLogic.DayState =
        save(context, RewardLogic.markPrompted(loadDayState(context), System.currentTimeMillis()))

    fun grantAdFreeDay(context: Context): RewardLogic.DayState =
        save(context, RewardLogic.grantAdFreeDay(loadDayState(context), System.currentTimeMillis()))
}
