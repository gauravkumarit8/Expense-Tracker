package com.autoexpensetracker.util

/**
 * "How is this month going?" for the Home screen: where spending is heading by
 * month-end, how that compares with last month, and (only if the user set
 * budgets) how much they can spend per day and stay inside them.
 *
 * Pure (no Android, no storage) so it is unit-tested on its own. Honest about
 * its limits: the projection is a straight line (spent so far / days passed x
 * days in month), so it is not shown in the first days of a month, when one
 * rent payment would make it meaningless.
 */
object MonthPace {

    /** No projection before this day of the month. */
    const val MIN_DAY_FOR_PROJECTION = 5

    /** Changes under this percent read as "about the same as last month". */
    const val SAME_BAND_PERCENT = 10

    /**
     * @param spent everything sent so far this month (the same figure as the Home hero card)
     * @param budgetLimit sum of the monthly limits of the user's budgets (0 if none)
     * @param budgetSpent spent so far in exactly the budgeted categories
     */
    data class Input(
        val spent: Double,
        val dayOfMonth: Int,
        val daysInMonth: Int,
        val lastMonthSpent: Double,
        val budgetLimit: Double,
        val budgetSpent: Double
    )

    data class Pace(
        val projected: Double?,
        val lastMonthSpent: Double,
        val changePercent: Int?,
        val budgetLeft: Double?,
        val perDayAllowance: Double?,
        val daysLeft: Int
    )

    fun compute(i: Input): Pace {
        val day = if (i.dayOfMonth < 1) 1 else i.dayOfMonth
        val total = if (i.daysInMonth < day) day else i.daysInMonth
        val daysLeft = total - day + 1 // today still counts as a day you can spend on

        var projected: Double? = null
        if (i.spent > 0.0 && day >= MIN_DAY_FOR_PROJECTION) {
            projected = i.spent / day * total
        }
        var change: Int? = null
        if (projected != null && i.lastMonthSpent > 0.0) {
            change = Math.round((projected - i.lastMonthSpent) / i.lastMonthSpent * 100.0).toInt()
        }
        var left: Double? = null
        var perDay: Double? = null
        if (i.budgetLimit > 0.0) {
            left = i.budgetLimit - i.budgetSpent
            if (left > 0.0) perDay = left / daysLeft
        }
        return Pace(projected, i.lastMonthSpent, change, left, perDay, daysLeft)
    }

    /** Whether there is anything worth showing: a projection, or a budget line. */
    fun hasContent(p: Pace): Boolean = p.projected != null || p.budgetLeft != null

    /** The card's lines, in order. Empty when there is nothing to say. */
    fun lines(p: Pace, money: (Double) -> String): List<String> {
        val out = ArrayList<String>()
        if (p.projected != null) {
            out.add("On pace to spend about " + money(p.projected) + " this month.")
            val change = p.changePercent
            if (change != null) {
                val abs = if (change < 0) -change else change
                val last = money(p.lastMonthSpent)
                if (abs < SAME_BAND_PERCENT) {
                    out.add("About the same as last month ($last).")
                } else if (change > 0) {
                    out.add("$abs% more than last month ($last).")
                } else {
                    out.add("$abs% less than last month ($last).")
                }
            }
        }
        if (p.budgetLeft != null) {
            if (p.budgetLeft <= 0.0) {
                if (p.budgetLeft < 0.0) {
                    out.add("You are " + money(-p.budgetLeft) + " over your budgets this month.")
                } else {
                    out.add("Your budgets are used up for this month.")
                }
            } else if (p.perDayAllowance != null) {
                val days = if (p.daysLeft == 1) "today" else "each of the next " + p.daysLeft + " days"
                out.add("To stay within your budgets, spend about " + money(p.perDayAllowance) + " " + days + ".")
            }
        }
        return out
    }
}
