package com.autoexpensetracker.util

import com.autoexpensetracker.Checks
import com.autoexpensetracker.review.ReviewPolicy
import org.junit.Test
import com.autoexpensetracker.util.WeeklyDigest as W

class WeeklyDigestTest {
    private val checks = Checks()
    private fun check(n: String, ok: Boolean) = checks.check(n, ok)

    private val day = 86_400_000L
    private val end = 100L * day
    private fun sent(daysAgo: Double, amt: Double, cat: String = "Food & Dining", top: Boolean = true) =
        W.Entry(end - (daysAgo * day).toLong(), amt, true, cat, top)
    private fun recv(daysAgo: Double, amt: Double) = W.Entry(end - (daysAgo * day).toLong(), amt, false, "Other", true)
    private val money: (Double) -> String = { "Rs" + it.toInt() }

    @Test
    fun allChecks() {
        // ---- compute: windows
        var d = W.compute(emptyList(), end)
        check("empty: zero everything", d.count == 0 && d.spent == 0.0 && d.topCategory == null)
        d = W.compute(listOf(sent(1.0, 100.0), sent(6.9, 50.0), recv(2.0, 1000.0)), end)
        check("in window: spent/received/count", d.spent == 150.0 && d.received == 1000.0 && d.count == 3)
        d = W.compute(listOf(sent(7.0, 999.0)), end)
        check("exactly 7 days ago is the start of the window (included)", d.spent == 999.0 && d.count == 1)
        d = W.compute(listOf(sent(7.0001, 999.0)), end)
        check("just before the window counts as previous week", d.count == 0 && d.previousSpent == 999.0)
        d = W.compute(listOf(sent(14.5, 999.0)), end)
        check("older than 14 days ignored", d.count == 0 && d.previousSpent == 0.0)
        d = W.compute(listOf(W.Entry(end, 500.0, true, "Food & Dining", true)), end)
        check("end is exclusive (a transaction at 'now' is not counted)", d.count == 0)
        d = W.compute(listOf(recv(10.0, 5000.0)), end)
        check("previous week counts only spending", d.previousSpent == 0.0)
        // ---- top category
        d = W.compute(listOf(sent(1.0, 100.0, "Food & Dining"), sent(2.0, 300.0, "Shopping"), sent(3.0, 150.0, "Food & Dining")), end)
        check("top category is the biggest total (Food 250 < Shopping 300)", d.topCategory == "Shopping")
        d = W.compute(listOf(sent(1.0, 9000.0, "Transfer", top = false), sent(2.0, 100.0, "Groceries")), end)
        check("Transfer never picked as top category", d.topCategory == "Groceries" && d.spent == 9100.0)
        d = W.compute(listOf(sent(1.0, 100.0, "Travel"), sent(2.0, 100.0, "Health")), end)
        check("tie: alphabetical, deterministic", d.topCategory == "Health")
        d = W.compute(listOf(sent(1.0, 100.0, "Transfer", top = false)), end)
        check("only transfers: spent counted, no top category", d.spent == 100.0 && d.topCategory == null)

        // ---- message
        check("no transactions -> no notification", W.message(W.compute(emptyList(), end), money) == null)
        check("only old transactions -> no notification", W.message(W.compute(listOf(sent(20.0, 10.0)), end), money) == null)
        var m = W.message(W.compute(listOf(recv(1.0, 2500.0)), end), money)
        check("received only: says so, no spending line", m != null && m.title == "Your week: Rs2500 received" && m.text.contains("No spending"))
        m = W.message(W.compute(listOf(sent(1.0, 4200.0, "Food & Dining"), sent(2.0, 800.0, "Travel")), end), money)
        check("title has the total", m != null && m.title == "This week you spent Rs5000")
        check("text names the top category", m != null && m.text.contains("Most on Food & Dining (Rs4200)."))
        check("no previous week -> no comparison", m != null && !m.text.contains("week before"))
        check("always invites the tap", m != null && m.text.endsWith("Tap to see where it went."))
        m = W.message(W.compute(listOf(sent(1.0, 1180.0), sent(9.0, 1000.0)), end), money)
        check("18% more", m != null && m.text.contains("18% more than the week before."))
        m = W.message(W.compute(listOf(sent(1.0, 600.0), sent(9.0, 1000.0)), end), money)
        check("40% less", m != null && m.text.contains("40% less than the week before."))
        m = W.message(W.compute(listOf(sent(1.0, 1050.0), sent(9.0, 1000.0)), end), money)
        check("5% is 'about the same'", m != null && m.text.contains("About the same"))
        m = W.message(W.compute(listOf(sent(1.0, 1100.0), sent(9.0, 1000.0)), end), money)
        check("exactly 10% is reported, not 'same'", m != null && m.text.contains("10% more"))
        m = W.message(W.compute(listOf(sent(1.0, 100.0, "Transfer", top = false)), end), money)
        check("transfers only: no 'Most on' line", m != null && !m.text.contains("Most on"))

        // ---- isDue (never double-send)
        val now = 200L * day
        check("never sent -> due", W.isDue(0L, now))
        check("sent 1 day ago -> not due", !W.isDue(now - day, now))
        check("sent 5 days ago -> not due", !W.isDue(now - 5 * day, now))
        check("sent 6 days ago -> due", W.isDue(now - 6 * day, now))
        check("sent 8 days ago -> due", W.isDue(now - 8 * day, now))
        check("clock set far back after a send -> due again (no freeze)", W.isDue(now + 30 * day, now))
        check("clock back by hours -> still not due", !W.isDue(now + day / 2, now))

        // ---- Home notices
        check("invite: 3 transactions, off, unanswered", HomeNotices.shouldInviteWeekly(3, false, false))
        check("invite: not before 3", !HomeNotices.shouldInviteWeekly(2, false, false))
        check("invite: not when already on", !HomeNotices.shouldInviteWeekly(10, true, false))
        check("invite: never again once answered", !HomeNotices.shouldInviteWeekly(10, false, true))
        check("invite threshold constant is 3", W.MIN_TRANSACTIONS_TO_INVITE == 3)
        check("capture warning: was working, access off", HomeNotices.shouldWarnCaptureOff(true, 5, false))
        check("capture warning: access on", !HomeNotices.shouldWarnCaptureOff(true, 5, true))
        check("capture warning: never captured (new/manual-only user is not nagged)", !HomeNotices.shouldWarnCaptureOff(true, 0, false))
        check("capture warning: not before the DB has loaded", !HomeNotices.shouldWarnCaptureOff(false, 5, false))

        // ---- review prompt now asks after 3 captured transactions
        check("review: 2 captured -> no", !ReviewPolicy.isEligible(2, 0, 0L, now))
        check("review: 3 captured -> yes", ReviewPolicy.isEligible(3, 0, 0L, now))
        check("review: cooldown and cap unchanged", !ReviewPolicy.isEligible(9, 3, 0L, now) && !ReviewPolicy.isEligible(9, 1, now - 10 * day, now))
        checks.assertAll()
    }
}
