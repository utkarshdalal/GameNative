package app.gamenative.utils

import android.content.Context
import app.gamenative.R
import app.gamenative.utils.launchdependencies.GogScriptInterpreterDependency
import app.gamenative.utils.launchdependencies.LaunchDependencyCallbacks
import app.gamenative.utils.launchdependencies.LaunchDependency
import com.winlator.container.Container

const val LOADING_PROGRESS_UNKNOWN: Float = -1f

private val ALL_LAUNCH_DEPENDENCIES: List<LaunchDependency> = listOf(
    GogScriptInterpreterDependency,
)

/**
 * Returns the ordered list of launch dependencies that apply to this container.
 */
fun getLaunchDependencies(container: Container): List<LaunchDependency> =
    ALL_LAUNCH_DEPENDENCIES.filter { it.appliesTo(container) }

/**
 * Ensures all dependencies required to launch a container are downloaded and installed.
 * Reports progress via the given callbacks.
 */
suspend fun ensureLaunchDependencies(
    context: Context,
    container: Container,
    setLoadingMessage: (String) -> Unit,
    setLoadingProgress: (Float) -> Unit,
) {
    val callbacks = LaunchDependencyCallbacks(setLoadingMessage, setLoadingProgress)
    try {
        for (dep in getLaunchDependencies(container)) {
            if (!dep.isSatisfied(context, container)) {
                setLoadingMessage(dep.getLoadingMessage(context, container))
                dep.install(context, container, callbacks)
            }
        }
    } finally {
        setLoadingMessage(context.getString(R.string.main_loading))
        setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
    }
}
