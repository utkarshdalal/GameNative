package app.gamenative.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.gamenative.theme.ThemeLayout
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

    /** Theme component colours; null keeps what each component draws by default. */
    val card: Color? = null,
    val onCard: Color? = null,
    val button: Color? = null,
    val onButton: Color? = null,
    val toggle: Color? = null,
    val menu: Color? = null,
    val onMenu: Color? = null,
    val onMenuMuted: Color? = null,
    /** Fill for accent buttons, toggles and progress bars the app draws itself. */
    val accentBrush: Brush? = null,
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

private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_ACCENT_CONTRAST = 3f

/** Smallest contrast that still shows a panel's edge against the page behind it. */
private const val MIN_SURFACE_EDGE = 1.1f
private const val MIN_CARD_EDGE = 1.25f

/** Themed bars stay at least this opaque, so bar text never sits on bare artwork. */
private const val MIN_BAR_ALPHA = 0.6f
private const val MIN_BAR_BACKING_ALPHA = 0.75f

private fun contrast(a: Color, b: Color): Float {
    val la = a.copy(alpha = 1f).luminance()
    val lb = b.copy(alpha = 1f).luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** [fg], moved toward white or black just far enough to reach [min] contrast against every one of [backgrounds]. */
private fun readable(fg: Color, min: Float, backgrounds: List<Color>): Color {
    fun worst(c: Color) = backgrounds.minOf { contrast(c, it) }
    if (worst(fg) >= min) return fg
    val target = if (worst(Color.White) >= worst(Color.Black)) Color.White else Color.Black
    var f = 0.1f
    while (f < 1f) {
        val c = lerp(fg, target, f)
        if (worst(c) >= min) return c.copy(alpha = fg.alpha)
        f += 0.1f
    }
    return target.copy(alpha = fg.alpha)
}

/** Multiplies the alpha, so a theme colour that is already translucent stays proportionally so. */
fun Color.scaleAlpha(factor: Float): Color = copy(alpha = (alpha * factor).coerceIn(0f, 1f))

/** Base for light themes: Material light defaults with the Pluvia accent. */
private val LightColorScheme = lightColorScheme(
    primary = PluviaPrimary,
    onPrimary = PluviaForeground,
    tertiary = PluviaPrimary,
    onTertiary = PluviaForeground,
    scrim = Color.Black.copy(alpha = 0.5f),
)

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f

/** True when the theme's page colour is light, so the app switches to light defaults and bar icons. */
internal fun ThemeSpec.isLightBackground(): Boolean {
    val page = background ?: backgroundGradient?.colors?.firstOrNull() ?: return false
    return Color(page).copy(alpha = 1f).luminance() > LIGHT_BACKGROUND_LUMINANCE
}

/** Theme accent: the flat primary, or the first stop of the accent gradient where only a colour fits. */
private fun ThemeSpec.accentSource(): Int? = primary ?: accentGradient?.colors?.firstOrNull()

internal fun buildColorScheme(spec: ThemeSpec): ColorScheme {
    if (spec.isEmpty) return DarkColorScheme
    val d = if (spec.isLightBackground()) LightColorScheme else DarkColorScheme
    val panel = spec.panelOpacity?.coerceIn(0f, 1f) ?: 1f
    val accentSource = spec.accentSource()
    val background = spec.background.toColor() ?: d.background
    // A theme may set only one colour of a pair; keep every pair it touched readable.
    val themedSurfaces = spec.background != null || spec.surface != null || spec.surfaceVariant != null
    // A themed page without panel colours gets panels, menus and dialogs tinted from the page, not the
    // stock grey (dark) or lavender (light) ones.
    val surfaceBase = spec.surface.toColor() ?: if (spec.background != null) background else d.surface
    val variantBase = spec.surfaceVariant.toColor()
        ?: if (spec.surface != null || spec.background != null) surfaceBase else d.surfaceVariant
    // Panels, menus, dialogs and cards the same colour as the page lose their edges; lift them slightly.
    val surface = surfaceBase.let {
        if (themedSurfaces) readable(it, MIN_SURFACE_EDGE, listOf(background)) else it
    }
    val variant = variantBase.let {
        if (themedSurfaces) readable(it, MIN_CARD_EDGE, listOf(background)) else it
    }
    // Accent text and red actions also sit on menus and dialogs, so keep them readable there too.
    val menu = spec.menu.toColor()?.copy(alpha = 1f)
    val primary = (accentSource.toColor() ?: d.primary).let {
        if (accentSource != null || themedSurfaces || menu != null) {
            readable(it, MIN_ACCENT_CONTRAST, listOfNotNull(background, surface, menu))
        } else {
            it
        }
    }
    val error = if (themedSurfaces || menu != null) {
        readable(d.error, MIN_ACCENT_CONTRAST, listOfNotNull(surface, variant, menu))
    } else {
        d.error
    }
    val onPrimary = (spec.onPrimary.toColor() ?: d.onPrimary).let {
        if (accentSource != null || spec.onPrimary != null) readable(it, MIN_TEXT_CONTRAST, listOf(primary)) else it
    }
    val text = (spec.text.toColor() ?: d.onSurface).let {
        if (spec.text != null || themedSurfaces) readable(it, MIN_TEXT_CONTRAST, listOf(background, surface, variant)) else it
    }
    val muted = (spec.textMuted.toColor() ?: d.onSurfaceVariant).let {
        if (spec.textMuted != null || themedSurfaces) readable(it, MIN_ACCENT_CONTRAST, listOf(background, surface, variant)) else it
    }
    val tertiary = if (accentSource != null) primary else d.tertiary
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
        onTertiary = if (accentSource != null) onPrimary else d.onTertiary,
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
        error = error,
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

internal fun buildPluviaColors(spec: ThemeSpec, scheme: ColorScheme): PluviaColors {
    if (spec.isEmpty) return DarkPluviaColors
    val d = DarkPluviaColors
    val panel = spec.panelOpacity?.coerceIn(0f, 1f) ?: 1f
    val light = spec.isLightBackground()
    val themedAccent = spec.accentSource() != null
    val accent = scheme.primary
    val focus = spec.focus.toColor()?.let { readable(it, MIN_ACCENT_CONTRAST, listOf(scheme.background, scheme.surface)) }
    val pageBacks = listOf(scheme.background, scheme.surface)
    val card = spec.card.toColor()
    val button = spec.button.toColor()
    val onButton = when {
        spec.onButton != null -> readable(Color(spec.onButton), MIN_TEXT_CONTRAST, button?.let { listOf(it) } ?: pageBacks)
        button != null -> readable(scheme.onSurface, MIN_TEXT_CONTRAST, listOf(button))
        else -> null
    }
    val menu = spec.menu.toColor()
    val menuBacks = menu?.let { listOf(it) } ?: listOf(scheme.surfaceContainer, scheme.surfaceContainerHigh)
    val onMenu = when {
        spec.onMenu != null -> readable(Color(spec.onMenu), MIN_TEXT_CONTRAST, menuBacks)
        menu != null -> readable(scheme.onSurface, MIN_TEXT_CONTRAST, menuBacks)
        else -> null
    }
    return d.copy(
        accentCyan = if (themedAccent) accent else d.accentCyan,
        accentPurple = if (themedAccent) accent else d.accentPurple,
        surfacePanel = (if (spec.surface != null || light) scheme.surface else d.surfacePanel).scaleAlpha(panel),
        surfaceElevated = (if (spec.surfaceVariant != null || light) scheme.surfaceVariant else d.surfaceElevated).scaleAlpha(panel),
        borderDefault = spec.outline.toColor() ?: if (light) scheme.outlineVariant else d.borderDefault,
        textMuted = if (spec.textMuted != null || light) scheme.onSurfaceVariant else d.textMuted,
        focus = focus ?: scheme.primary,
        focusAlt = focus ?: scheme.tertiary,
        bar = spec.bar.toColor()?.let { it.copy(alpha = it.alpha.coerceAtLeast(MIN_BAR_ALPHA)) } ?: scheme.surface,
        brandGradient = if (themedAccent) {
            listOf(accent, lerp(accent, scheme.onSurface, 0.35f), accent)
        } else {
            d.brandGradient
        },
        card = card,
        // tile titles sit on the card colour over cover art, so a half-way grey is not enough there
        onCard = card?.let { c ->
            if (contrast(scheme.onSurface, c) >= MIN_TEXT_CONTRAST) scheme.onSurface
            else if (contrast(Color.White, c) >= contrast(Color.Black, c)) Color.White else Color.Black
        },
        button = button,
        onButton = onButton,
        toggle = spec.toggle.toColor()?.let { readable(it, MIN_ACCENT_CONTRAST, pageBacks) },
        menu = menu,
        onMenu = onMenu,
        onMenuMuted = onMenu?.let { lerp(it, menuBacks.first(), 0.2f) },
        accentBrush = spec.accentGradient?.let { cssGradientBrush(it) },
    )
}

/** Null without a theme radius, so Material keeps its own default shapes. */
internal fun buildShapes(radius: Dp?): Shapes? {
    if (radius == null) return null
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

    val lightBars = remember(spec) { !spec.isEmpty && spec.isLightBackground() }
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = lightBars
        insetsController.isAppearanceLightNavigationBars = lightBars
    }

    CompositionLocalProvider(
        LocalPluviaColors provides pluviaColors,
        LocalThemeSpec provides spec,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes ?: MaterialTheme.shapes,
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

    /**
     * Backing drawn under bar content (tabs, chips) that can float over artwork: transparent without a theme,
     * otherwise the theme bar colour at a legible opacity.
     */
    /** True while at least one theme value is applied. */
    val isThemed: Boolean
        @Composable
        @ReadOnlyComposable
        get() = !LocalThemeSpec.current.isEmpty

    val barBacking: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalThemeSpec.current.isEmpty) {
            Color.Transparent
        } else {
            LocalPluviaColors.current.bar.let { it.copy(alpha = it.alpha.coerceAtLeast(MIN_BAR_BACKING_ALPHA)) }
        }

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

    /** True when the theme page colour is light. */
    val isLight: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.let { !it.isEmpty && it.isLightBackground() }

    /** Container of library tiles, list rows and carousel cards (not their artwork). */
    @Composable
    @ReadOnlyComposable
    fun cardColor(default: Color): Color = LocalPluviaColors.current.card ?: default

    /** Text on [cardColor]. */
    @Composable
    @ReadOnlyComposable
    fun onCardColor(default: Color): Color = LocalPluviaColors.current.onCard ?: default

    /** Accent fill as a brush: the theme accent gradient, or [default] as a flat colour. */
    @Composable
    @ReadOnlyComposable
    fun accentBrush(default: Color): Brush = LocalPluviaColors.current.accentBrush ?: SolidColor(default)

    /** The theme accent gradient brush, or null when the theme has none. */
    val accentGradientBrush: Brush?
        @Composable
        @ReadOnlyComposable
        get() = LocalPluviaColors.current.accentBrush

    /** "On" colour of switches, sliders and progress bars. */
    @Composable
    @ReadOnlyComposable
    fun toggleColor(default: Color): Color = LocalPluviaColors.current.toggle ?: default

    /** Brush for an "on" switch track, slider fill or progress fill the app draws itself. */
    @Composable
    @ReadOnlyComposable
    fun toggleBrush(default: Color): Brush {
        val c = LocalPluviaColors.current
        return c.toggle?.let { SolidColor(it) } ?: c.accentBrush ?: SolidColor(default)
    }

    /** Theme fill for "on" toggles and progress the app draws itself, or null without one. */
    val toggleFill: Brush?
        @Composable
        @ReadOnlyComposable
        get() = LocalPluviaColors.current.let { c -> c.toggle?.let { SolidColor(it) } ?: c.accentBrush }

    val progressColor: Color
        @Composable
        get() = LocalPluviaColors.current.toggle ?: ProgressIndicatorDefaults.linearColor

    @Composable
    fun switchColors(): SwitchColors {
        val toggle = LocalPluviaColors.current.toggle ?: return SwitchDefaults.colors()
        val thumb = readable(MaterialTheme.colorScheme.onPrimary, MIN_ACCENT_CONTRAST, listOf(toggle))
        return SwitchDefaults.colors(
            checkedTrackColor = toggle,
            checkedThumbColor = thumb,
            checkedIconColor = toggle,
        )
    }

    @Composable
    fun sliderColors(): SliderColors {
        val toggle = LocalPluviaColors.current.toggle ?: return SliderDefaults.colors()
        return SliderDefaults.colors(thumbColor = toggle, activeTrackColor = toggle)
    }

    /** Secondary outlined buttons. */
    @Composable
    fun outlinedButtonColors(): ButtonColors {
        val c = LocalPluviaColors.current
        if (c.button == null && c.onButton == null) return ButtonDefaults.outlinedButtonColors()
        return ButtonDefaults.outlinedButtonColors(
            containerColor = c.button ?: Color.Transparent,
            contentColor = c.onButton ?: MaterialTheme.colorScheme.primary,
        )
    }

    /** Secondary tonal buttons. */
    @Composable
    fun tonalButtonColors(): ButtonColors {
        val c = LocalPluviaColors.current
        if (c.button == null && c.onButton == null) return ButtonDefaults.filledTonalButtonColors()
        return ButtonDefaults.filledTonalButtonColors(
            containerColor = c.button ?: MaterialTheme.colorScheme.secondaryContainer,
            contentColor = c.onButton ?: MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    /** Fill of a secondary button the app draws itself. */
    @Composable
    @ReadOnlyComposable
    fun buttonColor(default: Color): Color = LocalPluviaColors.current.button ?: default

    @Composable
    @ReadOnlyComposable
    fun onButtonColor(default: Color): Color = LocalPluviaColors.current.onButton ?: default

    /** Container of menus, dialogs and the app's dialog panels. */
    @Composable
    @ReadOnlyComposable
    fun menuColor(default: Color): Color = LocalPluviaColors.current.menu ?: default

    @Composable
    @ReadOnlyComposable
    fun onMenuColor(default: Color): Color = LocalPluviaColors.current.onMenu ?: default

    val dialogContainerColor: Color
        @Composable
        get() = LocalPluviaColors.current.menu ?: AlertDialogDefaults.containerColor

    val dialogTitleColor: Color
        @Composable
        get() = LocalPluviaColors.current.onMenu ?: AlertDialogDefaults.titleContentColor

    val dialogTextColor: Color
        @Composable
        get() = LocalPluviaColors.current.onMenuMuted ?: AlertDialogDefaults.textContentColor

    val menuContainerColor: Color
        @Composable
        get() = LocalPluviaColors.current.menu ?: MenuDefaults.containerColor

    @Composable
    fun menuItemColors(): MenuItemColors {
        val on = LocalPluviaColors.current.onMenu ?: return MenuDefaults.itemColors()
        val off = on.copy(alpha = 0.5f)
        return MenuDefaults.itemColors(
            textColor = on,
            leadingIconColor = on,
            trailingIconColor = on,
            disabledTextColor = off,
            disabledLeadingIconColor = off,
            disabledTrailingIconColor = off,
        )
    }

    /** Outer focus glow radius, or null for none. */
    val focusGlow: Dp?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.focusGlowDp?.takeIf { it > 0f }?.dp

    val layout: ThemeLayout
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.layout ?: EMPTY_LAYOUT

    /** Library tile size multiplier, 1 without a theme. */
    val cardScale: Float
        @Composable
        @ReadOnlyComposable
        get() = layout.cardScale?.coerceIn(0.5f, 1.5f) ?: 1f

    /** Game page hero height multiplier, 1 without a theme. */
    val heroScale: Float
        @Composable
        @ReadOnlyComposable
        get() = layout.heroScale?.coerceIn(0.5f, 2f) ?: 1f

    /** Tilt of carousel and home tiles in degrees, 0 without a theme. */
    val cardTilt: Float
        @Composable
        @ReadOnlyComposable
        get() = layout.cardTiltDeg?.coerceIn(-15f, 15f) ?: 0f

    val hideBadges: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.hideBadges == true

    val hideFooterHints: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.hideFooterHints == true

    /** True when the theme centres game titles. */
    val centerTitles: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.titleAlign.equals("center", ignoreCase = true)

    /** Tile shape: the theme tile radius, else [default] scaled to the theme corner radius. */
    @Composable
    @ReadOnlyComposable
    fun cardShape(default: Dp): Shape {
        val tile = layout.cardCornerRadiusDp?.coerceAtLeast(0f) ?: return shape(default)
        return RoundedCornerShape(tile.dp)
    }
}

private val EMPTY_LAYOUT = ThemeLayout()

/**
 * Gives [content] the theme menu colours as its surface and text, for the app's own option menus.
 * Without menu colours in the theme it changes nothing.
 */
@Composable
fun MenuColorScope(content: @Composable () -> Unit) {
    val c = LocalPluviaColors.current
    if (c.menu == null && c.onMenu == null) {
        content()
    } else {
        val scheme = MaterialTheme.colorScheme
        val menuScheme = remember(scheme, c) {
            scheme.copy(
                surface = c.menu ?: scheme.surface,
                surfaceContainer = c.menu ?: scheme.surfaceContainer,
                onSurface = c.onMenu ?: scheme.onSurface,
                onSurfaceVariant = c.onMenuMuted ?: scheme.onSurfaceVariant,
            )
        }
        MaterialTheme(
            colorScheme = menuScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
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
fun settingsTileColors(): SettingsTileColors = if (!PluviaTheme.isThemed) {
    SettingsTileDefaults.colors(
        titleColor = PluviaForeground,
        subtitleColor = PluviaForegroundMuted,
        actionColor = PluviaCyan,
    )
} else {
    SettingsTileDefaults.colors(
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitleColor = PluviaTheme.colors.textMuted,
        actionColor = PluviaTheme.toggleColor(PluviaTheme.colors.accentCyan),
    )
}

@Composable
fun settingsTileColorsAlt(): SettingsTileColors = if (!PluviaTheme.isThemed) {
    SettingsTileDefaults.colors(
        titleColor = PluviaForeground,
        subtitleColor = PluviaForegroundMuted,
    )
} else {
    SettingsTileDefaults.colors(
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitleColor = PluviaTheme.colors.textMuted,
    )
}

@Composable
fun settingsTileColorsDebug(): SettingsTileColors = if (!PluviaTheme.isThemed) {
    SettingsTileDefaults.colors(
        titleColor = PluviaDestructive,
        subtitleColor = PluviaForegroundMuted,
        actionColor = PluviaCyan,
    )
} else {
    SettingsTileDefaults.colors(
        titleColor = MaterialTheme.colorScheme.error,
        subtitleColor = PluviaTheme.colors.textMuted,
        actionColor = PluviaTheme.colors.accentCyan,
    )
}
