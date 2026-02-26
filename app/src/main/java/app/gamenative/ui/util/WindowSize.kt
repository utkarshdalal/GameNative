package app.gamenative.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width size classes based on Material Design 3 guidelines.
 * https://m3.material.io/foundations/layout/applying-layout/window-size-classes
 */
enum class WindowWidthClass {
    COMPACT,  // < 600dp
    MEDIUM,   // 600-840dp
    EXPANDED, // > 840dp
}

@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        when {
            configuration.screenWidthDp < 600 -> WindowWidthClass.COMPACT
            configuration.screenWidthDp < 840 -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
    }
}

@Composable
fun rememberScreenWidthDp(): Int {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp
}

// TODO: Also consider if a gamepad is actually connected
@Composable
fun shouldShowGamepadUI(): Boolean {
    return rememberWindowWidthClass() != WindowWidthClass.COMPACT
}

@Composable
fun adaptivePanelWidth(preferredWidth: Dp): Dp {
    val screenWidthDp = rememberScreenWidthDp()
    val maxWidth = (screenWidthDp * 0.85f).dp
    return minOf(preferredWidth, maxWidth)
}

object AdaptivePadding {
    @Composable
    fun horizontal(): Dp = when (rememberWindowWidthClass()) {
        WindowWidthClass.COMPACT -> 12.dp
        WindowWidthClass.MEDIUM -> 16.dp
        WindowWidthClass.EXPANDED -> 20.dp
    }

    @Composable
    fun gridSpacing(): Dp = when (rememberWindowWidthClass()) {
        WindowWidthClass.COMPACT -> 8.dp
        WindowWidthClass.MEDIUM -> 10.dp
        WindowWidthClass.EXPANDED -> 12.dp
    }
}

object AdaptiveHeroHeight {
    @Composable
    fun get(): Dp = when (rememberWindowWidthClass()) {
        WindowWidthClass.COMPACT -> 200.dp
        WindowWidthClass.MEDIUM -> 300.dp
        WindowWidthClass.EXPANDED -> 420.dp
    }
}
