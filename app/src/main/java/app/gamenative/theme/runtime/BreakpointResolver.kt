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
     * Uses MainActivity's configuration state instead of LocalConfiguration.
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
        val orientation = app.gamenative.MainActivity.currentOrientation.value
        val screenWidthDp = app.gamenative.MainActivity.currentScreenWidthDp.value
        val isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        return remember(isPortrait, screenWidthDp, baseVariables, breakpoints) {
            VariableResolver.resolveWithBreakpoints(baseVariables, breakpoints, isPortrait, screenWidthDp)
        }
    }

    /**
     * Composable that returns whether the current screen is in portrait mode.
     * Uses MainActivity's configuration state instead of LocalConfiguration.
     */
    @Composable
    fun rememberIsPortrait(): Boolean {
        val orientation = app.gamenative.MainActivity.currentOrientation.value
        return remember(orientation) {
            orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
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
 * 
 * IMPORTANT: This uses MainActivity's configuration state instead of LocalConfiguration
 * because android:configChanges prevents LocalConfiguration from updating.
 */
@Composable
fun OrientationAwareThemeEffect() {
    // Use MainActivity's configuration state instead of LocalConfiguration
    // LocalConfiguration doesn't update when android:configChanges is set
    val orientation = app.gamenative.MainActivity.currentOrientation.value
    val screenWidthDp = app.gamenative.MainActivity.currentScreenWidthDp.value
    val isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    
    // Use remember to trigger remapping synchronously during composition
    // This ensures theme values are updated BEFORE the UI renders
    remember(isPortrait, screenWidthDp) {
        if (ThemeManager.hasBreakpoints()) {
            ThemeManager.remapForOrientation(isPortrait, screenWidthDp)
        }
        Unit
    }
}

/**
 * Returns a stable key that changes when orientation changes.
 * Use this with key() to force recomposition of themed content.
 * Uses MainActivity's configuration state instead of LocalConfiguration.
 */
@Composable
fun rememberOrientationKey(): String {
    val orientation = app.gamenative.MainActivity.currentOrientation.value
    val screenWidthDp = app.gamenative.MainActivity.currentScreenWidthDp.value
    val isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    return remember(isPortrait, screenWidthDp) {
        "$isPortrait-$screenWidthDp"
    }
}

