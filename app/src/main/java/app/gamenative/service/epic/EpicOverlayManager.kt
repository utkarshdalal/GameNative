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

        // Unlike FH4's windowed ForzaWebHelper (which needs the no3d software-rendering
        // recipe), the EOS overlay renders OFF-SCREEN into shared D3D11 textures that
        // EOSOVH composites over the game. Forcing no3d breaks that handoff (grey
        // overlay), so the renderer must use the container's normal D3D (DXVK).

        // The checks and registry writes below are static so boot-path callers
        // (XServerScreen, launch dependencies) don't depend on EpicService being
        // alive — routing them through a service instance silently no-ops when
        // the service is stopped.

        fun overlayDir(container: Container): File =
            File(container.rootDir, ".wine/$OVERLAY_WINE_RELATIVE_PATH")

        /** True if the overlay marker file exists in the container's Wine prefix. */
        fun isOverlayInstalled(container: Container): Boolean =
            File(overlayDir(container), OVERLAY_MARKER_FILE).exists()

        /** True only when the overlay files exist AND OverlayPath is present in user.reg. */
        fun isOverlayConfigured(container: Container): Boolean {
            if (!isOverlayInstalled(container)) return false
            val userRegFile = File(container.rootDir, ".wine/user.reg")
            if (!userRegFile.isFile) return false
            return WineRegistryEditor(userRegFile).use { editor ->
                editor.getStringValue(EOS_OVERLAY_REG_KEY, EOS_OVERLAY_REG_VALUE, null) == OVERLAY_WIN_PATH
            }
        }

        /**
         * Re-write the overlay registry entries if the overlay files are installed but
         * the keys are missing. Called from the container boot path AFTER prefix
         * provisioning, because a wine/proton version change re-extracts the prefix
         * template and replaces user.reg, wiping keys written earlier in the launch.
         */
        fun ensureRegistryEntries(container: Container) {
            if (!isOverlayInstalled(container)) return
            if (isOverlayConfigured(container)) return
            Timber.tag("EOSOverlay").i("Overlay registry entries missing (prefix re-provisioned?) — re-writing")
            writeRegistryEntries(container)
        }

        /**
         * Write the EOS overlay path to HKCU\SOFTWARE\Epic Games\EOS\OverlayPath in
         * [container]'s Wine user.reg.
         *
         * Mirrors `add_registry_entries` in legendary/lfs/eos.py for the Wine/prefix
         * code path (HKCU only; Vulkan implicit layers are not set because they do
         * not work in Wine).
         */
        internal fun writeRegistryEntries(container: Container) {
            val userRegFile = File(container.rootDir, ".wine/user.reg")
            WineRegistryEditor(userRegFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                editor.setStringValue(EOS_OVERLAY_REG_KEY, EOS_OVERLAY_REG_VALUE, OVERLAY_WIN_PATH)
            }
            Timber.tag("EOSOverlay").d(
                "Registry updated: HKCU\\$EOS_OVERLAY_REG_KEY\\$EOS_OVERLAY_REG_VALUE = $OVERLAY_WIN_PATH",
            )
        }
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
                // Wine's shutdown flush can drop registry keys written mid-session,
                // so repair them even when the overlay files are already on disk.
                writeRegistryEntries(container)
                Timber.tag("EOSOverlay").i("Overlay already installed at ${overlayDir.absolutePath}, skipping download")
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
            writeRegistryEntries(container)

            Timber.tag("EOSOverlay").i("EOS overlay installation complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("EOSOverlay").e(e, "EOS overlay installation failed")
            Result.failure(e)
        }
    }

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
