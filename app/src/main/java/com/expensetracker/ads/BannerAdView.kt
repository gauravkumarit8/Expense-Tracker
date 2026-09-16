package com.autoexpensetracker.ads

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

private const val TAG = "BannerAdView"

/**
 * Real AdMob banner ad unit ID for this app (see AndroidManifest.xml's
 * matching real APPLICATION_ID meta-data, same publisher account
 * "8712457399917631"). NOT a test ID despite the earlier name — kept
 * as a fallback default so any call site that doesn't explicitly pass
 * one still gets a real unit. To exercise Google's always-fill test ad
 * instead (e.g. to confirm the *rendering* works, independent of
 * whether real inventory is currently filling), pass
 * "ca-app-pub-3940256099942544/6300978111" explicitly at the call site.
 */
private const val DEFAULT_BANNER_AD_UNIT_ID = "ca-app-pub-8712457399917631/9782274174"

/**
 * A standard banner ad. Renders nothing (zero height, no placeholder box)
 * if the ad fails to load — e.g. no network — so the app's core offline
 * functionality is never visually disrupted by an empty/broken ad slot.
 * Should only ever be rendered for non-Pro users; callers are responsible
 * for that gate (kept out of this composable so it stays a dumb, reusable
 * ad view rather than knowing about subscription state itself).
 *
 * If real ads aren't showing (no crash, just nothing rendered): check
 * logcat for tag "BannerAdView" — every failed load is logged with
 * AdMob's own error code and message. The most common non-bug reason is
 * ERROR_CODE_NO_FILL (3), which just means no ad was available to serve
 * that request — expected and can persist for hours to a couple of days
 * on a freshly created ad unit or a brand-new AdMob account before
 * inventory/fill history builds up. ERROR_CODE_INVALID_REQUEST (1) or
 * anything mentioning the app/ad unit ID, by contrast, points to an
 * actual configuration mismatch worth double-checking against the AdMob
 * console (app not linked, wrong ad unit copied, etc).
 */
@Composable
fun BannerAdView(adUnitId: String = DEFAULT_BANNER_AD_UNIT_ID, modifier: Modifier = Modifier) {
    var loadFailed by remember { mutableStateOf(false) }

    if (loadFailed) return

    AndroidView(
        modifier = modifier.fillMaxWidth().height(50.dp),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                setAdUnitId(adUnitId)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                adListener = object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "Ad failed to load for unit $adUnitId — code=${error.code}, domain=${error.domain}, message=${error.message}")
                        // No network, no fill, whatever the reason — just
                        // collapse the slot rather than showing anything broken.
                        loadFailed = true
                    }

                    override fun onAdLoaded() {
                        Log.d(TAG, "Ad loaded successfully for unit $adUnitId")
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}