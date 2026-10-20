package com.autoexpensetracker.ads

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

private const val TAG = "BannerAdView"

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
fun BannerAdView(adUnitId: String = AdUnits.banner, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    var failed by remember { mutableStateOf(false) }
    var failures by remember { mutableIntStateOf(0) }

    // One AdView for as long as this composable stays in the tree. Adaptive
    // (anchored) size: fills the screen width and usually earns more than the
    // fixed 320x50 banner.
    val adView = remember(adUnitId, widthDp) {
        AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
            setAdUnitId(adUnitId)
            adListener = object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Ad failed to load for unit $adUnitId — code=${error.code}, domain=${error.domain}, message=${error.message}")
                    // Collapse the slot instead of showing anything broken;
                    // the effect below retries after a pause.
                    failures += 1
                    failed = true
                }

                override fun onAdLoaded() {
                    Log.d(TAG, "Ad loaded successfully for unit $adUnitId")
                    failed = false
                }
            }
        }
    }

    DisposableEffect(adView) { onDispose { adView.destroy() } }
    LaunchedEffect(adView) { adView.loadAd(AdRequest.Builder().build()) }

    // A failed load (no network, no fill, a new unit still propagating) used
    // to leave the slot empty until the user left the screen. Retry a few
    // times, a minute apart, then give up for this session.
    LaunchedEffect(failures) {
        if (failures in 1..5) {
            delay(60_000L)
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    // Zero height (not removed) while failed, so the AdView stays alive for
    // the retry and nothing visible is left behind.
    val slot = if (failed) {
        modifier.fillMaxWidth().height(0.dp)
    } else {
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 4.dp)
    }
    AndroidView(modifier = slot, factory = { adView })
}
