package com.autoexpensetracker.util

/**
 * The weekly summary notification: what to say, and when there is nothing to
 * say. Pure (no Android, no storage) so it is unit-tested on its own.
 *
 * It looks at the last 7 days up to [compute]'s end time and compares spending
 * with the 7 days before. A week with no transactions produces NO message: the
 * app never notifies just to say "nothing happened".
 */
object WeeklyDigest {

    const val WINDOW_DAYS = 7

    /** The Home card offering the weekly summary appears once the user has this many transactions. */
    const val MIN_TRANSACTIONS_TO_INVITE = 3

    /** At least this many whole days between two summaries, so a re-schedule can never double-send. */
    const val MIN_DAYS_BETWEEN = 6

    /** Spending changes smaller than this (percent) read as "about the same". */
    const val SAME_BAND_PERCENT = 10

    private const val DAY_MS = 86_400_000L

    /** [countsForTopCategory] is false for money moving between the user's own accounts (Transfer), which says nothing about habits. */
    data class Entry(
        val timestampMillis: Long,
        val amount: Double,
        val sent: Boolean,
        val categoryLabel: String,
        val countsForTopCategory: Boolean
    )

    data class Digest(
        val spent: Double,
        val received: Double,
        val count: Int,
        val topCategory: String?,
        val topCategorySpent: Double,
        val previousSpent: Double
    )

    data class Message(val title: String, val text: String)

    /** Window is [end - 7 days, end); the comparison window is the 7 days before it. */
    fun compute(entries: List<Entry>, endMillis: Long): Digest {
        val start = endMillis - WINDOW_DAYS * DAY_MS
        val prevStart = start - WINDOW_DAYS * DAY_MS
        var spent = 0.0
        var received = 0.0
        var count = 0
        var previous = 0.0
        val byCategory = HashMap<String, Double>()
        for (e in entries) {
            val inWindow = e.timestampMillis >= start && e.timestampMillis < endMillis
            val inPrevious = e.timestampMillis >= prevStart && e.timestampMillis < start
            if (inWindow) {
                count++
                if (e.sent) {
                    spent += e.amount
                    if (e.countsForTopCategory) {
                        byCategory[e.categoryLabel] = (byCategory[e.categoryLabel] ?: 0.0) + e.amount
                    }
                } else {
                    received += e.amount
                }
            } else if (inPrevious && e.sent) {
                previous += e.amount
            }
        }
        var top: String? = null
        var topAmount = 0.0
        for ((label, amount) in byCategory) {
            val better = amount > topAmount || (amount == topAmount && top != null && label.compareTo(top) < 0)
            if (better) {
                top = label
                topAmount = amount
            }
        }
        return Digest(spent, received, count, top, topAmount, previous)
    }

    /** Null when there is nothing worth a notification. */
    fun message(d: Digest, money: (Double) -> String): Message? {
        if (d.count == 0) return null
        if (d.spent <= 0.0) {
            return Message(
                "Your week: " + money(d.received) + " received",
                "No spending was recorded in the last 7 days."
            )
        }
        val parts = ArrayList<String>()
        if (d.topCategory != null) {
            parts.add("Most on " + d.topCategory + " (" + money(d.topCategorySpent) + ").")
        }
        if (d.previousSpent > 0.0) {
            val pct = Math.round((d.spent - d.previousSpent) / d.previousSpent * 100.0).toInt()
            val abs = if (pct < 0) -pct else pct
            if (abs < SAME_BAND_PERCENT) {
                parts.add("About the same as the week before.")
            } else if (pct > 0) {
                parts.add("$abs% more than the week before.")
            } else {
                parts.add("$abs% less than the week before.")
            }
        }
        parts.add("Tap to see where it went.")
        return Message("This week you spent " + money(d.spent), parts.joinToString(" "))
    }

    /** Whether enough time has passed since the last summary to send another. */
    fun isDue(lastSentMillis: Long, nowMillis: Long): Boolean =
        lastSentMillis <= 0L || nowMillis - lastSentMillis >= MIN_DAYS_BETWEEN * DAY_MS || nowMillis < lastSentMillis - DAY_MS
}

/**
 * Small show/hide rules for the two notices on Home, kept pure so they are tested.
 */
object HomeNotices {

    /** Offer the weekly summary once, after the app has shown it works, unless already on or already answered. */
    fun shouldInviteWeekly(transactionCount: Int, digestEnabled: Boolean, inviteAnswered: Boolean): Boolean =
        !digestEnabled && !inviteAnswered && transactionCount >= WeeklyDigest.MIN_TRANSACTIONS_TO_INVITE

    /**
     * Warn that auto-capture has stopped: notification access is off although
     * the app has captured alerts before, i.e. it WAS working and silently
     * stopped (access revoked, or reset by the phone's battery manager). A user
     * who never turned access on is not nagged here: the first-run card and
     * Settings cover them.
     */
    fun shouldWarnCaptureOff(transactionsLoaded: Boolean, autoCapturedCount: Int, accessGranted: Boolean): Boolean =
        transactionsLoaded && autoCapturedCount > 0 && !accessGranted
}
