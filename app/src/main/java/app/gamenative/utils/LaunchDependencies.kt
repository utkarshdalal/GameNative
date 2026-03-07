package app.gamenative.utils

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.utils.launchdependencies.GogScriptInterpreterDependency
import app.gamenative.utils.launchdependencies.LaunchDependencyCallbacks
import app.gamenative.utils.launchdependencies.LaunchDependency
import com.winlator.container.Container

const val LOADING_PROGRESS_UNKNOWN: Float = -1f

/**
 * Ensures all dependencies required to launch a container are downloaded and installed.
 * Reports progress via the given callbacks.
 * [gameSource] and [gameId] are extracted once by the caller (e.g. PluviaMain) and passed down.
 */
class LaunchDependencies {
    companion object {
        private val launchDependencies: List<LaunchDependency> = listOf(
            GogScriptInterpreterDependency,
        )
    }

    fun getLaunchDependencies(container: Container, gameSource: GameSource, gameId: Int): List<LaunchDependency> =
        launchDependencies.filter { it.appliesTo(container, gameSource, gameId) }

    suspend fun ensureLaunchDependencies(
        context: Context,
        container: Container,
        gameSource: GameSource,
        gameId: Int,
        setLoadingMessage: (String) -> Unit,
        setLoadingProgress: (Float) -> Unit,
    ) {
        val callbacks = LaunchDependencyCallbacks(setLoadingMessage, setLoadingProgress)
        try {
            for (dep in getLaunchDependencies(container, gameSource, gameId)) {
                if (!dep.isSatisfied(context, container, gameSource, gameId)) {
                    setLoadingMessage(dep.getLoadingMessage(context, container, gameSource, gameId))
                    dep.install(context, container, callbacks, gameSource, gameId)
                }
            }
        } finally {
            setLoadingMessage(context.getString(R.string.main_loading))
            setLoadingProgress(1f)
        }
    }
}
