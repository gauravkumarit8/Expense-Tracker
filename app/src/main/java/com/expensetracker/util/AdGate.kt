package com.autoexpensetracker.util

/**
 * One rule for when a free user may be shown banner or native ads.
 *
 * Besides the obvious conditions (not Pro, consent resolved, not in an
 * ad-free day) there is one on purpose: NO ads until the app has at least
 * one transaction. A brand-new user has seen nothing of value yet, and the
 * first thing a finance app should do is earn trust, not put an ad above the
 * navigation bar. Play's numbers showed most new installs leaving within days.
 * Rewarded ads are not affected: those are always the user's own choice.
 */
object AdGate {
    fun showAds(isPro: Boolean, consentResolved: Boolean, adFreeToday: Boolean, transactionCount: Int): Boolean =
        !isPro && consentResolved && !adFreeToday && transactionCount > 0
}
