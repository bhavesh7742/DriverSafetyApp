package com.driversafety.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * ══════════════════════════════════════════════════════════════════════════
 * Theme.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Contains the complete color palette and Material 3 dark theme for
 * the Driver Safety dashboard. All colors are used by name throughout
 * the Compose UI code (e.g. Teal500, RedAccent, CardDark).
 */

// ── Brand / Accent Colors ───────────────────────────────────────────────
val Teal200       = Color(0xFF03DAC6)   // Light teal for recording indicator
val Teal500       = Color(0xFF00BFA5)   // Primary action color (Start button)
val Teal700       = Color(0xFF00897B)   // Gradient darker end

val Amber400      = Color(0xFFFFCA28)   // "Trip Complete" accent + stars
val Amber600      = Color(0xFFFFB300)   // Deeper amber variant

val RedAccent     = Color(0xFFFF5252)   // Stop button + dangerous rating

// ── Surface / Card Colors ───────────────────────────────────────────────
val DarkSurface   = Color(0xFF121218)   // Surface color
val DarkBg        = Color(0xFF0D0D12)   // Background color
val CardDark      = Color(0xFF1C1C26)   // Status card background
val CardDarkAlt   = Color(0xFF22222E)   // Live rating card background

// ── Text Colors ─────────────────────────────────────────────────────────
val TextPrimary   = Color(0xFFE8E8EC)   // Primary text on dark backgrounds
val TextSecondary = Color(0xFF9E9EB0)   // Secondary / muted text

// ── Semantic Colors ─────────────────────────────────────────────────────
val GreenGood     = Color(0xFF66BB6A)   // Rating 5 — excellent
val OrangeWarn    = Color(0xFFFFA726)   // Rating 2 / heuristic badge

// ── Dark Color Scheme (Material 3) ──────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary         = Teal500,
    onPrimary       = Color.Black,
    secondary       = Amber400,
    onSecondary     = Color.Black,
    tertiary        = RedAccent,
    background      = DarkBg,
    surface         = DarkSurface,
    surfaceVariant  = CardDark,
    onBackground    = TextPrimary,
    onSurface       = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error           = RedAccent,
    outline         = Color(0xFF33334A)
)

/**
 * Top-level composable that applies the dark Material 3 theme.
 * Also tints the status bar to match the background.
 */
@Composable
fun DriverSafetyTheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme

    // Tint the system status bar to blend with our dark background
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography(),     // Default Material 3 typography
        content     = content
    )
}
