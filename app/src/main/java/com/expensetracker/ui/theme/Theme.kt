package com.autoexpensetracker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

/**
 * [dynamicColor] defaults to false, not true — this app has a deliberate,
 * specific green brand identity (the rupee-glyph icon), and Material You's
 * dynamic theming would override it with colors extracted from the user's
 * wallpaper, which could easily drift away from that identity on a given
 * device. Available as a parameter rather than removed entirely, in case
 * that tradeoff is ever revisited.
 */
@Composable
fun ExpenseTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}