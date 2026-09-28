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
    private const val STEAM_EXE_PROTON11 = "steam-proton11.exe"
    private const val BIONIC_STEAM_VERSION = "20260709"
    private const val BIONIC_STEAM_ARCHIVE = "steam-androidarm64-$BIONIC_STEAM_VERSION.tzst"
    private const val BIONIC_STEAM_MARKER = ".bionic_steam_version"
    private const val STEAMCLIENT_DLLS_ARCHIVE = "steamclient-dlls-20260619.tzst"
    private const val LSTEAMCLIENT_DLL = "lsteamclient.dll"
    private const val LIBSTEAMCLIENT_SO = "libsteamclient.so"
    private const val CACERT_PEM = "cacert.pem"

    /**
     * Exact bionic wine-version identifier -> lsteamclient archive. The unixlib
     * ABI is wine-version-locked, so each Proton build ships its own archive.
     * Identifiers are the installable set from manifest.json (proton) and
     * R.array.bionic_wine_entries (proton-9). Add an entry when a Proton is added.
     */
    private val LSTEAMCLIENT_ARCHIVE_BY_WINE = mapOf(
        "proton-9.0-x86_64" to "lsteamclient-x86_64-proton9.tzst",
        "proton-9.0-arm64ec" to "lsteamclient-arm64ec-proton9.tzst",
        "proton-10.0-4-x86_64-1" to "lsteamclient-x86_64-proton10.tzst",
        "proton-10.0-arm64ec-2" to "lsteamclient-arm64ec-proton10.tzst",
        "proton-10.0-4-arm64ec-1" to "lsteamclient-arm64ec-proton10.tzst",
        "proton-11.0-1-x86_64-1" to "lsteamclient-x86_64-proton11.tzst",
        "proton-11.0-1-arm64ec-1" to "lsteamclient-arm64ec-proton11.tzst",
    )

    /**
     * Wine versions that ship a dedicated steam.exe helper. steam.exe is
     * otherwise version-independent, so all other versions use the default.
     */
    private val STEAM_EXE_BY_WINE = mapOf(
        "proton-11.0-1-x86_64-1" to STEAM_EXE_PROTON11,
        "proton-11.0-1-arm64ec-1" to STEAM_EXE_PROTON11,
    )

    /** Filename of the steam.exe helper (server asset + filesDir cache) for this container. */
    fun steamExeAssetFor(container: Container): String =
        STEAM_EXE_BY_WINE[container.wineVersion] ?: STEAM_EXE

    /** All steam.exe cache names we may have installed, for bionic-vs-real detection. */
    fun bionicSteamExeNames(): List<String> = listOf(STEAM_EXE, STEAM_EXE_PROTON11)

    private fun lsteamclientArchiveFor(container: Container): String? {
        val wineVersion = container.wineVersion
        LSTEAMCLIENT_ARCHIVE_BY_WINE[wineVersion]?.let { return it }
        val major = Regex("^proton-(\\d+)").find(wineVersion)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val arch = if (wineVersion.contains("arm64ec")) "arm64ec" else "x86_64"
        val proton = when {
            major <= 9 -> "proton9"
            major == 10 -> "proton10"
            else -> "proton11"
        }
        return "lsteamclient-$arch-$proton.tzst"
    }

    private fun system32SrcArchDir(container: Container): String =
        if (container.wineVersion.contains("arm64ec")) "aarch64-windows" else "x86_64-windows"

    /** `lib/wine/` inside the active Proton's install tree, where the archive extracts. */
    private fun wineLibDir(context: Context, container: Container): File =
        File(wineInstallDir(context, container), "lib/wine")

    private fun treeSystem32DllIn(wineLibDir: File, container: Container): File =
        File(wineLibDir, "${system32SrcArchDir(container)}/$LSTEAMCLIENT_DLL")

    private fun treeSyswow64DllIn(wineLibDir: File): File =
        File(wineLibDir, "i386-windows/$LSTEAMCLIENT_DLL")

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

    private fun bionicSteamMarker(imageFs: ImageFs): File =
        File(imageFs.filesDir, BIONIC_STEAM_MARKER)

    private fun bionicSteamInstalled(imageFs: ImageFs): Boolean {
        val marker = bionicSteamMarker(imageFs)
        return marker.exists() &&
            marker.readText().trim() == BIONIC_STEAM_VERSION &&
            libsteamclientSo(imageFs).exists()
    }

    /**
     * Extracts the active Proton's lsteamclient archive (downloaded by [install])
     * into that Proton's install tree, then copies the PE DLLs into the container
     * prefix (system32 + syswow64). Run every boot and always overwrites, so
     * switching a container's Proton version can never leave a stale, ABI-
     * mismatched lsteamclient behind. No-op for wine versions without a bundled
     * lsteamclient, or if the archive hasn't been downloaded yet.
     */
    fun extractLsteamclientIntoPrefix(context: Context, container: Container) {
        val archive = lsteamclientArchiveFor(container) ?: return
        val imageFs = ImageFs.find(context)
        val archiveCache = File(imageFs.filesDir, archive)
        if (!archiveCache.exists()) {
            Timber.e("lsteamclient archive $archive not downloaded; extract skipped")
            return
        }
        val libDir = wineLibDir(context, container)
        libDir.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archiveCache, libDir)) {
            Timber.e("Failed to extract $archive into ${libDir.absolutePath}")
            return
        }
        val sys32Src = treeSystem32DllIn(libDir, container)
        val sysWowSrc = treeSyswow64DllIn(libDir)
        val dstSystem32 = system32Dll(container)
        val dstSyswow64 = syswow64Dll(container)
        dstSystem32.parentFile?.mkdirs()
        dstSyswow64.parentFile?.mkdirs()
        if (!FileUtils.copy(sys32Src, dstSystem32)) {
            Timber.e("Failed to copy ${sys32Src.absolutePath} to ${dstSystem32.absolutePath}")
        }
        if (!FileUtils.copy(sysWowSrc, dstSyswow64)) {
            Timber.e("Failed to copy ${sysWowSrc.absolutePath} to ${dstSyswow64.absolutePath}")
        }
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        container.isLaunchBionicSteam

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val imageFs = ImageFs.find(context)
        val filesDir = imageFs.filesDir
        if (!File(filesDir, steamExeAssetFor(container)).exists()) return false
        if (!File(filesDir, CACERT_PEM).exists()) return false
        if (!File(filesDir, STEAMCLIENT_DLLS_ARCHIVE).exists()) return false
        if (!bionicSteamInstalled(imageFs)) return false
        // Only ensures the archive is downloaded. The extract into the Proton tree
        // + copy into the prefix happen every boot in extractLsteamclientIntoPrefix
        // (always overwriting), so they aren't cached here.
        val lsteamclientArchive = lsteamclientArchiveFor(container)
        if (lsteamclientArchive != null && !File(filesDir, lsteamclientArchive).exists()) return false
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
        val steamExeAsset = steamExeAssetFor(container)
        val steamExeCache = File(filesDir, steamExeAsset)
        if (!withContext(Dispatchers.IO) { steamExeCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $steamExeAsset")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = steamExeAsset,
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

        // 2/3. Download the lsteamclient archive for the active Proton variant. The
        // extract into the Proton tree + copy into the prefix happen every boot in
        // extractLsteamclientIntoPrefix (always overwriting), not here.
        val lsteamclientArchive = lsteamclientArchiveFor(container)
        if (lsteamclientArchive != null) {
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
        }

        // 4. bionic Android libsteamclient.so (+ sibling libs), version-gated.
        val bionicArchiveCache = File(filesDir, BIONIC_STEAM_ARCHIVE)
        if (!withContext(Dispatchers.IO) { bionicSteamInstalled(imageFs) }) {
            suspend fun fetchBionicArchive() {
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
            if (!withContext(Dispatchers.IO) { bionicArchiveCache.exists() }) fetchBionicArchive()

            callbacks.setLoadingMessage("Extracting bionic Steam client")
            callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
            var extracted = withContext(Dispatchers.IO) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, bionicArchiveCache, imageFs.rootDir)
            }
            if (!extracted) {
                withContext(Dispatchers.IO) { bionicArchiveCache.delete() }
                fetchBionicArchive()
                extracted = withContext(Dispatchers.IO) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, bionicArchiveCache, imageFs.rootDir)
                }
            }
            if (!extracted) {
                throw IllegalStateException("Failed to extract $BIONIC_STEAM_ARCHIVE into ${imageFs.rootDir.absolutePath}")
            }

            withContext(Dispatchers.IO) {
                val marker = bionicSteamMarker(imageFs)
                val tmp = File(marker.parentFile, "$BIONIC_STEAM_MARKER.tmp")
                tmp.writeText(BIONIC_STEAM_VERSION)
                if (!tmp.renameTo(marker)) {
                    tmp.copyTo(marker, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}
