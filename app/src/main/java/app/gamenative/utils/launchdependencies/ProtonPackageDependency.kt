package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/** A Proton package: download .txz and extract to /opt if needed. */
internal class ProtonPackageDependency(
    private val fileName: String,
    private val displayName: String,
) : LaunchDependency {
    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val expectedArchive = "${container.wineVersion}.txz"
        return fileName == expectedArchive
    }
    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        if (!SteamService.isFileInstallable(context, fileName)) return false
        val imageFs = ImageFs.find(context)
        val outFile = File(imageFs.rootDir, "/opt/${container.wineVersion}")
        val binDir = File(outFile, "bin")
        return binDir.exists() && binDir.isDirectory
    }
    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int) =
        context.getString(R.string.main_downloading_proton, displayName)
    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        if (!SteamService.isFileInstallable(context, fileName)) {
            SteamService.downloadFile(
                onDownloadProgress = callbacks.setLoadingProgress,
                parentScope = this,
                context = context,
                fileName,
            ).await()
        }
        val protonVersion = container.wineVersion
        val imageFs = ImageFs.find(context)
        val outFile = File(imageFs.rootDir, "/opt/$protonVersion")
        val binDir = File(outFile, "bin")
        if (!binDir.exists() || !binDir.isDirectory) {
            Timber.i("Extracting $fileName to /opt/")
            callbacks.setLoadingMessage(context.getString(R.string.main_extracting_proton, protonVersion))
            callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
            val downloaded = File(imageFs.getFilesDir(), fileName)
            try {
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.XZ,
                    downloaded,
                    outFile,
                )
            } catch (e: Exception) {
                Timber.e(e, "ProtonPackageDependency: failed to extract archive path=%s", downloaded.absolutePath)
                throw e
            }
        }
    }
}
