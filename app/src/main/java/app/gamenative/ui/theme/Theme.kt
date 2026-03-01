package app.gamenative.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
)

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
)

// Light theme placeholder - customize when adding light theme support
// private val LightPluviaColors = PluviaColors(...)

private val LocalPluviaColors = staticCompositionLocalOf { DarkPluviaColors }

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

@Composable
fun PluviaTheme(
    seedColor: Color = PluviaSeed,
    isDark: Boolean = true, // for now, always force dark theme
    isAmoled: Boolean = false,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    content: @Composable () -> Unit,
) {
    val colorScheme = DarkColorScheme
    val pluviaColors = if (isDark) DarkPluviaColors else DarkPluviaColors // We can use LightPluviaColors when ready

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }

    CompositionLocalProvider(LocalPluviaColors provides pluviaColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PluviaTypography,
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
    titleColor = PluviaForeground,
    subtitleColor = PluviaForegroundMuted,
    actionColor = PluviaCyan,
)

@Composable
fun settingsTileColorsAlt(): SettingsTileColors = SettingsTileDefaults.colors(
    titleColor = PluviaForeground,
    subtitleColor = PluviaForegroundMuted,
)

@Composable
fun settingsTileColorsDebug(): SettingsTileColors = SettingsTileDefaults.colors(
    titleColor = PluviaDestructive,
    subtitleColor = PluviaForegroundMuted,
    actionColor = PluviaCyan,
)
