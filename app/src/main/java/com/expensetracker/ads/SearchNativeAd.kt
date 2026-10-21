package com.autoexpensetracker.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.autoexpensetracker.ui.theme.MutedText
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * One native advanced ad, drawn as a bounded card with a clear "Ad" badge.
 * Used on the Search screen only.
 *
 * Follows AdMob's native-ad rules:
 *  - every asset that is shown (headline, body, icon, advertiser, media,
 *    call to action) is registered on the [NativeAdView], so impressions and
 *    clicks are counted, and a MediaView is always present;
 *  - the "Ad" badge is always visible, and the card is visually separate
 *    from the app's own rows (own surface, own spacing), so it cannot be
 *    mistaken for a transaction;
 *  - the top-right corner is left clear for the AdChoices icon;
 *  - the ad is destroyed when the card leaves the screen.
 *
 * Takes no space until an ad has actually loaded, and shows nothing if the
 * load fails. Loaded once per time the card enters the screen; the caller
 * decides whether it may appear at all (free user, consent resolved, not in
 * an ad-free day).
 */
@Composable
fun SearchNativeAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }

    // Theme colours are read here, in composable scope, and passed into the
    // View-based layout.
    val colors = NativeAdColors(
        headline = MaterialTheme.colorScheme.onSurface.toArgb(),
        secondary = MutedText.toArgb(),
        button = MaterialTheme.colorScheme.primary.toArgb(),
        onButton = MaterialTheme.colorScheme.onPrimary.toArgb()
    )

    DisposableEffect(Unit) {
        var disposed = false
        val loader = AdLoader.Builder(context, AdUnits.nativeSearch)
            .forNativeAd { loaded ->
                // The callback can arrive after the card has already left the
                // screen; an ad nobody will show must still be destroyed.
                if (disposed) {
                    loaded.destroy()
                } else {
                    nativeAd?.destroy()
                    nativeAd = loaded
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w("SearchNativeAd", "Native ad failed to load: code=${error.code}, message=${error.message}")
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                    .build()
            )
            .build()
        loader.loadAd(AdRequest.Builder().build())
        onDispose {
            disposed = true
            nativeAd?.destroy()
            nativeAd = null
        }
    }

    val ad = nativeAd
    if (ad != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx -> NativeAdViews.create(ctx, colors) },
                update = { view -> NativeAdViews.populate(view, ad) }
            )
        }
    }
}

internal class NativeAdColors(val headline: Int, val secondary: Int, val button: Int, val onButton: Int)

/** Builds and fills the native ad layout in code (no XML), so the whole feature is a few Kotlin files. */
internal object NativeAdViews {

    private const val BADGE_BACKGROUND = 0xFFFFC107.toInt()
    private const val BADGE_TEXT = 0xFF000000.toInt()

    fun create(ctx: Context, c: NativeAdColors): NativeAdView {
        fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val headline = TextView(ctx).apply {
            setTextColor(c.headline)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TextView(ctx).apply {
            text = "Ad"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BADGE_TEXT)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply {
                setColor(BADGE_BACKGROUND)
                cornerRadius = dp(4).toFloat()
            }
        }
        val advertiser = TextView(ctx).apply {
            setTextColor(c.secondary)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6) }
        }
        val subRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge)
            addView(advertiser)
        }
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
            addView(headline)
            addView(subRow)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Keep the headline clear of the AdChoices icon the SDK draws top right.
            setPadding(0, 0, dp(24), 0)
            addView(icon)
            addView(titles)
        }
        val body = TextView(ctx).apply {
            setTextColor(c.secondary)
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        val media = MediaView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(10) }
            setImageScaleType(ImageView.ScaleType.CENTER_CROP)
        }
        val cta = Button(ctx).apply {
            setAllCaps(false)
            textSize = 14f
            setTextColor(c.onButton)
            background = GradientDrawable().apply {
                setColor(c.button)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) }
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(header)
            addView(body)
            addView(media)
            addView(cta)
        }

        return NativeAdView(ctx).apply {
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            headlineView = headline
            bodyView = body
            callToActionView = cta
            iconView = icon
            advertiserView = advertiser
            mediaView = media
        }
    }

    fun populate(view: NativeAdView, ad: NativeAd) {
        (view.headlineView as TextView).text = ad.headline

        val body = view.bodyView as TextView
        body.text = ad.body
        body.visibility = if (ad.body.isNullOrEmpty()) View.GONE else View.VISIBLE

        val cta = view.callToActionView as Button
        cta.text = ad.callToAction
        cta.visibility = if (ad.callToAction.isNullOrEmpty()) View.GONE else View.VISIBLE

        val icon = view.iconView as ImageView
        val drawable = ad.icon?.drawable
        if (drawable == null) {
            icon.visibility = View.GONE
        } else {
            icon.setImageDrawable(drawable)
            icon.visibility = View.VISIBLE
        }

        val advertiser = view.advertiserView as TextView
        advertiser.text = ad.advertiser
        advertiser.visibility = if (ad.advertiser.isNullOrEmpty()) View.GONE else View.VISIBLE

        val media = view.mediaView
        if (media != null) {
            media.setMediaContent(ad.mediaContent)
            media.visibility = if (ad.mediaContent != null) View.VISIBLE else View.GONE
        }

        // Registers the ad with the view; must come after the assets are set.
        view.setNativeAd(ad)
    }
}
