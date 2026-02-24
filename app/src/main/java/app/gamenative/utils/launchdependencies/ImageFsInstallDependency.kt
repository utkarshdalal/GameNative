package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFsInstaller
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ImageFs install (glibc/bionic) – always last. */
internal object ImageFsInstallDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int) = true
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean =
        !ImageFsInstaller.needsInstall(context, container)
    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        if (container.containerVariant.equals(Container.GLIBC, ignoreCase = true)) {
            context.getString(R.string.main_installing_glibc)
        } else {
            context.getString(R.string.main_installing_bionic)
        }
    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            suspendCancellableCoroutine<Unit> { cont ->
                val future = ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
                    callbacks.setLoadingProgress(progress / 100f)
                }
                cont.invokeOnCancellation { future.cancel(true) }
                try {
                    val result = future.get() as Boolean
                    if (cont.isActive) {
                        if (result) {
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(IllegalStateException("ImageFs install completed but returned false"))
                        }
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }
}
