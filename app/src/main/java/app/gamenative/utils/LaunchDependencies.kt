package app.gamenative.utils

import android.content.Context
import app.gamenative.R
import app.gamenative.utils.launchdependencies.GogScriptInterpreterDependency
import app.gamenative.utils.launchdependencies.LaunchDependencyCallbacks
import app.gamenative.utils.launchdependencies.LaunchDependency
import com.winlator.container.Container

const val LOADING_PROGRESS_UNKNOWN: Float = -1f

/**
 * Ensures all dependencies required to launch a container are downloaded and installed.
 * Reports progress via the given callbacks.
 */
class LaunchDependencies {
    companion object {
        private val launchDependencies: List<LaunchDependency> = listOf(
            GogScriptInterpreterDependency,
        )
    }

    fun getLaunchDependencies(container: Container): List<LaunchDependency> =
        launchDependencies.filter { it.appliesTo(container) }

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
}
