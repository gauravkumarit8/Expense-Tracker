package com.expensetracker.ads

import android.app.Activity
import com.expensetracker.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Wraps Google's User Messaging Platform (UMP) SDK. Required since January
 * 16, 2024 by Google's EU User Consent Policy for any app serving Google
 * ads to EEA, UK, or regulated-US-state users — this isn't an AdMob-tier
 * feature or something optional, it applies whenever real (non-test) ads
 * might be shown to those users. See REQUIREMENTS.md ยง2.23.
 *
 * Usage (from MainActivity.onCreate, before rendering any ad):
 * ```
 * ConsentManager.gatherConsent(this) { canRequestAds ->
 *     // update Compose state so BannerAdView only ever renders once true
 * }
 * ```
 *
 * `requestConsentInfoUpdate` needs an `Activity`, not just an Application
 * `Context` — this is why Mobile Ads SDK initialization moved here from
 * `ExpenseTrackerApp.onCreate` (where it previously ran unconditionally)
 * to `MainActivity.onCreate`. `MobileAds.initialize()` is now only ever
 * called from inside this class, gated on consent being resolved.
 */
object ConsentManager {

    private var consentInformation: ConsentInformation? = null
    private var mobileAdsInitialized = false

    /**
     * Requests the latest consent status, shows a consent form if one is
     * required, and initializes the Mobile Ads SDK once (and only once)
     * consent has been resolved — either obtained, or determined not to be
     * required for this user's region. Safe to call on every app launch;
     * the UMP SDK itself decides whether a form actually needs to show.
     *
     * [onCanRequestAdsChanged] is always invoked exactly once per call,
     * even on error — a UMP network failure falls back to whatever consent
     * status the SDK already has cached from a previous session rather
     * than blocking the app or ad slots indefinitely.
     */
    fun gatherConsent(activity: Activity, onCanRequestAdsChanged: (Boolean) -> Unit) {
        val paramsBuilder = ConsentRequestParameters.Builder()

        // Debug-only test configuration — NEVER active in a release build.
        // To actually exercise the EEA consent form during development:
        // 1. Run once, then grep logcat for "UMP SDK" — it logs this
        //    device's hashed ID the first time requestConsentInfoUpdate()
        //    runs on an unregistered device.
        // 2. Paste that hash into addTestDeviceHashedId() below.
        // 3. Uncomment setDebugGeography(...) to force the EEA flow
        //    regardless of this device's real location.
        if (BuildConfig.DEBUG) {
            val debugSettings = ConsentDebugSettings.Builder(activity)
                // .addTestDeviceHashedId("PASTE_YOUR_TEST_DEVICE_HASH_HERE")
                // .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                .build()
            paramsBuilder.setConsentDebugSettings(debugSettings)
        }

        val info = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = info

        info.requestConsentInfoUpdate(
            activity,
            paramsBuilder.build(),
            {
                // Consent info updated successfully — load/show a form only
                // if one is actually required for this user.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // Called once the consent gathering process is complete
                    // (form dismissed, or none was required). formError, if
                    // any, is intentionally not treated as fatal here.
                    resolve(activity, onCanRequestAdsChanged)
                }
            },
            {
                // Network error, etc. updating consent info. Fall back to
                // whatever canRequestAds() reports from a prior session
                // rather than leaving the app in an unresolved state.
                resolve(activity, onCanRequestAdsChanged)
            }
        )
    }

    private fun resolve(activity: Activity, onCanRequestAdsChanged: (Boolean) -> Unit) {
        val canRequest = consentInformation?.canRequestAds() == true
        if (canRequest && !mobileAdsInitialized) {
            mobileAdsInitialized = true
            MobileAds.initialize(activity.applicationContext) { }
        }
        onCanRequestAdsChanged(canRequest)
    }

    /**
     * True if this user's region/consent state requires the app to offer
     * an always-available way to change their ad-privacy choice (a GDPR
     * requirement — consent must be revocable, not just gatherable once).
     * Drives whether Settings shows a "Privacy & Ad Consent" row at all.
     */
    fun isPrivacyOptionsRequired(): Boolean =
        consentInformation?.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Re-opens the privacy/consent choice form — wire this to the
     *  Settings "Privacy & Ad Consent" row. */
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDismissed() }
    }
}