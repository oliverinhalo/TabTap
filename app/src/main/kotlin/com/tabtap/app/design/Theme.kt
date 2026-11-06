package com.tabtap.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TabTap's design system.
 *
 * Dark-first: the palette is built around a near-black field so the fretboard and the
 * notes on it are the brightest things on screen. Colour is used to carry meaning rather
 * than decoration — each musical role (the original line, each harmony voice, chords,
 * scale notes, the selection) has one colour, used identically on the fretboard, the
 * piano roll, the tab and the score, so a glance tells you which part you are looking at
 * in any view. There are no gradients and no accent colour that does not mean something.
 */

// --- Core surfaces -----------------------------------------------------------------

private val SurfaceBase = Color(0xFF0B0D10)      // the window behind everything
private val SurfacePanel = Color(0xFF13171C)     // cards, panels, the transport bar
private val SurfaceRaised = Color(0xFF1B2127)    // controls sitting on a panel
private val SurfaceHighest = Color(0xFF242C34)   // pressed and selected controls
private val Outline = Color(0xFF2C343D)
private val OutlineStrong = Color(0xFF3C4651)

private val TextPrimary = Color(0xFFE9EDF2)
private val TextSecondary = Color(0xFF9AA7B4)
private val TextTertiary = Color(0xFF66737F)

// --- Musical role colours ----------------------------------------------------------

/** The colour assigned to each musical role. Kept distinguishable in both themes. */
object MusicColors {
    /** The original, user-entered line. A warm amber: the thing you played. */
    val original = Color(0xFFFFB454)

    /** Harmony voices, in the order they are generated. */
    val harmony = listOf(
        Color(0xFF4CC2A8),  // teal
        Color(0xFF7D9BFF),  // periwinkle
        Color(0xFFE57BB8),  // rose
        Color(0xFF9BD45E),  // lime
        Color(0xFF52BEE8),  // sky
        Color(0xFFC79BFF),  // violet
    )

    /** Chord tones and chord-diagram dots. */
    val chord = Color(0xFF7D9BFF)

    /** The current selection, in every view. */
    val selection = Color(0xFFFFFFFF)

    /** Notes of the selected scale, shown faintly on the fretboard. */
    val scaleNote = Color(0xFF3E4A57)

    /** The root note of the selected key. */
    val rootNote = Color(0xFFE05C5C)

    /** The playhead and anything else that marks the current position. */
    val playhead = Color(0xFFFF6B5C)

    /** A note that cannot be played on the current fretboard. */
    val unplayable = Color(0xFFE05C5C)

    /** Fretboard wood, frets, inlays and strings. */
    val board = Color(0xFF15191E)
    val boardEdge = Color(0xFF0D1014)
    val fret = Color(0xFF4A5560)
    val fretShadow = Color(0xFF22282E)
    val nut = Color(0xFFBFC8D2)
    val inlay = Color(0xFF2B333B)
    val string = Color(0xFF8A96A4)

    /** Colour for the track in slot [slot]: slot 0 is the original line. */
    fun forSlot(slot: Int): Color =
        if (slot <= 0) original else harmony[(slot - 1) % harmony.size]
}

// --- Spacing and shape -------------------------------------------------------------

/** The spacing scale. Every gap in the app is one of these. */
@Immutable
data class Spacing(
    val hairline: androidx.compose.ui.unit.Dp = 1.dp,
    val tiny: androidx.compose.ui.unit.Dp = 4.dp,
    val small: androidx.compose.ui.unit.Dp = 8.dp,
    val medium: androidx.compose.ui.unit.Dp = 12.dp,
    val large: androidx.compose.ui.unit.Dp = 16.dp,
    val xLarge: androidx.compose.ui.unit.Dp = 24.dp,
    val xxLarge: androidx.compose.ui.unit.Dp = 32.dp,
    /** The minimum size of anything you are expected to hit with a finger. */
    val touchTarget: androidx.compose.ui.unit.Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** Extra colours that Material 3's scheme has no slot for. */
@Immutable
data class TabTapColors(
    val surfaceRaised: Color = SurfaceRaised,
    val surfaceHighest: Color = SurfaceHighest,
    val outlineStrong: Color = OutlineStrong,
    val textTertiary: Color = TextTertiary,
)

val LocalTabTapColors = staticCompositionLocalOf { TabTapColors() }

// --- Typography --------------------------------------------------------------------

/**
 * Type is kept tight and legible: a single sans family at carefully chosen weights, with
 * monospace reserved for anything that must align in columns — fret numbers, tab, tick
 * positions and the tempo readout.
 */
private val TabTapTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.6.sp,
    ),
)

/** Monospace styles, for anything that must line up in columns. */
object MonoType {
    val fretNumber = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = (-0.5).sp, textAlign = TextAlign.Center,
    )
    val tab = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, letterSpacing = 0.sp,
    )
    val readout = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, letterSpacing = 0.sp,
    )
    val bigReadout = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, letterSpacing = (-1).sp,
    )
}

// --- Colour schemes ----------------------------------------------------------------

private val DarkScheme = darkColorScheme(
    primary = MusicColors.harmony[0],
    onPrimary = Color(0xFF07211C),
    primaryContainer = Color(0xFF1C3A34),
    onPrimaryContainer = Color(0xFFBFF0E4),
    secondary = MusicColors.original,
    onSecondary = Color(0xFF2B1A00),
    secondaryContainer = Color(0xFF3D2B0C),
    onSecondaryContainer = Color(0xFFFFE3BC),
    tertiary = MusicColors.harmony[1],
    onTertiary = Color(0xFF0D152E),
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfacePanel,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = SurfaceBase,
    surfaceContainerLow = SurfacePanel,
    surfaceContainer = SurfacePanel,
    surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = SurfaceHighest,
    outline = Outline,
    outlineVariant = Color(0xFF20262D),
    error = Color(0xFFFF6B5C),
    onError = Color(0xFF2E0603),
    errorContainer = Color(0xFF4A1310),
    onErrorContainer = Color(0xFFFFD6D1),
    inverseSurface = TextPrimary,
    inverseOnSurface = SurfaceBase,
)

/**
 * A light scheme is provided so the app is usable in bright sunlight, but the dark one is
 * the designed default and what the app opens with.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF17796A),
    onPrimary = Color.White,
    secondary = Color(0xFF9A5B00),
    onSecondary = Color.White,
    tertiary = Color(0xFF3A52B8),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14181C),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFEDF0F3),
    onSurfaceVariant = Color(0xFF4A535C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F6),
    surfaceContainer = Color(0xFFEDF0F3),
    surfaceContainerHigh = Color(0xFFE5E9ED),
    surfaceContainerHighest = Color(0xFFDDE2E7),
    outline = Color(0xFFC3CAD1),
    outlineVariant = Color(0xFFD9DFE4),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * Wraps content in TabTap's theme.
 *
 * @param darkTheme dark by default; pass the system setting to follow it instead.
 */
@Composable
fun TabTapTheme(
    darkTheme: Boolean = true,
    /**
     * Scales the whole interface by adjusting the density.
     *
     * Scaling the density rather than individual text sizes keeps every proportion and
     * every touch target consistent — a screen at 0.85 is the same layout, smaller, so
     * more of it fits on a short landscape screen.
     */
    uiScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        TabTapColors()
    } else {
        TabTapColors(
            surfaceRaised = Color(0xFFE5E9ED),
            surfaceHighest = Color(0xFFDDE2E7),
            outlineStrong = Color(0xFFAAB3BB),
            textTertiary = Color(0xFF7A838C),
        )
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = if (uiScale == 1f) {
        density
    } else {
        androidx.compose.ui.unit.Density(
            density = density.density * uiScale.coerceIn(0.8f, 1.4f),
            fontScale = density.fontScale,
        )
    }
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalTabTapColors provides colors,
        androidx.compose.ui.platform.LocalDensity provides scaled,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = TabTapTypography,
            content = content,
        )
    }
}

/** Follows the system setting; used by previews and tests. */
@Composable
fun TabTapThemeFollowingSystem(content: @Composable () -> Unit) =
    TabTapTheme(darkTheme = isSystemInDarkTheme(), content = content)
