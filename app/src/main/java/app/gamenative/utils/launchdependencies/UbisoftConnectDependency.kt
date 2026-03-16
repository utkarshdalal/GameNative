package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.Net
import com.winlator.container.Container
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

/**
 * Downloads the Ubisoft Connect installer into the game's _CommonRedist folder so that
 * the corresponding pre-install step can run it inside Wine.
 */
object UbisoftConnectDependency : LaunchDependency {

    private const val TAG = "UbisoftConnectDependency"
    private const val INSTALLER_URL =
        "https://ubistatic3-a.akamaihd.net/orbit/launcher_installer/UbisoftConnectInstaller.exe"

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean {
        if (!container.getExtra("installUbisoftConnect", "false").toBoolean()) return false

        val gameDir = getGameDir(container) ?: return false
        val gameDirPath = gameDir.absolutePath

        if (MarkerUtils.hasMarker(gameDirPath, Marker.UBISOFT_CONNECT_INSTALLED)) return false

        return true
    }

    override fun isSatisfied(
        context: Context,
        container: Container,
        gameSource: GameSource,
        gameId: Int,
    ): Boolean {
        val gameDir = getGameDir(container) ?: return true
        return getInstallerFile(gameDir).isFile
    }

    override fun getLoadingMessage(
        context: Context,
        container: Container,
        gameSource: GameSource,
        gameId: Int,
    ): String = "Downloading Ubisoft Connect installer"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        val gameDir = getGameDir(container)
        if (gameDir == null) {
            Timber.tag(TAG).w("No A: drive found for container, cannot download Ubisoft Connect installer")
            return
        }

        val installerFile = getInstallerFile(gameDir)
        if (installerFile.isFile) {
            Timber.tag(TAG).d("Ubisoft Connect installer already present at %s", installerFile.absolutePath)
            return
        }

        installerFile.parentFile?.mkdirs()

        Timber.tag(TAG).i("Downloading Ubisoft Connect installer to %s", installerFile.absolutePath)

        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(INSTALLER_URL)
                .build()

            try {
                Net.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).w(
                            "Failed to download Ubisoft Connect installer, HTTP %d",
                            response.code,
                        )
                        return@withContext
                    }

                    val body = response.body

                    val contentLength = body.contentLength()
                    var downloaded = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(installerFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                if (contentLength > 0L) {
                                    val progress = downloaded.toFloat() / contentLength.toFloat()
                                    callbacks.setLoadingProgress(progress.coerceIn(0f, 1f))
                                }
                            }
                        }
                    }

                    Timber.tag(TAG).i(
                        "Finished downloading Ubisoft Connect installer (%d bytes)",
                        downloaded,
                    )
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Error while downloading Ubisoft Connect installer")
            }
        }
    }

    private fun getGameDir(container: Container): File? {
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].equals("A", ignoreCase = true)) return File(drive[1])
        }
        return null
    }

    private fun getInstallerFile(gameDir: File): File =
        File(gameDir, "_CommonRedist/UbisoftConnect/UbisoftConnectInstaller.exe")
}

