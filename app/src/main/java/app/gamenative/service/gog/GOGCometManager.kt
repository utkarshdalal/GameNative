package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGCredentials
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * Downloads Comet's Windows helpers once, deploys them into a Wine prefix, and prepares the
 * Windows-side command needed to start Comet before the actual game launch.
 *
 * This mirrors Heroic's integration approach, but keeps everything inside the Wine prefix so it
 * works on Android where the native Linux Comet build is not usable.
 */
object GOGCometManager {
    private const val TAG = "GOGComet"

    const val SESSION_METADATA_ACTIVE = "gog_comet_active"

    private const val RELEASE_TAG = "v0.2.0"
    private const val CACHE_DIR = "gog_comet/$RELEASE_TAG"

    private const val COMET_ASSET_NAME = "comet-x86_64-pc-windows-msvc.exe"
    private const val GALAXY_COMMUNICATION_ASSET_NAME = "GalaxyCommunication-dummy.exe"

    private const val PREFIX_COMET_DIR = "GameNative/Comet"
    private const val PREFIX_GALAXY_REDIST_DIR = "GOG.com/Galaxy/redists"

    private const val PREFIX_COMET_WINDOWS_PATH = "C:\\ProgramData\\GameNative\\Comet\\comet.exe"
    private const val PREFIX_GALAXY_COMM_WINDOWS_PATH = "C:\\ProgramData\\GOG.com\\Galaxy\\redists\\GalaxyCommunication.exe"

    private const val COMET_IDLE_WAIT_SECONDS = 20

    suspend fun prepareLaunchSupport(
        context: Context,
        container: Container,
    ): Result<PreparedCometLaunch> = withContext(Dispatchers.IO) {
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrElse { error ->
                return@withContext Result.failure(error)
            }

            if (credentials.accessToken.isBlank() || credentials.refreshToken.isBlank() || credentials.userId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Stored GOG credentials are incomplete"))
            }

            val cachedFiles = ensureCachedFiles(context)
            deployToPrefix(container, cachedFiles)

            Result.success(
                PreparedCometLaunch(
                    ensureGalaxyServiceCommand = buildGalaxyServiceCommand(),
                    startCometCommand = buildCometStartCommand(credentials, sanitizeUsername(credentials.username)),
                ),
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to prepare Comet launch support")
            Result.failure(e)
        }
    }

    suspend fun getGameplayDatabaseFile(
        context: Context,
        container: Container,
        appId: String,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrElse { error ->
                return@withContext Result.failure(error)
            }

            val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }
                .getOrElse { return@withContext Result.failure(IllegalArgumentException("Invalid GOG appId: $appId", it)) }
            val installPath = GOGService.getInstallPath(gameId.toString())
                ?: return@withContext Result.failure(IllegalStateException("No install path for $appId"))
            val infoJson = GOGService.getInstance()?.gogManager?.readInfoFile(appId, installPath)
                ?: return@withContext Result.failure(IllegalStateException("Could not read GOG info file for $appId"))
            val clientId = infoJson.optString("clientId", "")
            if (clientId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No clientId found for $appId"))
            }

            val gameplayDb = File(
                container.getRootDir(),
                ".wine/drive_c/users/${ImageFs.USER}/AppData/Local/comet/gameplay/$clientId/${credentials.userId}/gameplay.db",
            )
            Timber.tag(TAG).i("Resolved Comet gameplay DB for $appId: ${gameplayDb.absolutePath}")
            Result.success(gameplayDb)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to resolve Comet gameplay DB for $appId")
            Result.failure(e)
        }
    }

    private fun ensureCachedFiles(context: Context): CachedCometFiles {
        val cacheRoot = File(context.filesDir, CACHE_DIR)
        val cometExe = File(cacheRoot, "comet.exe")
        val galaxyCommunicationExe = File(cacheRoot, "GalaxyCommunication.exe")

        if (!isCached(cometExe) || !isCached(galaxyCommunicationExe)) {
            cacheRoot.mkdirs()
            downloadAsset(COMET_ASSET_NAME, cometExe)
            downloadAsset(GALAXY_COMMUNICATION_ASSET_NAME, galaxyCommunicationExe)
        }

        return CachedCometFiles(
            cometExe = cometExe,
            galaxyCommunicationExe = galaxyCommunicationExe,
        )
    }

    private fun deployToPrefix(container: Container, cachedFiles: CachedCometFiles) {
        val prefixProgramData = File(container.getRootDir(), ".wine/drive_c/ProgramData")
        val prefixCometDir = File(prefixProgramData, PREFIX_COMET_DIR)
        val prefixGalaxyDir = File(prefixProgramData, PREFIX_GALAXY_REDIST_DIR)

        prefixCometDir.mkdirs()
        prefixGalaxyDir.mkdirs()

        val prefixCometExe = File(prefixCometDir, "comet.exe")
        val prefixGalaxyCommunicationExe = File(prefixGalaxyDir, "GalaxyCommunication.exe")

        copyIfChanged(cachedFiles.cometExe, prefixCometExe)
        copyIfChanged(cachedFiles.galaxyCommunicationExe, prefixGalaxyCommunicationExe)
    }

    private fun sanitizeUsername(username: String?): String {
        val safe = username.orEmpty()
            .replace(Regex("[^A-Za-z0-9 ._-]"), "")
            .trim()

        return safe.ifEmpty { "GOG User" }
    }

    private fun buildGalaxyServiceCommand(): String {
        return "cmd.exe /c sc query GalaxyCommunication >nul 2>&1 || sc create GalaxyCommunication binpath= ${quoteWindowsArgument(PREFIX_GALAXY_COMM_WINDOWS_PATH)} >nul 2>&1"
    }

    private fun buildCometStartCommand(credentials: GOGCredentials, username: String): String {
        return buildString {
            append("cmd.exe /c start \"\" /B ")
            append(quoteWindowsArgument(PREFIX_COMET_WINDOWS_PATH))
            append(" --access-token ")
            append(quoteWindowsArgument(credentials.accessToken))
            append(" --refresh-token ")
            append(quoteWindowsArgument(credentials.refreshToken))
            append(" --user-id ")
            append(quoteWindowsArgument(credentials.userId))
            append(" --username ")
            append(quoteWindowsArgument(username))
            append(" --quit")
        }
    }

    private fun quoteWindowsArgument(value: String): String = "\"${value}\""

    private fun isCached(file: File): Boolean = file.exists() && file.length() > 0

    private fun copyIfChanged(source: File, destination: File) {
        if (destination.exists() && destination.length() == source.length()) return
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private fun downloadAsset(assetName: String, destination: File) {
        val url = "https://github.com/imLinguin/comet/releases/download/$RELEASE_TAG/$assetName"
        destination.parentFile?.mkdirs()
        val tmpFile = File(destination.parentFile, "${destination.name}.tmp")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                tmpFile.delete()
                throw IllegalStateException("Failed to download $assetName: HTTP ${response.code}")
            }

            response.body?.byteStream()?.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: run {
                tmpFile.delete()
                throw IllegalStateException("Failed to download $assetName: empty response body")
            }
        }

        if (destination.exists()) destination.delete()
        if (!tmpFile.renameTo(destination)) {
            tmpFile.copyTo(destination, overwrite = true)
            tmpFile.delete()
        }

        Timber.tag(TAG).i("Downloaded $assetName to ${destination.absolutePath}")
    }

    data class PreparedCometLaunch(
        val ensureGalaxyServiceCommand: String,
        val startCometCommand: String,
    )

    private data class CachedCometFiles(
        val cometExe: File,
        val galaxyCommunicationExe: File,
    )
}
