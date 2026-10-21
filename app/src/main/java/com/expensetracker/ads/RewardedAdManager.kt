package com.autoexpensetracker.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Loads one rewarded ad ahead of time and shows it on request.
 *
 * The reward callback is held until the user closes the ad. AdMob reports
 * "earned" while the ad is still on screen; acting then (opening a file
 * picker, say) would put the new screen behind the ad. Waiting for the
 * dismissal also means a user who closes the ad early gets no reward.
 *
 * Only loads while [setAllowed] is true: the caller passes "UMP consent
 * resolved, user is not Pro, rewarded ads are configured". [isReady] is
 * Compose state, so a dialog's "Watch ad" button enables itself when the
 * ad arrives.
 */
class RewardedAdManager(private val appContext: Context) {

    var isReady by mutableStateOf(false)
        private set

    private var ad: RewardedAd? = null
    private var loading = false
    private var allowed = false

    fun setAllowed(value: Boolean) {
        allowed = value
        if (value) {
            preload()
        } else {
            ad = null
            isReady = false
        }
    }

    /** Starts a load if none is running and none is ready. Safe to call often. */
    fun preload() {
        val unitId = AdUnits.rewarded ?: return
        if (!allowed || loading || ad != null) return
        loading = true
        RewardedAd.load(
            appContext,
            unitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedAd) {
                    loading = false
                    ad = loaded
                    isReady = true
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Rewarded ad failed to load: code=${error.code}, message=${error.message}")
                    loading = false
                    ad = null
                    isReady = false
                }
            }
        )
    }

    /**
     * Shows the loaded ad. Exactly one of the callbacks runs:
     *  - [onReward]: watched to the end and closed;
     *  - [onNoReward]: closed before earning the reward;
     *  - [onFailed]: no ad was ready, or it could not be shown.
     */
    fun show(activity: Activity, onReward: () -> Unit, onNoReward: () -> Unit, onFailed: () -> Unit) {
        val current = ad
        if (current == null) {
            preload()
            onFailed()
            return
        }
        ad = null
        isReady = false
        var earned = false
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (earned) onReward() else onNoReward()
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: code=${error.code}, message=${error.message}")
                onFailed()
                preload()
            }
        }
        current.show(activity) { earned = true }
    }

    companion object {
        private const val TAG = "RewardedAdManager"

        /** False when no rewarded unit is configured (release build before the ID is pasted into [AdUnits]). */
        val isConfigured: Boolean get() = AdUnits.rewarded != null
    }
}
