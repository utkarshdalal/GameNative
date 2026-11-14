package app.gamenative.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.gamenative.theme.ThemeBar
import app.gamenative.theme.ThemeContrast
import app.gamenative.theme.ThemeFill
import app.gamenative.theme.ThemeHero
import app.gamenative.theme.ThemeLayout
import app.gamenative.theme.ThemeSpec
import app.gamenative.theme.ThemeTypography
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

    /** Theme component colors; null keeps what each component draws by default. */
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
    /** Bar, focus and switch looks of a theme; null without one. */
    val styles: ThemedStyles? = null,
)

val BrandGradient = listOf(PluviaCyan, PluviaPurple, PluviaPink)

/** A top bar or button-hint bar as the theme draws it. */
@Immutable
data class ThemedBarStyle(
    /** True when the theme styles this bar itself; false keeps the app's own bar. */
    val themed: Boolean,
    val color: Color,
    /** Gradient layers over [color]; paint with [themeFill]. */
    val fill: ThemeFill?,
    /** Line on the edge facing the content (top edge of the footer, bottom edge of the top bar), or null. */
    val border: BorderStroke?,
    val glowColor: Color?,
    val glow: Dp,
    /** Blur of what is behind the bar (API 31+). */
    val blur: Dp,
    val contentColor: Color,
    val heightScale: Float,
)

/** A switch drawn by the app with theme brushes; there is no outline in the off state. */
@Immutable
data class ThemedSwitchStyle(
    val trackOn: Brush,
    val trackOff: Color,
    val thumbOn: Color,
    val thumbOff: Color,
)

/** Controller focus as the theme draws it. */
@Immutable
data class ThemedFocusStyle(
    val ringWidth: Dp,
    /** Ring colors; a sweep from the first to the second and back while [sweep] is true. */
    val ringColor: Color,
    val ringColorAlt: Color,
    val sweep: Boolean,
    val glow: Dp?,
    /** Fill of the focused row or tile and the text on it; null keeps the app's focus look. */
    val fill: ThemeFill?,
    val onFill: Color?,
    /** Bar under the focused item, or null. */
    val underline: Brush?,
    val underlineHeight: Dp,
    /** The ring pulses while focused. */
    val blink: Boolean,
)

/** Bar, focus and switch looks, built once per theme and density. */
@Immutable
class ThemedStyles(
    val header: ThemedBarStyle,
    val footer: ThemedBarStyle,
    val focus: ThemedFocusStyle,
    val switch: ThemedSwitchStyle?,
)

/** Outline (or soft shadow, when [blur] is above 0) around title text. */
@Immutable
data class ThemedTextOutline(val width: Dp, val color: Color, val blur: Dp)

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

/** Bars the theme does not color stay at least this opaque, so bar text never sits on bare artwork. */
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

/**
 * Text (or a handle) on [fill], by the shared [ThemeContrast] rule: the first of [candidates] reaching [min] contrast,
 * else black or white. A translucent [fill] is judged over [under].
 */
internal fun onFill(fill: Color, under: Color, candidates: List<Color?>, min: Double = ThemeContrast.TEXT): Color {
    val f = ThemeContrast.over(fill.toArgb(), under.copy(alpha = 1f).toArgb())
    return Color(ThemeContrast.onFill(f, min, candidates.map { it?.toArgb() }))
}

/** Smallest contrast of an off switch track against the rows it sits on, and of each handle against its track. */
private const val MIN_TRACK_EDGE = 1.5f

/** A themed bar at least this opaque is judged as a fill for its text. */
private const val OPAQUE_BAR_ALPHA = 0.5f

/** Handle of an on switch the theme gives no handle color: white like Steam, unless white disappears on [track]. */
private fun onSwitchTrack(track: Color, scheme: ColorScheme): Color =
    onFill(track, scheme.surface, listOf(Color.White, scheme.onPrimary), ThemeContrast.HANDLE)

/** Multiplies the alpha, so a theme color that is already translucent stays proportionally so. */
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

/** True when the theme's page color is light, so the app switches to light defaults and bar icons. */
internal fun ThemeSpec.isLightBackground(): Boolean {
    val page = background ?: backgroundGradient?.colors?.firstOrNull() ?: return false
    return Color(page).copy(alpha = 1f).luminance() > LIGHT_BACKGROUND_LUMINANCE
}

/** Theme accent: the flat primary, or the first stop of the accent gradient where only a color fits. */
private fun ThemeSpec.accentSource(): Int? = primary ?: accentGradient?.colors?.firstOrNull()

internal fun buildColorScheme(spec: ThemeSpec): ColorScheme {
    if (spec.isEmpty) return DarkColorScheme
    val d = if (spec.isLightBackground()) LightColorScheme else DarkColorScheme
    val panel = spec.panelOpacity?.coerceIn(0f, 1f) ?: 1f
    val accentSource = spec.accentSource()
    val background = spec.background.toColor() ?: d.background
    // A theme may set only one color of a pair; keep every pair it touched readable.
    val themedSurfaces = spec.background != null || spec.surface != null || spec.surfaceVariant != null
    // A themed page without panel colors gets panels, menus and dialogs tinted from the page, not the
    // stock gray (dark) or lavender (light) ones.
    val surfaceBase = spec.surface.toColor() ?: if (spec.background != null) background else d.surface
    val variantBase = spec.surfaceVariant.toColor()
        ?: if (spec.surface != null || spec.background != null) surfaceBase else d.surfaceVariant
    // Panels, menus, dialogs and cards derived from the page color lose their edges; lift them slightly.
    // A surface color the theme sets itself is drawn as set.
    val surface = surfaceBase.let {
        if (themedSurfaces && spec.surface == null) readable(it, MIN_SURFACE_EDGE, listOf(background)) else it
    }
    val variant = variantBase.let {
        if (themedSurfaces && spec.surfaceVariant == null && spec.surface == null) readable(it, MIN_CARD_EDGE, listOf(background)) else it
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
    // destructive actions stay red: the stock red when it reads on the theme's surfaces, else the bright one
    val error = if (themedSurfaces || menu != null) {
        val backs = listOfNotNull(surface, variant, menu)
        listOf(d.error, PluviaDanger).firstOrNull { c -> backs.all { contrast(c, it) >= MIN_ACCENT_CONTRAST } }
            ?: readable(PluviaDanger, MIN_ACCENT_CONTRAST, backs)
    } else {
        d.error
    }
    val onPrimary = if (accentSource != null || spec.onPrimary != null) {
        onFill(primary, background, listOf(spec.onPrimary.toColor() ?: d.onPrimary, spec.text.toColor()))
    } else {
        d.onPrimary
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
        // raised surfaces keep the theme's surface color instead of a tint of its accent
        surfaceTint = surface,
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

internal fun buildPluviaColors(spec: ThemeSpec, scheme: ColorScheme, density: Float): PluviaColors {
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
    val themeText = spec.text.toColor()
    val onButton = when {
        button != null -> onFill(button, scheme.background, listOf(spec.onButton.toColor(), scheme.onSurface, themeText))
        spec.onButton != null -> readable(Color(spec.onButton), MIN_TEXT_CONTRAST, pageBacks)
        else -> null
    }
    val menu = spec.menu.toColor()
    val menuBacks = menu?.let { listOf(it) } ?: listOf(scheme.surfaceContainer, scheme.surfaceContainerHigh)
    val onMenu = when {
        menu != null -> onFill(menu.scaleAlpha(spec.menuStyle?.opacity?.coerceIn(0f, 1f) ?: 1f), scheme.background, listOf(spec.onMenu.toColor(), scheme.onSurface, themeText))
        spec.onMenu != null -> readable(Color(spec.onMenu), MIN_TEXT_CONTRAST, menuBacks)
        else -> null
    }
    val colors = d.copy(
        accentCyan = if (themedAccent) accent else d.accentCyan,
        accentPurple = if (themedAccent) accent else d.accentPurple,
        surfacePanel = (if (spec.surface != null || light) scheme.surface else d.surfacePanel).scaleAlpha(panel),
        surfaceElevated = (if (spec.surfaceVariant != null || light) scheme.surfaceVariant else d.surfaceElevated).scaleAlpha(panel),
        borderDefault = spec.outline.toColor() ?: if (light) scheme.outlineVariant else d.borderDefault,
        textMuted = if (spec.textMuted != null || light) scheme.onSurfaceVariant else d.textMuted,
        focus = focus ?: scheme.primary,
        focusAlt = focus ?: scheme.tertiary,
        bar = spec.bar.toColor() ?: scheme.surface,
        brandGradient = if (themedAccent) {
            listOf(accent, lerp(accent, scheme.onSurface, 0.35f), accent)
        } else {
            d.brandGradient
        },
        card = card,
        // tile titles sit on the card color over cover art, so a half-way gray is not enough there
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
        accentBrush = spec.accentGradient?.let { cssGradientBrush(it, density) },
    )
    return colors.copy(styles = buildStyles(spec, colors, scheme, density))
}

private val UnthemedStyles: ThemedStyles by lazy { buildStyles(ThemeSpec.EMPTY, DarkPluviaColors, DarkColorScheme, 1f) }

private fun buildStyles(spec: ThemeSpec, c: PluviaColors, scheme: ColorScheme, density: Float): ThemedStyles {
    fun bar(bar: ThemeBar?, unset: Color = c.bar): ThemedBarStyle {
        val border = bar?.borderDp?.takeIf { it > 0f }?.let { BorderStroke(it.dp, bar?.borderColor.toColor() ?: c.focus) }
        val text = bar?.textColor.toColor() ?: scheme.onSurface
        // a bar the theme colors mostly opaque is a fill like any other; over artwork the theme's text is kept as set
        val barFill = bar?.color.toColor()?.takeIf { it.alpha >= OPAQUE_BAR_ALPHA }
        return ThemedBarStyle(
            themed = bar != null && !bar.isEmpty,
            color = bar?.color.toColor() ?: unset,
            fill = bar?.fill,
            border = border,
            glowColor = bar?.glowColor.toColor()?.takeIf { (bar?.glowDp ?: 0f) > 0f },
            glow = (bar?.glowDp ?: 0f).dp,
            blur = (bar?.blurDp ?: 0f).dp,
            contentColor = barFill?.let { onFill(it, scheme.background, listOf(text, scheme.onSurface)) } ?: text,
            heightScale = bar?.heightScale?.coerceIn(0.25f, 2f) ?: 1f,
        )
    }
    val f = spec.focusStyle
    val ring = f?.ringColor.toColor() ?: c.focus
    val alt = if (f?.ringColor != null) ring else c.focusAlt
    val focus = ThemedFocusStyle(
        ringWidth = (f?.ringDp?.coerceAtLeast(0f) ?: DEFAULT_FOCUS_RING_DP).dp,
        ringColor = ring,
        ringColorAlt = alt,
        sweep = spec.isEmpty || ring != alt,
        glow = spec.focusGlowDp?.takeIf { it > 0f }?.dp,
        fill = f?.fill,
        onFill = f?.fill?.flatColor
            ?.let { onFill(Color(it), scheme.surface, listOf(f?.onFill.toColor(), scheme.onSurface, spec.text.toColor())) }
            ?: f?.onFill.toColor(),
        underline = f?.underline?.let { cssGradientBrush(it, density) },
        underlineHeight = (f?.underlineDp ?: DEFAULT_UNDERLINE_DP).dp,
        blink = f?.blink == true,
    )
    val k = spec.controls
    val switchThemed = spec.toggle != null || spec.accentGradient != null ||
        k?.toggleOn != null || k?.toggleOff != null || k?.thumbOn != null || k?.thumbOff != null
    val switch = if (!switchThemed) {
        null
    } else {
        val trackOn = k?.toggleOn?.let { themeFillBrush(it, density) } ?: c.accentBrush ?: c.toggle?.let { SolidColor(it) }
        val onFlat = c.toggle ?: k?.toggleOn?.flatColor.toColor() ?: scheme.primary
        // an off track the rows hide is lifted just enough to show, and its handle kept apart from it
        val trackOff = readable(
            k?.toggleOff.toColor() ?: scheme.surfaceContainerHighest,
            MIN_TRACK_EDGE,
            listOf(scheme.background, scheme.surface, scheme.surfaceVariant),
        )
        ThemedSwitchStyle(
            trackOn = trackOn ?: SolidColor(scheme.primary),
            trackOff = trackOff,
            thumbOn = k?.thumbOn.toColor() ?: onSwitchTrack(onFlat, scheme),
            thumbOff = readable(k?.thumbOff.toColor() ?: scheme.outline, MIN_TRACK_EDGE, listOf(trackOff)),
        )
    }
    return ThemedStyles(bar(spec.header, if (spec.header != null && spec.bar == null) Color.Transparent else c.bar), bar(spec.footer), focus, switch)
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
    val density = LocalDensity.current.density
    // We can use LightPluviaColors when ready
    val pluviaColors = remember(spec, colorScheme, density) { buildPluviaColors(spec, colorScheme, density) }
    val shapes = remember(spec.cornerRadiusDp) { buildShapes(spec.cornerRadiusDp?.coerceAtLeast(0f)?.dp) }
    val fileTypography = rememberThemeTypography(spec.fontFile)
    val textScale = spec.typography?.scale?.coerceIn(ThemeTypography.MIN_SCALE, ThemeTypography.MAX_SCALE)
    val letterSpacing = spec.typography?.letterSpacingEm?.takeIf { it != 0f }
    val sizedTypography = if (textScale == null && letterSpacing == null) {
        fileTypography
    } else {
        remember(fileTypography, textScale, letterSpacing) { fileTypography.themed(textScale ?: 1f, letterSpacing ?: 0f) }
    }
    val glowColor = spec.textRoles?.glowColor
    val glowDp = spec.textRoles?.glowDp?.takeIf { it > 0f }
    val typography = if (glowColor == null || glowDp == null) {
        sizedTypography
    } else {
        remember(sizedTypography, glowColor, glowDp, density) {
            sizedTypography.withShadow(Shadow(Color(glowColor), Offset.Zero, glowDp * density))
        }
    }

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

    /** Root color of a main screen: transparent when a theme backdrop is drawn behind it. */
    val screenBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = if (hasBackdrop) Color.Transparent else MaterialTheme.colorScheme.background

    /**
     * Backing drawn under bar content (tabs, chips) that can float over artwork: transparent without a theme,
     * otherwise the theme bar color at a legible opacity.
     */
    /** True while at least one theme value is applied. */
    val isThemed: Boolean
        @Composable
        @ReadOnlyComposable
        get() = !LocalThemeSpec.current.isEmpty

    val barBacking: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.let { spec ->
            when {
                spec.isEmpty -> Color.Transparent
                // a bar color the theme sets keeps its own transparency
                spec.bar != null -> LocalPluviaColors.current.bar
                else -> LocalPluviaColors.current.bar.let { it.copy(alpha = it.alpha.coerceAtLeast(MIN_BAR_BACKING_ALPHA)) }
            }
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

    /** True when the theme page color is light. */
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

    /** Accent fill as a brush: the theme accent gradient, or [default] as a flat color. */
    @Composable
    @ReadOnlyComposable
    fun accentBrush(default: Color): Brush = LocalPluviaColors.current.accentBrush ?: SolidColor(default)

    /** The theme accent gradient brush, or null when the theme has none. */
    val accentGradientBrush: Brush?
        @Composable
        @ReadOnlyComposable
        get() = LocalPluviaColors.current.accentBrush

    /** "On" color of switches, sliders and progress bars. */
    @Composable
    @ReadOnlyComposable
    fun toggleColor(default: Color): Color = LocalPluviaColors.current.toggle ?: default

    /**
     * Brush for an "on" switch track, slider fill or progress fill the app draws itself: the theme's switch-on fill
     * (gradients kept), else its accent gradient, else its flat toggle color, else [default].
     */
    @Composable
    @ReadOnlyComposable
    fun toggleBrush(default: Color): Brush = toggleFill ?: SolidColor(default)

    /** Theme fill for "on" toggles and progress the app draws itself, or null without one. */
    val toggleFill: Brush?
        @Composable
        @ReadOnlyComposable
        get() {
            val c = LocalPluviaColors.current
            val on = LocalThemeSpec.current.controls?.toggleOn?.let { themeFillBrush(it, LocalDensity.current.density) }
            return on ?: c.accentBrush ?: c.toggle?.let { SolidColor(it) }
        }

    val progressColor: Color
        @Composable
        get() = LocalPluviaColors.current.toggle ?: ProgressIndicatorDefaults.linearColor

    /** Material switch colors; a component that can draw [switchStyle] should prefer it. */
    @Composable
    fun switchColors(): SwitchColors {
        val k = LocalThemeSpec.current.controls
        val on = LocalPluviaColors.current.toggle ?: k?.toggleOn?.flatColor.toColor()
        if (on == null && k?.thumbOn == null && k?.thumbOff == null && k?.toggleOff == null) return SwitchDefaults.colors()
        val d = SwitchDefaults.colors()
        val track = on ?: d.checkedTrackColor
        val scheme = MaterialTheme.colorScheme
        val thumb = k?.thumbOn.toColor() ?: onSwitchTrack(track, scheme)
        val off = k?.toggleOff.toColor()?.let { readable(it, MIN_TRACK_EDGE, listOf(scheme.background, scheme.surface, scheme.surfaceVariant)) }
        return SwitchDefaults.colors(
            checkedTrackColor = track,
            checkedThumbColor = thumb,
            checkedIconColor = track,
            uncheckedTrackColor = off ?: d.uncheckedTrackColor,
            uncheckedThumbColor = k?.thumbOff.toColor()?.let { readable(it, MIN_TRACK_EDGE, listOf(off ?: d.uncheckedTrackColor)) } ?: d.uncheckedThumbColor,
            uncheckedBorderColor = if (off != null) Color.Transparent else d.uncheckedBorderColor,
        )
    }

    /**
     * A switch the app draws itself with the theme's track brush and handle colors, or null without a theme
     * switch look (then use a Material Switch with [switchColors]).
     */
    val switchStyle: ThemedSwitchStyle?
        @Composable
        @ReadOnlyComposable
        get() = styles.switch

    @Composable
    fun sliderColors(): SliderColors {
        val k = LocalThemeSpec.current.controls
        val toggle = LocalPluviaColors.current.toggle
        if (toggle == null && k?.sliderThumb == null && k?.sliderTrack == null) return SliderDefaults.colors()
        val d = SliderDefaults.colors()
        return SliderDefaults.colors(
            thumbColor = k?.sliderThumb.toColor() ?: toggle ?: d.thumbColor,
            activeTrackColor = toggle ?: d.activeTrackColor,
            inactiveTrackColor = k?.sliderTrack.toColor() ?: d.inactiveTrackColor,
        )
    }

    /** Track of a slider or progress bar outside its filled part. */
    @Composable
    @ReadOnlyComposable
    fun sliderTrackColor(default: Color): Color = LocalThemeSpec.current.controls?.sliderTrack.toColor() ?: default

    /** Handle of a slider the app draws itself. */
    @Composable
    @ReadOnlyComposable
    fun sliderThumbColor(default: Color): Color = LocalThemeSpec.current.controls?.sliderThumb.toColor() ?: default

    /** Fill of a secondary button the app draws itself: the theme's button fill (gradients kept) or [buttonColor]. */
    @Composable
    @ReadOnlyComposable
    fun buttonBrush(default: Color): Brush =
        LocalThemeSpec.current.controls?.buttonFill?.let { themeFillBrush(it, LocalDensity.current.density) } ?: SolidColor(buttonColor(default))

    /** Fill of a focused secondary button, or [default] without a theme one. */
    @Composable
    @ReadOnlyComposable
    fun buttonFocusBrush(default: Brush?): Brush? =
        LocalThemeSpec.current.controls?.buttonFocusFill?.let { themeFillBrush(it, LocalDensity.current.density) } ?: default

    /** Text on a focused secondary button. */
    @Composable
    @ReadOnlyComposable
    fun onButtonFocusColor(default: Color): Color {
        val k = LocalThemeSpec.current.controls ?: return default
        val fill = k.buttonFocusFill?.flatColor ?: return k.onButtonFocus.toColor() ?: default
        return onFill(Color(fill), MaterialTheme.colorScheme.background, listOf(k.onButtonFocus.toColor(), default))
    }

    /** Line under secondary buttons, or null. */
    val buttonUnderline: BorderStroke?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.controls?.let { k ->
            val w = k.buttonUnderlineDp?.takeIf { it > 0f } ?: return@let null
            BorderStroke(w.dp, k.buttonUnderlineColor.toColor() ?: MaterialTheme.colorScheme.outline)
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
        get() = menuOpacity((LocalPluviaColors.current.menu ?: AlertDialogDefaults.containerColor))

    val dialogTitleColor: Color
        @Composable
        get() = LocalPluviaColors.current.onMenu ?: AlertDialogDefaults.titleContentColor

    val dialogTextColor: Color
        @Composable
        get() = LocalPluviaColors.current.onMenuMuted ?: AlertDialogDefaults.textContentColor

    val menuContainerColor: Color
        @Composable
        get() = menuOpacity(LocalPluviaColors.current.menu ?: MenuDefaults.containerColor)

    @Composable
    @ReadOnlyComposable
    private fun menuOpacity(c: Color): Color = LocalThemeSpec.current.menuStyle?.opacity?.let { c.scaleAlpha(it) } ?: c

    /** Shape of menus, dropdowns and dialogs: the theme menu radius, else [default]. */
    @Composable
    @ReadOnlyComposable
    fun menuShape(default: Shape): Shape =
        LocalThemeSpec.current.menuStyle?.cornerRadiusDp?.let { RoundedCornerShape(it.coerceAtLeast(0f).dp) } ?: default

    /** Gradient layers over the menu container color, or null; paint with [themeFill]. */
    val menuFill: ThemeFill?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.menuStyle?.fill

    /** Blur of what is behind a menu (API 31+), or null. */
    val menuBlur: Dp?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.menuStyle?.blurDp?.takeIf { it > 0f }?.dp

    /** Border of menus and dialogs, or null. */
    val menuBorder: BorderStroke?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.menuStyle?.let { m ->
            val w = m.borderDp?.takeIf { it > 0f } ?: return@let null
            BorderStroke(w.dp, m.borderColor.toColor() ?: LocalPluviaColors.current.focus)
        }

    /** Glow around menus in the border (or focus) color, or null. */
    val menuGlow: Dp?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.menuStyle?.glowDp?.takeIf { it > 0f }?.dp

    /** Fill and text of the focused menu item, or [default] without theme ones. */
    @Composable
    @ReadOnlyComposable
    fun menuFocusColor(default: Color): Color = LocalThemeSpec.current.menuStyle?.focusFill.toColor() ?: default

    @Composable
    @ReadOnlyComposable
    fun onMenuFocusColor(default: Color): Color {
        val m = LocalThemeSpec.current.menuStyle
        val fill = m?.focusFill.toColor() ?: return m?.onFocus.toColor() ?: default
        val under = LocalPluviaColors.current.menu ?: MaterialTheme.colorScheme.surfaceContainer
        return onFill(fill, under, listOf(m?.onFocus.toColor(), default))
    }

    /** Side menus and panels: gradient layers over a color, or null to keep [panel]; paint with [themeFill]. */
    val panelFill: ThemeFill?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.panelFill

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

    /** Shear of shelf covers after the focused one in degrees; null = [cardTilt]. */
    val cardTiltAfter: Float?
        @Composable
        @ReadOnlyComposable
        get() = layout.cardTiltAfterDeg?.coerceIn(-15f, 15f)

    /** Turn of shelf covers about their vertical axis in degrees, with CSS rotateY's sign; 0 without a theme. */
    val cardTurn: Float
        @Composable
        @ReadOnlyComposable
        get() = layout.cardTurnDeg?.coerceIn(-45f, 45f) ?: 0f

    /** Turn of shelf covers after the focused one; null = [cardTurn]. */
    val cardTurnAfter: Float?
        @Composable
        @ReadOnlyComposable
        get() = layout.cardTurnAfterDeg?.coerceIn(-45f, 45f)

    /** Distance of the eye from the covers for [cardTurn], as CSS perspective(); null = no foreshortening. */
    val cardTurnPerspective: Dp?
        @Composable
        @ReadOnlyComposable
        get() = layout.cardTurnPerspectiveDp?.takeIf { it > 0f }?.coerceIn(100f, 10_000f)?.dp

    /** The focused shelf cover drawn with no tilt and no turn. */
    val cardFocusedFlat: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.cardFocusedFlat == true

    /** True when shelf covers take their tilt or turn from their place against the focused cover. */
    val cardPosePerSlot: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.let {
            it.cardTiltAfterDeg != null || it.cardTurnDeg != null || it.cardTurnAfterDeg != null || it.cardFocusedFlat == true
        }

    val hideBadges: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.hideBadges == true

    val hideFooterHints: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.hideFooterHints == true

    /** Library tabs show their names without the game counts. */
    val hideTabCounts: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.hideTabCounts == true

    /** Library grid column count, or null for the app's width-based columns. */
    val gridColumns: Int?
        @Composable
        @ReadOnlyComposable
        get() = layout.gridColumns?.takeIf { it > 0 }?.coerceAtMost(12)

    /** Library grid cover width / height, or null for the app's own cover shape. */
    val gridTileAspect: Float?
        @Composable
        @ReadOnlyComposable
        get() = layout.gridTileAspect?.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.25f, 4f)

    /** The shelf layout's first tile is a wide landscape capsule unless the theme turns that off. */
    val shelfWideFirst: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.shelfWideFirst != false

    /** Where the shelf layout's row of covers sits: "top" (the default, as on Steam's home), "center" or "bottom". */
    val shelfRowPosition: String
        @Composable
        @ReadOnlyComposable
        get() = layout.shelfRowPosition?.lowercase()?.takeIf { it == "center" || it == "bottom" } ?: "top"

    /** The shelf layout's game title above the row instead of under the current cover. */
    val shelfTitleAbove: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.shelfTitleAbove == true

    val shelfHideTitle: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.shelfHideTitle == true

    /** The shelf layout's title without the status and play time line under it. */
    val shelfHideSubtitle: Boolean
        @Composable
        @ReadOnlyComposable
        get() = layout.shelfHideSubtitle == true

    /** True when the theme centers game titles. */
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

    /** Small game icon shape: the theme icon radius, else [default] scaled to the theme corner radius. */
    @Composable
    @ReadOnlyComposable
    fun iconShape(default: Dp): Shape {
        val icon = layout.iconCornerRadiusDp?.coerceAtLeast(0f) ?: return shape(default)
        return RoundedCornerShape(icon.dp)
    }

    /** Fully rounded ends for pills and chips; theme corner radii never change it. */
    val pillShape: Shape = RoundedCornerShape(percent = 50)

    private val styles: ThemedStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalPluviaColors.current.styles ?: UnthemedStyles

    /** Top bar. Without a theme: not themed, [PluviaColors.bar], onSurface content. */
    val headerStyle: ThemedBarStyle
        @Composable
        @ReadOnlyComposable
        get() = styles.header

    /** Button-hint bar; when [ThemedBarStyle.themed] it is drawn full width rather than as the floating pill. */
    val footerStyle: ThemedBarStyle
        @Composable
        @ReadOnlyComposable
        get() = styles.footer

    /** Controller focus. Without a theme: a 4dp ring sweeping primary -> tertiary, no fill, no underline. */
    val focusStyle: ThemedFocusStyle
        @Composable
        @ReadOnlyComposable
        get() = styles.focus

    /** Section headings above groups of rows; [default] without a theme. */
    @Composable
    @ReadOnlyComposable
    fun headingColor(default: Color): Color {
        val spec = LocalThemeSpec.current
        if (spec.isEmpty) return default
        return spec.textRoles?.heading.toColor() ?: MaterialTheme.colorScheme.onSurface
    }

    /** The line under a game title; [default] without a theme subtitle color. */
    @Composable
    @ReadOnlyComposable
    fun subtitleColor(default: Color): Color = LocalThemeSpec.current.textRoles?.subtitle.toColor() ?: default

    /** Outline of game titles, or null. */
    val titleOutline: ThemedTextOutline?
        @Composable
        @ReadOnlyComposable
        get() = layout.let { outline(it.titleStrokeDp, it.titleStrokeColor, it.titleStrokeBlurDp) }

    /** Outline of the line under game titles, or null. */
    val subtitleOutline: ThemedTextOutline?
        @Composable
        @ReadOnlyComposable
        get() = layout.let { outline(it.subtitleStrokeDp, it.subtitleStrokeColor, it.subtitleStrokeBlurDp) }

    private fun outline(width: Float?, color: Int?, blur: Float?): ThemedTextOutline? {
        val w = width?.takeIf { it > 0f } ?: return null
        return ThemedTextOutline(w.dp, color.toColor() ?: Color.Black, (blur ?: 0f).coerceAtLeast(0f).dp)
    }

    /** Glow behind body text as a text shadow, or null. */
    val textGlow: Shadow?
        @Composable
        @ReadOnlyComposable
        get() {
            val t = LocalThemeSpec.current.textRoles ?: return null
            val blur = t.glowDp?.takeIf { it > 0f } ?: return null
            val color = t.glowColor.toColor() ?: return null
            return Shadow(color = color, offset = Offset.Zero, blurRadius = blur * LocalDensity.current.density)
        }

    /** Text size, weight, spacing and case of the theme, or null without them. */
    val typography: ThemeTypography?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.typography

    /** [style] for a game title: scaled by the theme title size. */
    @Composable
    @ReadOnlyComposable
    fun titleTextStyle(style: TextStyle): TextStyle {
        val f = LocalThemeSpec.current.typography?.titleScale ?: return style
        return style.scaled(f.coerceIn(ThemeTypography.MIN_SCALE, ThemeTypography.MAX_SCALE))
    }

    /** [style] for a button-hint label in the footer: scaled by the theme footer text size. */
    @Composable
    @ReadOnlyComposable
    fun footerTextStyle(style: TextStyle): TextStyle {
        val f = LocalThemeSpec.current.typography?.footerScale ?: return style
        return style.scaled(f.coerceIn(ThemeTypography.MIN_SCALE, ThemeTypography.MAX_SCALE))
    }

    /** Fill of the selected tab: the theme's own selected-tab color, else null to keep the accent fill. */
    val selectedTabFill: Color?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.controls?.tabFill.toColor()

    /**
     * Text on the selected tab (and the pills drawn like it), by the shared [onFill] rule on the fill actually drawn:
     * the theme's selected-tab color, else the first stop of its accent gradient, else the accent.
     */
    val onSelectedTab: Color
        @Composable
        @ReadOnlyComposable
        get() {
            val spec = LocalThemeSpec.current
            val scheme = MaterialTheme.colorScheme
            if (spec.isEmpty) return scheme.onPrimary
            val k = spec.controls
            val fill = k?.tabFill.toColor() ?: spec.accentGradient?.colors?.firstOrNull().toColor() ?: scheme.primary
            return onFill(fill, scheme.background, listOf(k?.onTab.toColor(), scheme.onPrimary, spec.text.toColor()))
        }

    /** Weight of section headings, or [default] without a theme one. */
    @Composable
    @ReadOnlyComposable
    fun headingWeight(default: FontWeight?): FontWeight? =
        LocalThemeSpec.current.typography?.headingWeight?.let { FontWeight(ThemeTypography.weight(it)) } ?: default

    /** Weight of the selected tab or menu item, or [default] without a theme one. */
    @Composable
    @ReadOnlyComposable
    fun selectedWeight(default: FontWeight?): FontWeight? =
        LocalThemeSpec.current.typography?.selectedWeight?.let { FontWeight(ThemeTypography.weight(it)) } ?: default

    /** [text] in upper case when the theme upper-cases section headings (or all text). */
    @Composable
    @ReadOnlyComposable
    fun headingText(text: String): String {
        val t = LocalThemeSpec.current.typography ?: return text
        return if ((t.headingUppercase ?: t.allUppercase) == true) text.uppercase() else text
    }

    /** [text] in upper case when the theme upper-cases tab labels (or all text). */
    @Composable
    @ReadOnlyComposable
    fun tabText(text: String): String {
        val t = LocalThemeSpec.current.typography ?: return text
        return if ((t.tabUppercase ?: t.allUppercase) == true) text.uppercase() else text
    }

    /** [text] in upper case when the theme upper-cases footer labels (or all text). */
    @Composable
    @ReadOnlyComposable
    fun footerText(text: String): String {
        val t = LocalThemeSpec.current.typography ?: return text
        return if ((t.footerUppercase ?: t.allUppercase) == true) text.uppercase() else text
    }

    /** [text] in upper case when the theme upper-cases all text, as Pip-Boy does game titles and the lines under them. */
    @Composable
    @ReadOnlyComposable
    fun allText(text: String): String = if (LocalThemeSpec.current.typography?.allUppercase == true) text.uppercase() else text

    /** The footer button glyphs are drawn inverted, for a light footer. */
    val footerGlyphInvert: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.layout?.footerGlyphInvert == true

    /** The focused game's artwork look, or null to keep the app's own. */
    val hero: ThemeHero?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.hero

    /** Seconds for one pass of a sliding accent fill, or null when it stays still. */
    val fillSlideSec: Float?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.motion?.fillSlideSec?.takeIf { it > 0f }

    /** Seconds for one pass of the sliding side panel gradient, or null when it stays still. */
    val panelSlideSec: Float?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.motion?.panelSlideSec?.takeIf { it > 0f }

    /** Seconds for one pass of the sliding focus underline, or null when it stays still. */
    val underlineSlideSec: Float?
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeSpec.current.motion?.underlineSlideSec?.takeIf { it > 0f }
}

private const val DEFAULT_FOCUS_RING_DP = 4f
private const val DEFAULT_UNDERLINE_DP = 4f

private val EMPTY_LAYOUT = ThemeLayout()

/**
 * Gives [content] the theme menu colors as its surface and text, for the app's own option menus.
 * Without menu colors in the theme it changes nothing.
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
