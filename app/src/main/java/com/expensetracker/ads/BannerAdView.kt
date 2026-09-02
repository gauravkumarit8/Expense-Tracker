package com.expensetracker.ads

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

/**
 * Google's OFFICIAL TEST banner ad unit ID — always serves a clearly-marked
 * test ad, safe to leave in debug builds. Replace with a real ad unit ID
 * from AdMob console before release — see REQUIREMENTS.md ยง2.18.
 */
private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

/**
 * A standard banner ad. Renders nothing (zero height, no placeholder box)
 * if the ad fails to load — e.g. no network — so the app's core offline
 * functionality is never visually disrupted by an empty/broken ad slot.
 * Should only ever be rendered for non-Pro users; callers are responsible
 * for that gate (kept out of this composable so it stays a dumb, reusable
 * ad view rather than knowing about subscription state itself).
 */
@Composable
fun BannerAdView(adUnitId: String = TEST_BANNER_AD_UNIT_ID, modifier: Modifier = Modifier) {
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
                        // No network, no fill, whatever the reason — just
                        // collapse the slot rather than showing anything broken.
                        loadFailed = true
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}