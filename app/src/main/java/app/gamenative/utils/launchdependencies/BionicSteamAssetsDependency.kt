package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Pre-downloads and extracts assets required for the experimental bionic-Steam
 * launch path:
 *   - steam.exe (cached in filesDir; the Wine-side copy is refreshed each boot
 *     by extractSteamFiles in XServerScreen.kt)
 *   - lsteamclient archive for the active Proton variant; extracted to
 *     <winepath>/lib/wine/ so the .so siblings land in lib/wine/<arch>-unix/
 *     and the PE DLLs land in lib/wine/<arch>-windows/. The DLLs are then
 *     copied into the wineprefix's system32 / syswow64.
 *   - bionic Android libsteamclient.so (steam-androidarm64.tzst), extracted
 *     relative to the imagefs root so it lands at imagefs/usr/lib/.
 */
object BionicSteamAssetsDependency : LaunchDependency {
    private const val STEAM_EXE = "steam.exe"
    private const val BIONIC_STEAM_ARCHIVE = "steam-androidarm64.tzst"
    private const val STEAMCLIENT_DLLS_ARCHIVE = "steamclient-dlls-20260619.tzst"
    private const val LSTEAMCLIENT_DLL = "lsteamclient.dll"
    private const val LIBSTEAMCLIENT_SO = "libsteamclient.so"
    private const val CACERT_PEM = "cacert.pem"

    private fun lsteamclientArchiveFor(container: Container): String? = when {
        container.wineVersion.contains("arm64ec") -> "lsteamclient-arm64ec.tzst"
        container.wineVersion.contains("x86_64") -> "lsteamclient-x86_64.tzst"
        else -> null
    }

    private fun system32SrcArchDir(container: Container): String =
        if (container.wineVersion.contains("arm64ec")) "aarch64-windows" else "x86_64-windows"

    private fun unixArchDir(container: Container): String =
        if (container.wineVersion.contains("arm64ec")) "aarch64-unix" else "x86_64-unix"

    private fun lsteamclientUnixSo(context: Context, container: Container): File {
        val wineDir = wineInstallDir(context, container)
        return File(wineDir, "lib/wine/${unixArchDir(container)}/lsteamclient.so")
    }

    /**
     * Resolves the actual Wine/Proton install directory for the container.
     * imageFs.winePath is not initialized yet at dependency-install time
     * (it's set later in XServerScreen via setWinePath), so we resolve it
     * the same way XServerScreen does — through WineInfo.fromIdentifier.
     */
    private fun wineInstallDir(context: Context, container: Container): File {
        val contentsManager = ContentsManager(context).also { it.syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, container.wineVersion)
        val path = wineInfo.path
        return if (!path.isNullOrEmpty()) {
            File(path)
        } else {
            File(ImageFs.find(context).rootDir, "opt/wine")
        }
    }

    private fun system32Dll(container: Container): File =
        File(container.rootDir, ".wine/drive_c/windows/system32/" + LSTEAMCLIENT_DLL)

    private fun syswow64Dll(container: Container): File =
        File(container.rootDir, ".wine/drive_c/windows/syswow64/" + LSTEAMCLIENT_DLL)

    private fun libsteamclientSo(imageFs: ImageFs): File =
        File(imageFs.libDir, LIBSTEAMCLIENT_SO)

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        container.isLaunchBionicSteam

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val imageFs = ImageFs.find(context)
        val filesDir = imageFs.filesDir
        if (!File(filesDir, STEAM_EXE).exists()) return false
        if (!File(filesDir, CACERT_PEM).exists()) return false
        if (!File(filesDir, STEAMCLIENT_DLLS_ARCHIVE).exists()) return false
        if (!libsteamclientSo(imageFs).exists()) return false
        if (lsteamclientArchiveFor(container) != null) {
            if (!system32Dll(container).exists() || !syswow64Dll(container).exists()) return false
            if (!lsteamclientUnixSo(context, container).exists()) return false
        }
        return true
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Preparing real Steam assets"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        val imageFs = withContext(Dispatchers.IO) { ImageFs.find(context) }
        val filesDir = imageFs.filesDir

        // 1. steam.exe — cache only; XServerScreen.extractSteamFiles copies into the prefix each boot.
        val steamExeCache = File(filesDir, STEAM_EXE)
        if (!withContext(Dispatchers.IO) { steamExeCache.exists() }) {
            callbacks.setLoadingMessage("Downloading steam.exe")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = STEAM_EXE,
                ).await()
            }
        }

        val steamclientDllsCache = File(filesDir, STEAMCLIENT_DLLS_ARCHIVE)
        if (!withContext(Dispatchers.IO) { steamclientDllsCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $STEAMCLIENT_DLLS_ARCHIVE")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = STEAMCLIENT_DLLS_ARCHIVE,
                ).await()
            }
        }

        val cacertCache = File(filesDir, CACERT_PEM)
        if (!withContext(Dispatchers.IO) { cacertCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $CACERT_PEM")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = CACERT_PEM,
                ).await()
            }
        }

        // 2/3. lsteamclient archive for the active Proton variant.
        val lsteamclientArchive = lsteamclientArchiveFor(container)
        if (lsteamclientArchive != null) {
            val dllSystem32 = system32Dll(container)
            val dllSyswow64 = syswow64Dll(container)
            val unixSo = lsteamclientUnixSo(context, container)
            val allFilesPresent = withContext(Dispatchers.IO) {
                dllSystem32.exists() && dllSyswow64.exists() && unixSo.exists()
            }
            if (!allFilesPresent) {
                val archiveCache = File(filesDir, lsteamclientArchive)
                if (!withContext(Dispatchers.IO) { archiveCache.exists() }) {
                    callbacks.setLoadingMessage("Downloading $lsteamclientArchive")
                    withContext(Dispatchers.IO) {
                        SteamService.downloadFile(
                            onDownloadProgress = { callbacks.setLoadingProgress(it) },
                            parentScope = this@coroutineScope,
                            context = context,
                            fileName = lsteamclientArchive,
                        ).await()
                    }
                }

                callbacks.setLoadingMessage("Extracting lsteamclient")
                callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
                withContext(Dispatchers.IO) {
                    val wineDir = wineInstallDir(context, container)
                    val wineLibDir = File(wineDir, "lib/wine/")
                    wineLibDir.mkdirs()
                    Timber.i("Extracting $lsteamclientArchive into ${wineLibDir.absolutePath}")
                    val ok = TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        archiveCache,
                        wineLibDir,
                    )
                    if (!ok) {
                        throw IllegalStateException("Failed to extract $lsteamclientArchive into ${wineLibDir.absolutePath}")
                    }

                    val sys32Src = File(wineLibDir, "${system32SrcArchDir(container)}/$LSTEAMCLIENT_DLL")
                    val sysWowSrc = File(wineLibDir, "i386-windows/$LSTEAMCLIENT_DLL")
                    dllSystem32.parentFile?.mkdirs()
                    dllSyswow64.parentFile?.mkdirs()
                    if (!FileUtils.copy(sys32Src, dllSystem32)) {
                        throw IllegalStateException("Failed to copy ${sys32Src.absolutePath} to ${dllSystem32.absolutePath}")
                    }
                    if (!FileUtils.copy(sysWowSrc, dllSyswow64)) {
                        throw IllegalStateException("Failed to copy ${sysWowSrc.absolutePath} to ${dllSyswow64.absolutePath}")
                    }
                }
            }
        }

        // 4. bionic Android libsteamclient.so.
        val nativeLib = libsteamclientSo(imageFs)
        if (!withContext(Dispatchers.IO) { nativeLib.exists() }) {
            val bionicArchiveCache = File(filesDir, BIONIC_STEAM_ARCHIVE)
            if (!withContext(Dispatchers.IO) { bionicArchiveCache.exists() }) {
                callbacks.setLoadingMessage("Downloading $BIONIC_STEAM_ARCHIVE")
                withContext(Dispatchers.IO) {
                    SteamService.downloadFile(
                        onDownloadProgress = { callbacks.setLoadingProgress(it) },
                        parentScope = this@coroutineScope,
                        context = context,
                        fileName = BIONIC_STEAM_ARCHIVE,
                    ).await()
                }
            }

            callbacks.setLoadingMessage("Extracting bionic Steam client")
            callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
            withContext(Dispatchers.IO) {
                val ok = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    bionicArchiveCache,
                    imageFs.rootDir,
                )
                if (!ok) {
                    throw IllegalStateException("Failed to extract $BIONIC_STEAM_ARCHIVE into ${imageFs.rootDir.absolutePath}")
                }
            }
        }
    }
}
