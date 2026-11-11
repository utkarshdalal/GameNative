package app.gamenative.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.gamenative.theme.ThemeSpec
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.materialkolor.PaletteStyle

/**
 * Custom color system for Pluvia, extending Material3.
 * Provides app-specific colors beyond the Material ColorScheme.
 */
@Immutable
data class PluviaColors(
    // Status colors
    val statusInstalled: Color,
    val statusDownloading: Color,
    val statusAvailable: Color,
    val statusAway: Color,
    val statusOffline: Color,

    // Friend status
    val friendOnline: Color,
    val friendOffline: Color,
    val friendInGame: Color,
    val friendAwayOrSnooze: Color,
    val friendInGameAwayOrSnooze: Color,
    val friendBlocked: Color,

    // Accents
    val accentCyan: Color,
    val accentPurple: Color,
    val accentPink: Color,
    val accentSuccess: Color,
    val accentWarning: Color,
    val accentDanger: Color,

    // Surfaces
    val surfacePanel: Color,
    val surfaceElevated: Color,

    // Utility
    val borderDefault: Color,
    val textMuted: Color,

    // Compatibility
    val compatibilityGood: Color,
    val compatibilityGoodBackground: Color,
    val compatibilityPartial: Color,
    val compatibilityPartialBackground: Color,
    val compatibilityUnknown: Color,
    val compatibilityUnknownBackground: Color,
    val compatibilityBad: Color,
    val compatibilityBadBackground: Color,

    /** Controller focus ring; the ring sweeps from [focus] to [focusAlt]. */
    val focus: Color = PluviaPrimary,
    val focusAlt: Color = PluviaCyan,
    /** Top bars, tab bars and the footer hint bar. */
    val bar: Color = PluviaCard,
    val brandGradient: List<Color> = BrandGradient,
)

val BrandGradient = listOf(PluviaCyan, PluviaPurple, PluviaPink)

/**
 * Dark theme color palette.
 */
private val DarkPluviaColors = PluviaColors(
    statusInstalled = StatusInstalled,
    statusDownloading = StatusDownloading,
    statusAvailable = StatusAvailable,
    statusAway = StatusAway,
    statusOffline = StatusOffline,

    friendOnline = FriendOnline,
    friendOffline = FriendOffline,
    friendInGame = FriendInGame,
    friendAwayOrSnooze = FriendAwayOrSnooze,
    friendInGameAwayOrSnooze = FriendInGameAwayOrSnooze,
    friendBlocked = FriendBlocked,

    accentCyan = PluviaCyan,
    accentPurple = PluviaPurple,
    accentPink = PluviaPink,
    accentSuccess = PluviaSuccess,
    accentWarning = PluviaWarning,
    accentDanger = PluviaDanger,

    surfacePanel = PluviaSurface,
    surfaceElevated = PluviaSurfaceElevated,

    borderDefault = PluviaBorder,
    textMuted = PluviaForegroundMuted,

    compatibilityGood = CompatibilityGood,
    compatibilityGoodBackground = CompatibilityGoodBg,
    compatibilityPartial = CompatibilityPartial,
    compatibilityPartialBackground = CompatibilityPartialBg,
    compatibilityUnknown = CompatibilityUnknown,
    compatibilityUnknownBackground = CompatibilityUnknownBg,
    compatibilityBad = CompatibilityBad,
    compatibilityBadBackground = CompatibilityBadBg,

    focus = PluviaPrimary,
    focusAlt = PluviaCyan,
    bar = PluviaCard,
    brandGradient = BrandGradient,
)

// Light theme placeholder - customize when adding light theme support
// private val LightPluviaColors = PluviaColors(...)

private val LocalPluviaColors = staticCompositionLocalOf { DarkPluviaColors }
private val LocalThemeSpec = staticCompositionLocalOf { ThemeSpec.EMPTY }

/**
 * Material3 dark color scheme using Pluvia colors.
 */
private val DarkColorScheme = darkColorScheme(
    primary = PluviaPrimary,
    onPrimary = PluviaForeground,
    primaryContainer = PluviaPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = PluviaForeground,

    secondary = PluviaSecondary,
    onSecondary = PluviaForeground,
    secondaryContainer = PluviaSecondary.copy(alpha = 0.8f),
    onSecondaryContainer = PluviaForeground,

    tertiary = PluviaCyan,
    onTertiary = PluviaForeground,
    tertiaryContainer = PluviaCyan.copy(alpha = 0.2f),
    onTertiaryContainer = PluviaForeground,

    background = PluviaBackground,
    onBackground = PluviaForeground,

    surface = PluviaCard,
    onSurface = PluviaForeground,
    surfaceVariant = PluviaSecondary,
    onSurfaceVariant = PluviaForegroundMuted,
    surfaceTint = PluviaPrimary,

    inverseSurface = PluviaForeground,
    inverseOnSurface = PluviaBackground,
    inversePrimary = PluviaPrimary,

    error = PluviaDestructive,
    onError = PluviaForeground,
    errorContainer = PluviaDestructive.copy(alpha = 0.2f),
    onErrorContainer = PluviaForeground,

    outline = PluviaForegroundMuted,
    outlineVariant = PluviaSecondary,

    scrim = Color.Black.copy(alpha = 0.5f),
    surfaceBright = PluviaSecondary,
    surfaceDim = PluviaBackground,
    surfaceContainer = PluviaCard,
    surfaceContainerHigh = PluviaSecondary,
    surfaceContainerHighest = PluviaSecondary.copy(alpha = 0.9f),
    surfaceContainerLow = PluviaBackground,
    surfaceContainerLowest = PluviaBackground,
)

/** Reference radius that [ThemeSpec.cornerRadiusDp] replaces; inline radii scale by the same ratio. */
private val REFERENCE_CORNER = 12.dp

private fun Int?.toColor(): Color? = this?.let { Color(it) }

/** Multiplies the alpha, so a theme colour that is already translucent stays proportionally so. */
fun Color.scaleAlpha(factor: Float): Color = copy(alpha = (alpha * factor).coerceIn(0f, 1f))

private fun buildColorScheme(spec: ThemeSpec): ColorScheme {
    if (spec.isEmpty) return DarkColorScheme
    val d = DarkColorScheme
    val panel = spec.panelOpacity?.coerceIn(0f, 1f) ?: 1f
    val primary = spec.primary.toColor() ?: d.primary
    val onPrimary = spec.onPrimary.toColor() ?: d.onPrimary
    val text = spec.text.toColor() ?: d.onSurface
    val muted = spec.textMuted.toColor() ?: d.onSurfaceVariant
    val background = spec.background.toColor() ?: d.background
    val surface = spec.surface.toColor() ?: d.surface
    val variant = spec.surfaceVariant.toColor() ?: d.surfaceVariant
    val tertiary = if (spec.primary != null) primary else d.tertiary
    return d.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary.copy(alpha = 0.2f),
        onPrimaryContainer = text,
        secondary = variant,
        onSecondary = text,
        secondaryContainer = variant.copy(alpha = 0.8f),
        onSecondaryContainer = text,
        tertiary = tertiary,
        onTertiary = if (spec.primary != null) onPrimary else d.onTertiary,
        tertiaryContainer = tertiary.copy(alpha = 0.2f),
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = variant,
        onSurfaceVariant = muted,
        surfaceTint = primary,
        inverseSurface = text,
        inverseOnSurface = background,
        inversePrimary = primary,
        onError = text,
        onErrorContainer = text,
        outline = spec.outline.toColor() ?: muted,
        outlineVariant = variant,
        surfaceBright = variant,
        surfaceDim = background,
        surfaceContainer = surface.scaleAlpha(panel),
        surfaceContainerHigh = variant.scaleAlpha(panel),
        surfaceContainerHighest = variant.copy(alpha = 0.9f).scaleAlpha(panel),
        surfaceContainerLow = background,
        surfaceContainerLowest = background,
    )
}

private fun buildPluviaColors(spec: ThemeSpec, scheme: ColorScheme): PluviaColors {
    if (spec.isEmpty) return DarkPluviaColors
    val d = DarkPluviaColors
    val panel = spec.panelOpacity?.coerceIn(0f, 1f) ?: 1f
    val themedAccent = spec.primary != null
    val accent = scheme.primary
    val focus = spec.focus.toColor()
    return d.copy(
        accentCyan = if (themedAccent) accent else d.accentCyan,
        accentPurple = if (themedAccent) accent else d.accentPurple,
        surfacePanel = (spec.surface.toColor() ?: d.surfacePanel).scaleAlpha(panel),
        surfaceElevated = (spec.surfaceVariant.toColor() ?: d.surfaceElevated).scaleAlpha(panel),
        borderDefault = spec.outline.toColor() ?: d.borderDefault,
        textMuted = spec.textMuted.toColor() ?: d.textMuted,
        focus = focus ?: scheme.primary,
        focusAlt = focus ?: scheme.tertiary,
        bar = spec.bar.toColor() ?: scheme.surface,
        brandGradient = if (themedAccent) {
            listOf(accent, lerp(accent, scheme.onSurface, 0.35f), accent)
        } else {
            d.brandGradient
        },
    )
}

private fun buildShapes(radius: Dp?): Shapes {
    if (radius == null) return Shapes()
    fun r(f: Float) = RoundedCornerShape(radius * f)
    return Shapes(
        extraSmall = r(1f / 3f),
        small = r(2f / 3f),
        medium = r(1f),
        large = r(4f / 3f),
        extraLarge = r(7f / 3f),
    )
}

@Composable
fun PluviaTheme(
    seedColor: Color = PluviaSeed,
    isDark: Boolean = true, // for now, always force dark theme
    isAmoled: Boolean = false,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    spec: ThemeSpec = ThemeSpec.EMPTY,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(spec) { buildColorScheme(spec) }
    // We can use LightPluviaColors when ready
    val pluviaColors = remember(spec, colorScheme) { buildPluviaColors(spec, colorScheme) }
    val shapes = remember(spec.cornerRadiusDp) { buildShapes(spec.cornerRadiusDp?.coerceAtLeast(0f)?.dp) }
    val typography = rememberThemeTypography(spec.fontFile)

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }

    CompositionLocalProvider(
        LocalPluviaColors provides pluviaColors,
        LocalThemeSpec provides spec,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

/**
 * Accessor for Pluvia custom colors.
 * Usage: PluviaTheme.colors.accentCyan
 */
object PluviaTheme {
    val colors: PluviaColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPluviaColors.current

    val spec: ThemeSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current

    /** Theme corner radius, or null for the app default. */
    val cornerRadius: Dp?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.cornerRadiusDp?.coerceAtLeast(0f)?.dp

    /** True when the theme draws an image or gradient behind the main screens. */
    val hasBackdrop: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.let { it.backgroundImage != null || it.backgroundGradient != null }

    /** Root colour of a main screen: transparent when a theme backdrop is drawn behind it. */
    val screenBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = if (hasBackdrop) Color.Transparent else MaterialTheme.colorScheme.background

    /** [color] with the theme panel opacity applied, for side menus and sheets. */
    @Composable
    @ReadOnlyComposable
    fun panel(color: Color): Color {
        val opacity = LocalThemeSpec.current.panelOpacity?.coerceIn(0f, 1f) ?: return color
        return color.scaleAlpha(opacity)
    }

    /** [default] radius scaled to the theme corner radius. */
    @Composable
    @ReadOnlyComposable
    fun radius(default: Dp): Dp {
        val themed = cornerRadius ?: return default
        return default * (themed / REFERENCE_CORNER)
    }

    /** Rounded shape whose [default] radius follows the theme corner radius. */
    @Composable
    @ReadOnlyComposable
    fun shape(default: Dp): Shape = RoundedCornerShape(radius(default))
}

/**
 * Direct access to dark colors for non-Composable contexts.
 * Prefer PluviaTheme.colors when inside a Composable.
 */
object DarkColors {
    val statusInstalled = StatusInstalled
    val statusDownloading = StatusDownloading
    val statusAvailable = StatusAvailable
    val statusAway = StatusAway
    val statusOffline = StatusOffline

    val friendOnline = FriendOnline
    val friendOffline = FriendOffline
    val friendInGame = FriendInGame
    val friendAwayOrSnooze = FriendAwayOrSnooze
    val friendInGameAwayOrSnooze = FriendInGameAwayOrSnooze
    val friendBlocked = FriendBlocked

    val accentCyan = PluviaCyan
    val accentPurple = PluviaPurple
    val accentPink = PluviaPink
    val accentSuccess = PluviaSuccess
    val accentWarning = PluviaWarning
    val accentDanger = PluviaDanger

    val surfacePanel = PluviaSurface
    val surfaceElevated = PluviaSurfaceElevated

    val borderDefault = PluviaBorder
    val textMuted = PluviaForegroundMuted

    val compatibilityGood = CompatibilityGood
    val compatibilityGoodBackground = CompatibilityGoodBg
    val compatibilityPartial = CompatibilityPartial
    val compatibilityPartialBackground = CompatibilityPartialBg
    val compatibilityUnknown = CompatibilityUnknown
    val compatibilityUnknownBackground = CompatibilityUnknownBg
    val compatibilityBad = CompatibilityBad
    val compatibilityBadBackground = CompatibilityBadBg
}

// Settings tile color helpers
@Composable
fun settingsTileColors(): SettingsTileColors = SettingsTileDefaults.colors(
    titleColor = MaterialTheme.colorScheme.onSurface,
    subtitleColor = PluviaTheme.colors.textMuted,
    actionColor = PluviaTheme.colors.accentCyan,
)

@Composable
fun settingsTileColorsAlt(): SettingsTileColors = SettingsTileDefaults.colors(
    titleColor = MaterialTheme.colorScheme.onSurface,
    subtitleColor = PluviaTheme.colors.textMuted,
)

@Composable
fun settingsTileColorsDebug(): SettingsTileColors = SettingsTileDefaults.colors(
    titleColor = MaterialTheme.colorScheme.error,
    subtitleColor = PluviaTheme.colors.textMuted,
    actionColor = PluviaTheme.colors.accentCyan,
)
