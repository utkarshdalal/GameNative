package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container

/** Callbacks for progress during dependency download/install. */
data class LaunchDependencyCallbacks(
    val setLoadingMessage: (String) -> Unit,
    val setLoadingProgress: (Float) -> Unit,
)

/**
 * A single launch dependency (e.g. imagefs base, Wine/Proton, a file, Steam client).
 * Dependencies are gathered for a container and then satisfied in order.
 * [gameSource] and [gameId] are extracted once by the caller (e.g. PluviaMain) and passed down.
 */
interface LaunchDependency {
    /** Whether this dependency applies to the given container. */
    fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean

    /** True if already installed, so install can be skipped. */
    fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean

    /** Message shown while this dependency is being installed. */
    fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String

    /** Download and/or install this dependency. Uses calling coroutine's scope via coroutineScope. */
    suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    )
}
