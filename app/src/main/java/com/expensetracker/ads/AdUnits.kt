package com.autoexpensetracker.ads

import com.autoexpensetracker.BuildConfig

/**
 * Every AdMob ad unit ID in one place.
 *
 * Debug builds use Google's always-fill TEST units by default
 * ([USE_TEST_ADS_IN_DEBUG]). Loading and tapping your own LIVE ads on a
 * development phone can get an AdMob account flagged for invalid activity,
 * and it is the likeliest way to lose one. Set the flag to false only for a
 * short check that the REAL banner unit is filling (the logcat tag is
 * BannerAdView), and never tap the ad while you do.
 *
 * Release builds always use the real units. If [REWARDED] were ever blank
 * the rewarded-ad features would switch themselves off in release builds, so
 * nothing is shown and no reward can be claimed without a real ad behind it.
 *
 * All three units belong to the AdMob app declared in AndroidManifest.xml
 * (ca-app-pub-8712457399917631~5914829708).
 */
object AdUnits {

    const val USE_TEST_ADS_IN_DEBUG = true

    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    private const val TEST_NATIVE = "ca-app-pub-3940256099942544/2247696110"

    private const val BANNER = "ca-app-pub-8712457399917631/2481170515"

    private const val REWARDED = "ca-app-pub-8712457399917631/9021300774"

    /** Native advanced unit, used on the Search screen only. */
    private const val NATIVE_SEARCH = "ca-app-pub-8712457399917631/2912644265"

    private val useTest: Boolean get() = BuildConfig.DEBUG && USE_TEST_ADS_IN_DEBUG

    val banner: String get() = if (useTest) TEST_BANNER else BANNER

    /** The rewarded unit to use, or null when none is configured (the feature is then off). */
    val rewarded: String? get() = if (useTest) TEST_REWARDED else if (REWARDED.isEmpty()) null else REWARDED

    val nativeSearch: String get() = if (useTest) TEST_NATIVE else NATIVE_SEARCH
}
