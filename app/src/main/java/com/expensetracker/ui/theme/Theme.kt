package com.autoexpensetracker.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.autoexpensetracker.util.ThemeMode

/**
 * 2026-09-08: replaces the previous bare `MaterialTheme { ... }` call in
 * MainActivity.kt, which had zero customization and was silently running
 * on Compose's default baseline purple color scheme — completely
 * unrelated to this app's actual green branding. That mismatch is what
 * caused a handful of icons (the ones that correctly referenced
 * `MaterialTheme.colorScheme.primary` rather than a hardcoded hex value)
 * to render in the wrong color. See REQUIREMENTS.md ยง13 for the full
 * audit this was built from.
 */
private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = BrandGreenContainerLight,
    onPrimaryContainer = BrandGreenDark,
    secondary = SemanticIndigo,
    onSecondary = Color.White,
    error = SemanticRed,
    onError = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1C1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = Color(0xFF444444),
    outline = SemanticGray
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    onPrimary = BrandGreenDark,
    primaryContainer = BrandGreenDark,
    onPrimaryContainer = BrandGreenContainerLight,
    secondary = SemanticIndigo,
    onSecondary = Color.White,
    error = Color(0xFFFFB4AB), // M3's standard dark-theme error tone — the light-theme red (#D32F2F) is too low-contrast against a dark background
    onError = Color(0xFF690005),
    background = BackgroundDark,
    onBackground = Color(0xFFE3E5E3),
    surface = SurfaceDarkTone,
    onSurface = Color(0xFFE3E5E3),
    surfaceVariant = Color(0xFF2A2D2A),
    onSurfaceVariant = Color(0xFFC4C8C3),
    outline = SemanticGray
)

// Same tonal roles as DarkColors — only background/surface differ (pure
// black, not the warmed dark-green tone) — everything else (text
// contrast, brand green accents, error color) stays identical, so
// switching between DARK and AMOLED never changes anything except how
// black the backdrop is.
private val AmoledColors = darkColorScheme(
    primary = BrandGreenLight,
    onPrimary = BrandGreenDark,
    primaryContainer = BrandGreenDark,
    onPrimaryContainer = BrandGreenContainerLight,
    secondary = SemanticIndigo,
    onSecondary = Color.White,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = AmoledBlack,
    onBackground = Color(0xFFE3E5E3),
    surface = AmoledSurface,
    onSurface = Color(0xFFE3E5E3),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFC4C8C3),
    outline = SemanticGray
)

/**
 * [themeMode] defaults to [ThemeMode.SYSTEM], preserving prior behavior
 * exactly (system dark/light was previously the *only* option, not a
 * default with alternatives). [dynamicColor] defaults to false, not
 * true — this app has a deliberate, specific green brand identity (the
 * rupee-glyph icon), and Material You's dynamic theming would override
 * it with colors extracted from the user's wallpaper, which could easily
 * drift away from that identity on a given device. Available as a
 * parameter rather than removed entirely, in case that tradeoff is ever
 * revisited.
 */
@Composable
fun ExpenseTrackerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themeMode == ThemeMode.AMOLED -> AmoledColors
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 2026-09-20: this used to set window.statusBarColor directly — one
    // of the exact APIs Android 15 flags as deprecated for any app
    // targeting API 35+ that also calls enableEdgeToEdge() (as
    // MainActivity.onCreate does). Worse, actively setting a solid status
    // bar color here was fighting against edge-to-edge on every single
    // recomposition, which is almost certainly the real cause of Play
    // Console's "Edge-to-edge may not display for all users" warning —
    // not just the (now also removed) themes.xml attributes. Replaced
    // with enableEdgeToEdge()'s own SystemBarStyle API, re-invoked here
    // so it stays correct when the resolved theme changes — including a
    // manual dark/light/AMOLED override from Settings, not just the
    // system setting, which the plain onCreate-time call alone can't
    // track since it runs once, before Compose has read any preference.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? ComponentActivity
            val scrimColor = android.graphics.Color.TRANSPARENT
            activity?.enableEdgeToEdge(
                statusBarStyle = if (darkTheme) SystemBarStyle.dark(scrimColor) else SystemBarStyle.light(scrimColor, scrimColor),
                navigationBarStyle = if (darkTheme) SystemBarStyle.dark(scrimColor) else SystemBarStyle.light(scrimColor, scrimColor)
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}