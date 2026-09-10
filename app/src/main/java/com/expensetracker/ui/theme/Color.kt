package com.autoexpensetracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Named colors matching this app's actual, already-established visual
 * language — the launcher icon's green (#2E7D32 background, #1B5E20
 * glyph), and the semantic colors (success/danger/warning/etc.) that were
 * already in wide, consistent use as hardcoded hex values throughout
 * MainActivity.kt before this file existed (70+ occurrences audited
 * 2026-09-08 — see REQUIREMENTS.md ยง13). This file doesn't invent a new
 * palette; it names the one that was already there, so it can be reused
 * through the theme instead of repeated as magic hex literals.
 *
 * Not every hardcoded color in MainActivity.kt has been migrated to
 * reference these yet — that's a larger, separate follow-up (see ยง13).
 * This file's immediate job is supplying the Material3 ColorScheme so
 * `MaterialTheme.colorScheme.primary` resolves to the real brand green
 * instead of Compose's default baseline purple.
 */

// Brand green — from the actual launcher icon (ic_launcher_background /
// ic_launcher_foreground), not a new choice.
val BrandGreen = Color(0xFF2E7D32)
val BrandGreenDark = Color(0xFF1B5E20)
val BrandGreenContainerLight = Color(0xFFC8E6C9)
val BrandGreenLight = Color(0xFF66BB6A) // lighter tone for dark-theme primary

// Semantic colors, matching the most-used existing hardcoded values
// (frequency per the 2026-09-08 audit) rather than picking new ones.
val SemanticRed = Color(0xFFD32F2F)       // used 14x already (sent/danger/error)
val SemanticOrange = Color(0xFFE65100)    // used 9x already (warning/needs-review)
val SemanticGray = Color(0xFFBDBDBD)      // used 9x already (disabled/muted)
val SemanticIndigo = Color(0xFF5C6BC0)    // used 5x already (info accents)
val SemanticBrown = Color(0xFF5D4037)     // used 4x already (category accents)
val SemanticGold = Color(0xFFF9A825)      // Pro/premium accent

val SurfaceLight = Color(0xFFF7F7F9)      // the most common card background already in use
val SurfaceDarkTone = Color(0xFF1E211E)   // dark-theme surface, warmed slightly toward green rather than pure neutral gray, to stay on-brand
val BackgroundDark = Color(0xFF141614)