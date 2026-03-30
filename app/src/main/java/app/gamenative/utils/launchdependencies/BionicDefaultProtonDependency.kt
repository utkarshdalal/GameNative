package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Ensures Proton (arm64ec or x86_64) is downloaded and extracted to imagefs_shared/proton for Bionic.
 * Only runs when container variant is BIONIC and wine version is proton-9.0-arm64ec or proton-9.0-x86_64.
 */
object BionicDefaultProtonDependency : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean {
        if (container.containerVariant != Container.BIONIC) return false
        val v = container.wineVersion
        return v.contains("proton-9.0-arm64ec") || v.contains("proton-9.0-x86_64")
    }

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val protonVersion = container.wineVersion
        val outFile = File(ImageFs.find(context).getRootDir(), "opt/$protonVersion")
        val binDir = File(outFile, "bin")
        return binDir.exists() && binDir.isDirectory
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String {
        return when {
            container.wineVersion.contains("proton-9.0-arm64ec") -> "Downloading arm64ec Proton"
            container.wineVersion.contains("proton-9.0-x86_64") -> "Downloading x86_64 Proton"
            else -> "Extracting Proton"
        }
    }

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        val protonVersion = container.wineVersion
        val imageFs = withContext(Dispatchers.IO) { ImageFs.find(context) }
        val archiveName = when {
            protonVersion.contains("proton-9.0-arm64ec") -> "proton-9.0-arm64ec.txz"
            protonVersion.contains("proton-9.0-x86_64") -> "proton-9.0-x86_64.txz"
            else -> return@coroutineScope
        }

        if (!withContext(Dispatchers.IO) { SteamService.isFileInstallable(context, archiveName) }) {
            callbacks.setLoadingMessage("Downloading $protonVersion")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    archiveName,
                ).await()
            }
        }

        val outFile = File(ImageFs.find(context).getRootDir(), "opt/$protonVersion")
        val binDir = File(outFile, "bin")
        val needsExtract = withContext(Dispatchers.IO) { !binDir.exists() || !binDir.isDirectory }
        if (needsExtract) {
            Timber.i("Extracting $protonVersion to ${outFile.absolutePath}")
            callbacks.setLoadingMessage("Extracting $protonVersion")
            callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
            val downloaded = File(imageFs.getFilesDir(), archiveName)
            withContext(Dispatchers.IO) {
                val success = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.XZ,
                    downloaded,
                    outFile,
                )
                if (!success) {
                    throw IllegalStateException("Failed to extract $protonVersion from ${downloaded.absolutePath}")
                }
                downloaded.delete()
            }
        }
    }
}
