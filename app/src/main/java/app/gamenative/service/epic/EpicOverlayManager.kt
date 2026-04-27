package app.gamenative.service.epic

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages EOS (Epic Online Services) Overlay installation and configuration inside Wine containers.
 *
 * The overlay provides in-game Epic notifications, friend activity, and purchasing UI.
 * Implementation follows Legendary's overlay flow (legendary/lfs/eos.py, legendary/core.py).
 *
 * Install flow:
 *  1. Fetch the latest overlay manifest from Epic's CDN.
 *  2. Download and install overlay files into the Wine prefix (as-is, no DLL modification).
 *  3. Write the overlay install path to the Wine registry (HKCU\SOFTWARE\Epic Games\EOS\OverlayPath).
 */
@Singleton
class EpicOverlayManager @Inject constructor(
    private val epicManager: EpicManager,
    private val epicDownloadManager: EpicDownloadManager,
) {

    companion object {
        // ── EOS Overlay Epic app identifiers ─────────────────────────────────────
        // Source: legendary/lfs/eos.py  EOSOverlayApp
        const val OVERLAY_APP_NAME = "98bc04bc842e4906993fd6d6644ffb8d"
        const val OVERLAY_NAMESPACE = "302e5ede476149b1bc3e4fe6ae45e50e"
        const val OVERLAY_CATALOG_ITEM_ID = "cc15684f44d849e89e9bf4cec0508b68"

        // ── Wine prefix path (mirrors the standard Epic launcher install location) ─
        // Legendary searches for the overlay at:
        //   {prefix}/drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay
        const val OVERLAY_WINE_RELATIVE_PATH =
            "drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay"

        // Windows-style path used in the registry value
        const val OVERLAY_WIN_PATH =
            "C:\\Program Files (x86)\\Epic Games\\Launcher\\Portal\\Extras\\Overlay"

        // ── Registry keys ─────────────────────────────────────────────────────────
        // Source: legendary/lfs/eos.py  EOS_OVERLAY_KEY / EOS_OVERLAY_VALUE
        const val EOS_OVERLAY_REG_KEY = "SOFTWARE\\Epic Games\\EOS"
        const val EOS_OVERLAY_REG_VALUE = "OverlayPath"

        // ── Identification ────────────────────────────────────────────────────────
        // Presence of this file signals that the overlay is installed.
        // Mirrors legendary/core.py Core.is_overlay_install().
        const val OVERLAY_MARKER_FILE = "EOSOVH-Win64-Shipping.dll"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Install the EOS overlay into [container]'s Wine prefix.
     *
     * Idempotent: if the overlay is already up-to-date, the function returns
     * success without re-downloading unless [forceReinstall] is true.
     */
    suspend fun installOverlay(
        context: Context,
        container: Container,
        forceReinstall: Boolean = false,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val overlayDir = overlayDir(container)

            if (!forceReinstall && isOverlayInstalled(container)) {
                Timber.tag("EOSOverlay").i("Overlay already installed at ${overlayDir.absolutePath}, skipping")
                return@withContext Result.success(Unit)
            }

            Timber.tag("EOSOverlay").i("Starting EOS overlay install into container ${container.id}")

            // Get the Overlay manifest
            val manifestResult = epicManager.fetchManifestFromEpic(
                context = context,
                namespace = OVERLAY_NAMESPACE,
                catalogItemId = OVERLAY_CATALOG_ITEM_ID,
                appName = OVERLAY_APP_NAME,
            )
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull()
                        ?: Exception("Failed to fetch EOS overlay manifest"),
                )
            }

            val manifest = manifestResult.getOrNull()!!
            overlayDir.mkdirs()

            // Download overlay files to the install directory of the container
            Timber.tag("EOSOverlay").i("Downloading overlay files to ${overlayDir.absolutePath}")
            val downloadResult = epicDownloadManager.downloadOverlay(
                manifestResult = manifest,
                installPath = overlayDir.absolutePath,
                onProgress = onProgress,
            )
            if (downloadResult.isFailure) {
                return@withContext Result.failure(
                    downloadResult.exceptionOrNull()
                        ?: Exception("Failed to download EOS overlay files"),
                )
            }

            // Update registry to point to the overlay path.
            // The EOS SDK in games reads this to locate the overlay; if the
            // overlay DLLs fail to load under Wine the SDK degrades gracefully
            // (no HUD) while keeping auth/online features working.
            writeRegistryPath(container, OVERLAY_WIN_PATH)

            Timber.tag("EOSOverlay").i("EOS overlay installation complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("EOSOverlay").e(e, "EOS overlay installation failed")
            Result.failure(e)
        }
    }

    /**
     * Returns true if the overlay marker file exists in the container's Wine prefix.
     */
    fun isOverlayInstalled(container: Container): Boolean =
        File(overlayDir(container), OVERLAY_MARKER_FILE).exists()

    /**
     * Remove all overlay files from [container] and clear the registry path.
     */
    suspend fun removeOverlay(context: Context, container: Container): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dir = overlayDir(container)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Timber.tag("EOSOverlay").i("Removed overlay directory: ${dir.absolutePath}")
                }
                removeRegistryPath(container)
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("EOSOverlay").e(e, "Failed to remove EOS overlay")
                Result.failure(e)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the overlay install directory inside [container]'s Wine prefix.
     */
    private fun overlayDir(container: Container): File =
        File(container.rootDir, ".wine/$OVERLAY_WINE_RELATIVE_PATH")

    /**
     * Write the EOS overlay path to HKCU\SOFTWARE\Epic Games\EOS\OverlayPath in
     * [container]'s Wine user.reg.
     *
     * Mirrors `add_registry_entries` in legendary/lfs/eos.py for the Wine/prefix
     * code path (HKCU only; Vulkan implicit layers are not set because they do
     * not work in Wine).
     */
    private fun writeRegistryPath(container: Container, winPath: String) {
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            editor.setStringValue(EOS_OVERLAY_REG_KEY, EOS_OVERLAY_REG_VALUE, winPath)
        }
        Timber.tag("EOSOverlay").d(
            "Registry updated: HKCU\\$EOS_OVERLAY_REG_KEY\\$EOS_OVERLAY_REG_VALUE = $winPath",
        )
    }

    /**
     * Clear the EOS overlay path from the Wine user.reg.
     *
     * Mirrors `remove_registry_entries` in legendary/lfs/eos.py.
     */
    private fun removeRegistryPath(container: Container) {
        val userRegFile = File(container.rootDir, ".wine/user.reg")
        if (!userRegFile.exists()) return
        WineRegistryEditor(userRegFile).use { editor ->
            editor.setStringValue(EOS_OVERLAY_REG_KEY, EOS_OVERLAY_REG_VALUE, "")
        }
        Timber.tag("EOSOverlay").d("Removed HKCU\\$EOS_OVERLAY_REG_KEY\\$EOS_OVERLAY_REG_VALUE")
    }
}
