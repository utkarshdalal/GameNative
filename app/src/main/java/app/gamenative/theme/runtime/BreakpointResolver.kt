package app.gamenative.theme.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import app.gamenative.theme.ThemeManager
import app.gamenative.theme.model.Breakpoint
import app.gamenative.theme.model.Visibility

/**
 * Runtime breakpoint resolver for responsive theme layouts.
 * 
 * Resolves variables based on current screen orientation and width,
 * applying matching breakpoints in order (CSS cascade behavior).
 * 
 * Usage:
 * ```kotlin
 * val resolvedVariables = rememberResolvedVariables(
 *     baseVariables = theme.variables,
 *     breakpoints = theme.breakpoints
 * )
 * ```
 */
object BreakpointResolver {

    /**
     * Composable that resolves variables based on current screen configuration.
     * Automatically recomposes when orientation or screen size changes.
     * 
     * @param baseVariables Default variable values from theme
     * @param breakpoints List of breakpoints that may override variables
     * @return Map of resolved variable names to values
     */
    @Composable
    fun rememberResolvedVariables(
        baseVariables: Map<String, String>,
        breakpoints: List<Breakpoint>
    ): Map<String, String> {
        val config = LocalConfiguration.current
        val isPortrait = config.screenHeightDp > config.screenWidthDp
        val screenWidthDp = config.screenWidthDp

        return remember(isPortrait, screenWidthDp, baseVariables, breakpoints) {
            VariableResolver.resolveWithBreakpoints(baseVariables, breakpoints, isPortrait, screenWidthDp)
        }
    }

    /**
     * Composable that returns whether the current screen is in portrait mode.
     */
    @Composable
    fun rememberIsPortrait(): Boolean {
        val config = LocalConfiguration.current
        return remember(config.orientation) {
            config.screenHeightDp > config.screenWidthDp
        }
    }

    /**
     * Check if an element with the given visibility should be shown.
     */
    @Composable
    fun shouldShowElement(visibility: Visibility): Boolean {
        val isPortrait = rememberIsPortrait()
        return visibility.isVisible(isPortrait)
    }
}

/**
 * Convenience composable to resolve variables from a theme tree.
 */
@Composable
fun rememberResolvedVariables(
    baseVariables: Map<String, String>,
    breakpoints: List<Breakpoint>
): Map<String, String> = BreakpointResolver.rememberResolvedVariables(baseVariables, breakpoints)

/**
 * Convenience composable to check if current orientation is portrait.
 */
@Composable
fun rememberIsPortrait(): Boolean = BreakpointResolver.rememberIsPortrait()

/**
 * Convenience composable to check if an element should be visible.
 */
@Composable
fun shouldShowElement(visibility: Visibility): Boolean = BreakpointResolver.shouldShowElement(visibility)

/**
 * Effect that triggers theme remapping when orientation changes.
 * Place this in any screen that uses the theme engine.
 * 
 * This automatically triggers ThemeManager.remapForOrientation() when the 
 * screen configuration changes, ensuring breakpoint-aware variable resolution.
 */
@Composable
fun OrientationAwareThemeEffect() {
    val config = LocalConfiguration.current
    val isPortrait = config.screenHeightDp > config.screenWidthDp
    val screenWidthDp = config.screenWidthDp
    
    LaunchedEffect(isPortrait, screenWidthDp) {
        // Only remap if theme has breakpoints
        if (ThemeManager.hasBreakpoints()) {
            ThemeManager.remapForOrientation(isPortrait, screenWidthDp)
        }
    }
}

