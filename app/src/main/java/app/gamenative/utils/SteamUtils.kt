package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.DepotInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.LoginResult
import app.gamenative.enums.Marker
import app.gamenative.enums.PathType
import app.gamenative.enums.SpecialGameSaveMapping
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirName
import app.gamenative.service.SteamService.Companion.getAppInfoOf
import app.gamenative.ui.component.TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.HardwareUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import timber.log.Timber
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.setLastModifiedTime

object SteamUtils {

    /**
     * True when a stored Steam session exists (offline-launch gate).
     * Matches GOG/Epic/Amazon AuthManager.hasStoredCredentials convention.
     */
    fun hasStoredCredentials(): Boolean =
        PrefManager.username.isNotEmpty() && PrefManager.refreshToken.isNotEmpty()

    // fall back at the same moment the banner would offer "Continue Offline".
    const val STEAM_LOGIN_AWAIT_MS: Long = TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS * 1000L

    // disconnect listener ignores non-terminal events on purpose: SteamService.reconnect()
    // emits Disconnected(isTerminal=false) before each retry, and a wifi blip mid-login should
    // resolve via the eventual LogonEnded(Success), not bail the wait.
    suspend fun awaitSteamLogin(timeoutMs: Long = STEAM_LOGIN_AWAIT_MS): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val onLogon: (SteamEvent.LogonEnded) -> Unit = { e ->
            when (e.loginResult) {
                LoginResult.Success -> deferred.complete(true)
                LoginResult.Failed -> deferred.complete(false)
                else -> Unit
            }
        }
        val onDisconnect: (SteamEvent.Disconnected) -> Unit = { e ->
            if (e.isTerminal) deferred.complete(false)
        }
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogon)
        PluviaApp.events.on<SteamEvent.Disconnected, Unit>(onDisconnect)
        try {
            // register-then-check: a flag check before registration would race events
            // fired in the gap.
            if (SteamService.isLoggedIn) return true
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogon)
            PluviaApp.events.off<SteamEvent.Disconnected, Unit>(onDisconnect)
        }
    }

    fun getDownloadBytes(manifest: ManifestInfo?): Long {
        if (manifest == null) return 0L
        return if (manifest.download > 0L) manifest.download else manifest.size
    }

    internal val http = Net.http.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val sfd by lazy {
        SimpleDateFormat("MMM d - h:mm a", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    /**
     * Converts steam time to actual time
     * @return a string in the 'MMM d - h:mm a' format.
     */
    // Note: Mostly correct, has a slight skew when near another minute
    fun fromSteamTime(rtime: Int): String = sfd.format(rtime * 1000L)

    /**
     * Converts steam time from the playtime of a friend into an approximate double representing hours.
     * @return A string representing how many hours were played, ie: 1.5 hrs
     */
    fun formatPlayTime(time: Int): String {
        val hours = time / 60.0
        return if (hours % 1 == 0.0) {
            hours.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", time / 60.0)
        }
    }

    // Steam strips all non-ASCII characters from usernames and passwords
    // source: https://github.com/steevp/UpdogFarmer/blob/8f2d185c7260bc2d2c92d66b81f565188f2c1a0e/app/src/main/java/com/steevsapps/idledaddy/LoginActivity.java#L166C9-L168C104
    // more: https://github.com/winauth/winauth/issues/368#issuecomment-224631002
    /**
     * Strips non-ASCII characters from String
     */
    fun removeSpecialChars(s: String): String = s.replace(Regex("[^\\u0000-\\u007F]"), "")

    private fun generateInterfacesFile(dllPath: Path) {
        val outFile = dllPath.parent.resolve("steam_interfaces.txt")
        if (Files.exists(outFile)) return          // already generated on a previous boot

        // -------- read DLL into memory ----------------------------------------
        val bytes = Files.readAllBytes(dllPath)
        val strings = mutableSetOf<String>()

        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 10) {                 // only consider reasonably long strings
                val candidate = sb.toString()
                if (candidate.matches(Regex("^Steam[A-Za-z]+[0-9]{3}\$", RegexOption.IGNORE_CASE)))
                    strings += candidate
            }
            sb.setLength(0)
        }

        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            if (ch in 0x20..0x7E) {                // printable ASCII
                sb.append(ch.toChar())
            } else {
                flush()
            }
        }
        flush()                                    // catch trailing string

        if (strings.isEmpty()) {
            Timber.w("No Steam interface strings found in ${dllPath.fileName}")
            return
        }

        val sorted = strings.sorted()
        Files.write(outFile, sorted)
        Timber.i("Generated steam_interfaces.txt (${sorted.size} interfaces)")
    }

    private fun copyOriginalSteamDll(dllPath: Path, appDirPath: String): String? {
        // 1️⃣  back-up next to the original DLL
        val backup = dllPath.parent.resolve("${dllPath.fileName}.orig")
        if (Files.notExists(backup)) {
            try {
                Files.copy(dllPath, backup)
                Timber.i("Copied original ${dllPath.fileName} to $backup")
            } catch (e: IOException) {
                Timber.w(e, "Failed to back up ${dllPath.fileName}")
                return null
            }
        }
        // 2️⃣  return the relative path inside the app directory (even if backup already existed)
        return try {
            val relPath = Paths.get(appDirPath).relativize(backup)
            relPath.toString()
        } catch (e: Exception) {
            Timber.w(e, "Failed to compute relative path for ${dllPath.fileName}")
            null
        }
    }

    /**
     * Mark the Steamworks Common Redistributables (228980) install scripts as
     * already executed, so `steam.exe -applaunch` does not re-launch the bundled
     * vcredist / DXSETUP installers on every boot (which can race the game's
     * MSVC loader and steal window focus). Writes Installed=1/Run=1 under the
     * HKLM keys Steam consults before invoking an InstallScript.
     */
    fun applySteamInstallScriptShim(context: Context, steamAppId: Int) {
        try {
            // Per-container path; imageFs.wineprefix resolves through the
            // global xuser symlink which is unsafe for writes (see
            // WineUtils.applySystemTweaks rationale).
            val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
            val systemRegFile = File(container.rootDir, ".wine/system.reg")
            if (!systemRegFile.isFile) return

            val appKeys = listOf(
                "Software\\Valve\\Steam\\Apps\\228980",
                "Software\\Wow6432Node\\Valve\\Steam\\Apps\\228980",
                "Software\\Valve\\Steam\\Apps\\$steamAppId",
                "Software\\Wow6432Node\\Valve\\Steam\\Apps\\$steamAppId",
            )
            val depotIds = sharedDepotInstallScripts.keys

            WineRegistryEditor(systemRegFile).use { reg ->
                reg.setCreateKeyIfNotExist(true)
                appKeys.forEach { key ->
                    reg.setDwordValue(key, "Installed", 1)
                    reg.setDwordValue(key, "Updating", 0)
                    reg.setDwordValue(key, "Running", 0)
                }
                depotIds.forEach { depotId ->
                    val perDepotKeys = listOf(
                        "Software\\Valve\\Steam\\Apps\\228980\\Depots\\$depotId",
                        "Software\\Wow6432Node\\Valve\\Steam\\Apps\\228980\\Depots\\$depotId",
                        "Software\\Valve\\Steam\\Apps\\$steamAppId\\Depots\\$depotId",
                        "Software\\Wow6432Node\\Valve\\Steam\\Apps\\$steamAppId\\Depots\\$depotId",
                        "Software\\Valve\\Steam\\InstallScripts\\$depotId",
                        "Software\\Wow6432Node\\Valve\\Steam\\InstallScripts\\$depotId",
                    )
                    perDepotKeys.forEach { key ->
                        reg.setDwordValue(key, "Installed", 1)
                        reg.setDwordValue(key, "Run", 1)
                    }
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "applySteamInstallScriptShim: registry IO failed for appId=%d", steamAppId)
        } catch (e: SecurityException) {
            Timber.w(e, "applySteamInstallScriptShim: registry access denied for appId=%d", steamAppId)
        }
    }

    // Hash-verify the steam_api DLL on disk against the pipe DLL shipped in assets.
    // An interrupted launch can desync markers from reality (marker says REPLACED but
    // the DLL is the game's original, or vice versa). Returns true if the marker's
    // claim matches disk. Any IO/hash failure is treated as desync so we reheal.
    private fun pipeDllHashes(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        listOf("steam_api.dll", "steam_api64.dll").forEach { name ->
            runCatching {
                context.assets.open("steampipe/$name").use { ins ->
                    out[name.lowercase()] = sha256OfStream(ins)
                }
            }
        }
        return out
    }

    private fun verifyReplacedState(context: Context, appDirPath: String): Boolean {
        return try {
            val assetHashes = pipeDllHashes(context)
            var found = false
            Paths.get(appDirPath).toFile().walkTopDown().maxDepth(10).forEach { file ->
                if (!file.isFile) return@forEach
                val n = file.name.lowercase()
                if (n == "steam_api.dll" || n == "steam_api64.dll") {
                    found = true
                    val expected = assetHashes[n]
                    if (expected == null) {
                        // Missing pipe-asset hash means we can't verify — fail closed and force
                        // a re-replace rather than silently trust the marker.
                        Timber.w("DLL marker desync: pipe asset hash missing for %s, can't verify replaced state", n)
                        return false
                    }
                    if (sha256OfFile(file) != expected) {
                        Timber.w("DLL marker desync: %s hash mismatch (marker says REPLACED)", file.absolutePath)
                        return false
                    }
                }
            }
            if (!found) {
                Timber.w("DLL marker desync: no steam_api DLL found under %s but REPLACED marker present", appDirPath)
                return false
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "verifyReplacedState failed, treating as desync")
            false
        }
    }

    private fun verifyRestoredState(context: Context, appDirPath: String): Boolean {
        return try {
            val assetHashes = pipeDllHashes(context)
            var found = false
            Paths.get(appDirPath).toFile().walkTopDown().maxDepth(10).forEach { file ->
                if (!file.isFile) return@forEach
                val n = file.name.lowercase()
                if (n == "steam_api.dll" || n == "steam_api64.dll") {
                    found = true
                    val pipeHash = assetHashes[n]
                    if (pipeHash == null) {
                        // Missing pipe-asset hash means we can't tell whether this is the pipe
                        // DLL or the original Valve DLL — fail closed so the caller re-restores.
                        Timber.w("DLL marker desync: pipe asset hash missing for %s, can't verify restored state", n)
                        return false
                    }
                    if (sha256OfFile(file) == pipeHash) {
                        Timber.w("DLL marker desync: %s is still the pipe DLL (marker says RESTORED)", file.absolutePath)
                        return false
                    }
                }
            }
            if (!found) {
                // No steam_api*.dll on disk — marker is stale (game dir moved/deleted/relocated).
                // Fail closed so putBackSteamDlls() runs on next launch; it's a safe no-op when
                // there are no .orig backups either (some games genuinely don't ship the DLL).
                Timber.w("DLL marker desync: no steam_api DLL found under %s but RESTORED marker present", appDirPath)
                return false
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "verifyRestoredState failed, treating as desync")
            false
        }
    }

    private fun sha256OfFile(file: File): String = file.inputStream().use { sha256OfStream(it) }

    private fun sha256OfStream(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Replaces any existing `steam_api.dll` or `steam_api64.dll` in the app directory
     * with our pipe dll stored in assets
     */
    suspend fun replaceSteamApi(context: Context, appId: String, isOffline: Boolean = false) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED)) {
            if (verifyReplacedState(context, appDirPath)) {
                return
            }
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
        Timber.i("Starting replaceSteamApi for appId: $appId")
        Timber.i("Checking directory: $appDirPath")
        var replaced32Count = 0
        var replaced64Count = 0
        val backupPaths = mutableSetOf<String>()
        val imageFs = ImageFs.find(context)
        // Pass the container so writes go to the per-container .wine prefix
        // rather than through the global xuser symlink (which can race with
        // another concurrent launch and corrupt that game's prefix).
        val container = ContainerUtils.getContainer(context, appId)
        autoLoginUserChanges(imageFs, container)
        // userdata is keyed by Steam3 accountID (matches restoreSteamApi). userSteamId is null on offline.
        setupLightweightSteamConfig(imageFs, getSteam3AccountId()?.toString(), container)

        val rootPath = Paths.get(appDirPath)
        // Get ticket once for all DLLs
        val ticketBase64 = SteamService.instance?.getEncryptedAppTicketBase64(steamAppId)

        rootPath.toFile().walkTopDown().maxDepth(10).forEach { file ->
            val path = file.toPath()
            if (!file.isFile || !path.name.startsWith("steam_api", ignoreCase = true)) return@forEach

            val is64Bit = path.name.equals("steam_api64.dll", ignoreCase = true)
            val is32Bit = path.name.equals("steam_api.dll", ignoreCase = true)

            if (is64Bit || is32Bit) {
                val dllName = if (is64Bit) "steam_api64.dll" else "steam_api.dll"
                Timber.i("Found $dllName at ${path.absolutePathString()}, replacing...")
                generateInterfacesFile(path)
                val relPath = copyOriginalSteamDll(path, appDirPath)
                if (relPath != null) {
                    backupPaths.add(relPath)
                }
                Files.delete(path)
                Files.createFile(path)
                FileOutputStream(path.absolutePathString()).use { fos ->
                    context.assets.open("steampipe/$dllName").use { fs ->
                        fs.copyTo(fos)
                    }
                }
                Timber.i("Replaced $dllName")
                if (is64Bit) replaced64Count++ else replaced32Count++
                ensureSteamSettings(context, path, appId, ticketBase64, isOffline)
            }
        }

        // Write all collected backup paths to orig_dll_path.txt
        if (backupPaths.isNotEmpty()) {
            try {
                Files.write(
                    Paths.get(appDirPath).resolve("orig_dll_path.txt"),
                    backupPaths.sorted(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                Timber.i("Wrote ${backupPaths.size} DLL backup paths to orig_dll_path.txt")
            } catch (e: IOException) {
                Timber.w(e, "Failed to write orig_dll_path.txt")
            }
        }

        Timber.i("Finished replaceSteamApi for appId: $appId. Replaced 32bit: $replaced32Count, Replaced 64bit: $replaced64Count")

        // Restore unpacked executable if it exists (for DRM-free mode)
        restoreUnpackedExecutable(context, steamAppId)

        // Restore original steamclient.dll files if they exist
        restoreSteamclientFiles(context, steamAppId)

        // Create Steam ACF manifest for real Steam compatibility
        createAppManifest(context, steamAppId)

        // Game-specific Handling
        ensureSaveLocationsForGames(context, steamAppId, container)

        // Generate achievements.json
        generateAchievementsFile(rootPath.resolve("steam_settings"), appId)

        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
    }

    /**
     * Replaces any existing `steamclient.dll` or `steamclient64.dll` in the Steam directory
     */
    suspend fun replaceSteamclientDll(context: Context, appId: String, isOffline: Boolean = false) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        val container = ContainerUtils.getContainer(context, appId)

        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED) && File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamclient_loader_x64.dll").exists()) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)

        // Make a backup before extracting
        backupSteamclientFiles(context, steamAppId)

        // Delete extra_dlls folder before extraction to prevent conflicts
        val extraDllDir = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/extra_dlls")
        if (extraDllDir.exists()) {
            extraDllDir.deleteRecursively()
            Timber.i("Deleted extra_dlls directory before extraction for appId: $steamAppId")
        }

        val imageFs = ImageFs.find(context)
        val downloaded = File(imageFs.getFilesDir(), "experimental-drm-20260116.tzst")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            downloaded,
            imageFs.getRootDir(),
        )
        putBackSteamDlls(appDirPath)
        restoreUnpackedExecutable(context, steamAppId)

        // Get ticket and pass to ensureSteamSettings
        val ticketBase64 = SteamService.instance?.getEncryptedAppTicketBase64(steamAppId)
        val path = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamclient.dll").toPath()
        ensureSteamSettings(context, path, appId, ticketBase64, isOffline)
        generateAchievementsFile(path, appId)

        // Game-specific Handling
        ensureSaveLocationsForGames(context, steamAppId, container)

        restoreLegacyStashedOverlayFiles(container)

        MarkerUtils.addMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
    }

    fun steamClientFiles() : Array<String> {
        return arrayOf(
            "GameOverlayRenderer.dll",
            "GameOverlayRenderer64.dll",
            "steamclient.dll",
            "steamclient64.dll",
            "steamclient_loader_x32.exe",
            "steamclient_loader_x64.exe",
        )
    }

    fun backupSteamclientFiles(context: Context, steamAppId: Int) {
        // Per-container path; imageFs.wineprefix resolves through the global xuser
        // symlink which is unsafe for writes (see WineUtils.applySystemTweaks).
        val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
        val steamDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")

        var backupCount = 0

        val backupDir = File(steamDir, "steamclient_backup")
        backupDir.mkdirs()

        steamClientFiles().forEach { file ->
            val dll = File(steamDir, file)
            if (dll.exists()) {
                Files.copy(dll.toPath(), File(backupDir, "$file.orig").toPath(), StandardCopyOption.REPLACE_EXISTING)
                backupCount++
            }
        }

        Timber.i("Finished backupSteamclientFiles for appId: $steamAppId. Backed up $backupCount file(s)")
    }

    // Upgrade helper: a prior build disabled the Steam overlay by renaming
    // overlay binaries (and Vulkan layer JSONs) to `.disabled`. The overlay
    // disable is now done through Khronos env vars at launch time, so any
    // leftover `.disabled` stashes need to be restored or the overlay stays
    // broken even with the toggle off. Runs once per launch; a no-op once
    // clean. Safe to delete after a release or two.
    private val legacyStashedOverlayFiles = arrayOf(
        "GameOverlayRenderer.dll",
        "GameOverlayRenderer64.dll",
        "SteamOverlayVulkanLayer.dll",
        "SteamOverlayVulkanLayer64.dll",
        "GameOverlayUI.exe",
        "SteamOverlayVulkanLayer.json",
        "SteamOverlayVulkanLayer64.json",
    )

    private fun restoreLegacyStashedOverlayFiles(container: com.winlator.container.Container) {
        val steamDir = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam")
        legacyStashedOverlayFiles.forEach { name ->
            val live = File(steamDir, name)
            val stashed = File(steamDir, "$name.disabled")
            if (stashed.exists() && !live.exists()) stashed.renameTo(live)
        }
    }

    fun restoreSteamclientFiles(context: Context, steamAppId: Int) {
        // Per-container path; imageFs.wineprefix resolves through the global xuser
        // symlink which is unsafe for writes (see WineUtils.applySystemTweaks).
        val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
        val origDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")

        var restoredCount = 0

        val backupDir = File(origDir, "steamclient_backup")
        if (backupDir.exists()) {
            steamClientFiles().forEach { file ->
                val dll = File(backupDir, "$file.orig")
                if (dll.exists()) {
                    Files.copy(dll.toPath(), File(origDir, file).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    restoredCount++
                }
            }
        }

        val extraDllDir = File(origDir, "extra_dlls")
        if (extraDllDir.exists()) {
            extraDllDir.deleteRecursively()
        }

        Timber.i("Finished restoreSteamclientFiles for appId: $steamAppId. Restored $restoredCount file(s)")
    }

    internal fun generateColdClientIni(
        gameName: String,
        executablePath: String,
        exeCommandLine: String,
        steamAppId: Int,
        workingDir: String?,
        isUnpackFiles: Boolean,
    ): String {
        val exePath = "steamapps\\common\\$gameName\\${executablePath.replace("/", "\\")}"
        val exeRunDir = if (workingDir.isNullOrEmpty()) exePath.substringBeforeLast("\\") else ""

        // Only include DllsToInjectFolder if unpackFiles is enabled
        val injectionSection = if (isUnpackFiles) {
            """
                [Injection]
                IgnoreLoaderArchDifference=1
                DllsToInjectFolder=extra_dlls
            """
        } else {
            """
                [Injection]
                IgnoreLoaderArchDifference=1
            """
        }

        return """
                [SteamClient]

                Exe=$exePath
                ExeRunDir=$exeRunDir
                ExeCommandLine=$exeCommandLine
                AppId=$steamAppId

                # path to the steamclient dlls, both must be set, absolute paths or relative to the loader directory
                SteamClientDll=steamclient.dll
                SteamClient64Dll=steamclient64.dll

                $injectionSection
            """.trimIndent()
    }

    internal fun writeColdClientIni(steamAppId: Int, container: Container, launchInfo: LaunchInfo? = null) {
        val gameName = getAppDirName(getAppInfoOf(steamAppId))
        val workingDir = launchInfo?.workingDir
        val iniFile = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini")
        iniFile.parentFile?.mkdirs()
        iniFile.writeText(
            generateColdClientIni(
                gameName = gameName,
                executablePath = container.executablePath,
                exeCommandLine = container.execArgs,
                steamAppId = steamAppId,
                workingDir = workingDir,
                isUnpackFiles = container.isUnpackFiles,
            )
        )
    }

    fun autoLoginUserChanges(imageFs: ImageFs, container: Container? = null) {
        // userSteamId is null on offline launch — fall back to persisted ID, else writer puts "null" in vdf
        val steamId64 = SteamService.userSteamId?.convertToUInt64()?.toString()
            ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.toString()
            ?: "0"
        val vdfFileText = SteamService.getLoginUsersVdfOauth(
            steamId64 = steamId64,
            account = PrefManager.username,
            refreshToken = PrefManager.refreshToken,
            accessToken = PrefManager.accessToken,      // may be blank
            personaName = SteamService.instance?.localPersona?.value?.name ?: PrefManager.username
        )
        // When called from a launch flow, prefer the per-container path.
        // imageFs.wineprefix resolves through the global xuser symlink which is
        // unsafe for concurrent launches (writes leak into the wrong
        // container's prefix). The symlink-based fallback is kept for the
        // login flow, which has no specific container context.
        val winePrefixBase: File = container?.let { File(it.rootDir, ".wine") }
            ?: File(imageFs.wineprefix)
        val steamConfigDir = File(winePrefixBase, "drive_c/Program Files (x86)/Steam/config")
        try {
            // Ensure the Steam config dir exists. On a fresh container the Wine prefix
            // skeleton is in place but Steam's own subdir hasn't been laid down yet —
            // writeText() would fail with FileNotFoundException without this.
            if (!steamConfigDir.isDirectory && !steamConfigDir.mkdirs()) {
                Timber.w("autoLoginUserChanges: failed to create $steamConfigDir; loginusers.vdf write may fail")
            }
            File(steamConfigDir, "loginusers.vdf").writeText(vdfFileText)
            val userRegFile = File(winePrefixBase, "user.reg")
            val steamRoot = "C:\\Program Files (x86)\\Steam"
            val steamExe = "$steamRoot\\steam.exe"
            val hkcu = "Software\\Valve\\Steam"
            WineRegistryEditor(userRegFile).use { reg ->
                reg.setStringValue("Software\\Valve\\Steam", "AutoLoginUser", PrefManager.username)
                reg.setStringValue(hkcu, "SteamExe", steamExe)
                reg.setStringValue(hkcu, "SteamPath", steamRoot)
                reg.setStringValue(hkcu, "InstallPath", steamRoot)
            }
        } catch (e: Exception) {
            Timber.w("Could not add steam config options: $e")
        }
    }

    /**
     * Creates configuration files that make Steam run in lightweight mode
     * with reduced resource usage and disabled community features
     */
    private fun setupLightweightSteamConfig(imageFs: ImageFs, steamId64: String?, container: Container? = null) {
        Timber.i("Setting up lightweight steam configs")
        try {
            // Prefer per-container path. Fallback to imageFs.wineprefix (xuser
            // symlink) is kept for non-launch callers that have no container.
            val steamPath = container?.let { File(it.rootDir, ".wine/drive_c/Program Files (x86)/Steam") }
                ?: File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")

            // Create necessary directories
            val userDataPath = File(steamPath, "userdata/$steamId64")
            val configPath = File(userDataPath, "config")
            val remotePath = File(userDataPath, "7/remote")

            configPath.mkdirs()
            remotePath.mkdirs()

            // Create localconfig.vdf for small mode and low resource usage
            val localConfigContent = """
                "UserLocalConfigStore"
                {
                  "Software"
                  {
                    "Valve"
                    {
                      "Steam"
                      {
                        "SmallMode"                      "1"
                        "LibraryDisableCommunityContent" "1"
                        "LibraryLowBandwidthMode"        "1"
                        "LibraryLowPerfMode"             "1"
                      }
                    }
                  }
                  "friends"
                  {
                    "SignIntoFriends" "0"
                  }
                }
            """.trimIndent()

            // Create sharedconfig.vdf for additional optimizations
            val sharedConfigContent = """
                "UserRoamingConfigStore"
                {
                  "Software"
                  {
                    "Valve"
                    {
                      "Steam"
                      {
                        "SteamDefaultDialog" "#app_games"
                        "FriendsUI"
                        {
                          "FriendsUIJSON" "{\"bSignIntoFriends\":false,\"bAnimatedAvatars\":false,\"PersonaNotifications\":0,\"bDisableRoomEffects\":true}"
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            // Write the configuration files if they don't exist
            val localConfigFile = File(configPath, "localconfig.vdf")
            val sharedConfigFile = File(remotePath, "sharedconfig.vdf")

            if (!localConfigFile.exists()) {
                localConfigFile.writeText(localConfigContent)
                Timber.i("Created lightweight Steam localconfig.vdf")
            }

            if (!sharedConfigFile.exists()) {
                sharedConfigFile.writeText(sharedConfigContent)
                Timber.i("Created lightweight Steam sharedconfig.vdf")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to setup lightweight Steam configuration")
        }
    }

    /**
     * Restores the unpacked executable (.unpacked.exe) if it exists and is different from current .exe
     * This ensures we use the DRM-free version when not using real Steam
     */
    private fun restoreUnpackedExecutable(context: Context, steamAppId: Int) {
        try {
            val appDirPath = SteamService.getAppDirPath(steamAppId)

            // Convert to Wine path format
            val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
            val executablePath = container.executablePath
            val drives = container.drives
            val driveIndex = drives.indexOf(appDirPath)
            val drive = if (driveIndex > 1) {
                drives[driveIndex - 2]
            } else {
                Timber.e("Could not locate game drive")
                'D'
            }
            val executableFile = "$drive:\\${executablePath}"

            // Per-container .wine path; imageFs.wineprefix resolves through the
            // global xuser symlink which is unsafe for writes.
            val winePrefix = File(container.rootDir, ".wine").absolutePath
            val exe = File(winePrefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/'))
            val unpackedExe = File(winePrefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/') + ".unpacked.exe")

            if (unpackedExe.exists()) {
                // Check if files are different (compare size and last modified time for efficiency)
                val areFilesDifferent = !exe.exists() ||
                    exe.length() != unpackedExe.length() ||
                    exe.lastModified() != unpackedExe.lastModified()

                if (areFilesDifferent) {
                    Files.copy(unpackedExe.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Timber.i("Restored unpacked executable from ${unpackedExe.name} to ${exe.name}")
                } else {
                    Timber.i("Unpacked executable is already current, no restore needed")
                }
            } else {
                Timber.i("No unpacked executable found, using current executable")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore unpacked executable for appId $steamAppId")
        }
    }

    /**
     * Creates a Steam ACF (Application Cache File) manifest for the given app
     * This allows real Steam to detect the game as installed
     */
    private fun createAppManifest(context: Context, steamAppId: Int) {
        try {
            val appInfo = SteamService.getAppInfoOf(steamAppId)
            if (appInfo == null) {
                Timber.w("createAppManifest: no SteamApp info for appId=$steamAppId — Steam will gray Play")
                return
            }

            // Per-container path; imageFs.wineprefix resolves through the global
            // xuser symlink which is unsafe for writes.
            val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
            val steamappsDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam/steamapps")
            if (!steamappsDir.exists()) {
                steamappsDir.mkdirs()
            }

            // Create the common folder
            val commonDir = File(steamappsDir, "common")
            if (!commonDir.exists()) {
                commonDir.mkdirs()
            }

            // Get game directory info
            val gameDir = File(SteamService.getAppDirPath(steamAppId))
            val gameName = gameDir.name
            val sizeOnDisk = calculateDirectorySize(gameDir)

            // Resolve the installdir Steam expects. Real steam.exe -applaunch looks
            // the game up via steamapps/common/<installdir>, NOT the on-disk folder
            // name, so the primary symlink must match the manifest's installdir.
            val actualInstallDir = appInfo.config.installDir.ifEmpty { gameName }
            val primaryLink = File(commonDir, actualInstallDir)
            createSteamCommonLink(primaryLink, gameDir)
            // Keep a fallback alias under the on-disk folder name for code paths
            // that still resolve via gameDir.name (WineUtils, legacy helpers).
            if (actualInstallDir != gameName) {
                createSteamCommonLink(File(commonDir, gameName), gameDir)
            }

            val installedBranch = SteamService.getInstalledApp(steamAppId)?.branch ?: "public"
            val buildId = (appInfo.branches[installedBranch] ?: appInfo.branches["public"])?.buildId ?: 0L
            if (buildId == 0L) {
                Timber.w("createAppManifest: unresolvable buildid for appId=$steamAppId branch=$installedBranch")
                return
            }
            val downloadableDepots = SteamService.getDownloadableDepots(steamAppId)

            val regularDepots = mutableMapOf<Int, DepotInfo>()
            val sharedDepots = mutableMapOf<Int, DepotInfo>()

            downloadableDepots.forEach { (depotId, depotInfo) ->
                val manifest = depotInfo.manifests[installedBranch]
                    ?: depotInfo.manifests["public"]
                    ?: depotInfo.manifests.values.firstOrNull()
                if (manifest != null && manifest.gid != 0L) {
                    regularDepots[depotId] = depotInfo
                } else {
                    sharedDepots[depotId] = depotInfo
                }
            }

            // Find the main content depot (owner) - typically the one with the lowest ID that has content
            val mainDepotId = regularDepots.keys.minOrNull()

            // LastOwner is how Steam attributes the install to a signed-in account.
            // Leaving it zero makes cloud-sync/ownership checks resolve against a
            // non-existent user, which is one of the stalls that leaves Play disabled.
            val lastOwner = SteamService.userSteamId?.convertToUInt64()?.toString() ?: "0"
            if (lastOwner == "0") {
                Timber.w("createAppManifest: appId=$steamAppId LastOwner=0 (no signed-in SteamID) — cloud/ownership checks may stall")
            }

            // Pre-resolve depot manifests so we can bail before writing a broken ACF.
            val regularDepotManifests = regularDepots.mapValues { (_, depotInfo) ->
                depotInfo.manifests[installedBranch]
                    ?: depotInfo.manifests["public"]
                    ?: depotInfo.manifests.values.firstOrNull()
            }
            val brokenDepotIds = regularDepotManifests.filter { (_, m) -> m == null || m.gid == 0L }.keys
            if (brokenDepotIds.isNotEmpty()) {
                Timber.w("createAppManifest: appId=$steamAppId depot(s) $brokenDepotIds have no resolvable manifest GID for branch=$installedBranch — skipping (would trigger Update Required)")
                return
            }

            // If PICS returned no resolvable regular depots, don't overwrite a previously-good
            // acf with an empty InstalledDepots block (Steam would flip Update Required on it).
            if (regularDepots.isEmpty()) {
                val existing = File(steamappsDir, "appmanifest_$steamAppId.acf")
                val existingBuildId = if (existing.isFile) parseAcfBuildId(existing) else 0L
                val existingDepotCount = if (existing.isFile) parseAcfInstalledDepotIds(existing).size else 0
                val hasValidExisting = existing.isFile && existingBuildId > 0L && existingDepotCount > 0
                if (!hasValidExisting) {
                    Timber.w(
                        "createAppManifest: appId=%d has no regular depots and no valid existing acf — Steam will likely gray Play until PICS returns full data",
                        steamAppId,
                    )
                }
                // Still refresh shared helper manifests (228980, 241100) — they're independent of the child's state.
                writeAllSharedAppManifests(steamappsDir, commonDir, lastOwner, sharedDepots.keys)
                return
            }

            // Create ACF content
            val acfContent = buildString {
                appendLine("\"AppState\"")
                appendLine("{")
                appendLine("\t\"appid\"\t\t\"$steamAppId\"")
                appendLine("\t\"Universe\"\t\t\"1\"")
                appendLine("\t\"name\"\t\t\"${escapeString(appInfo.name)}\"")
                // StateFlags = 4 (fully installed). Deliberately omit 2/8/16 — Steam
                // flips those itself if it decides an update is needed.
                appendLine("\t\"StateFlags\"\t\t\"4\"")
                appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
                appendLine("\t\"SizeOnDisk\"\t\t\"$sizeOnDisk\"")
                appendLine("\t\"buildid\"\t\t\"$buildId\"")
                // TargetBuildID = buildId and UpdateResult=0 so Steam doesn't think it's mid-update.
                appendLine("\t\"TargetBuildID\"\t\t\"$buildId\"")
                appendLine("\t\"UpdateResult\"\t\t\"0\"")
                appendLine("\t\"AppType\"\t\t\"Game\"")
                appendLine("\t\"betakey\"\t\t\"${if (installedBranch == "public") "" else escapeString(installedBranch)}\"")

                appendLine("\t\"installdir\"\t\t\"${escapeString(actualInstallDir)}\"")

                appendLine("\t\"LastOwner\"\t\t\"$lastOwner\"")
                appendLine("\t\"BytesToDownload\"\t\t\"0\"")
                appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
                appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
                appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
                appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")

                if (regularDepots.isNotEmpty()) {
                    appendLine("\t\"InstalledDepots\"")
                    appendLine("\t{")
                    regularDepots.forEach { (depotId, _) ->
                        val manifest = regularDepotManifests[depotId]!!
                        appendLine("\t\t\"$depotId\"")
                        appendLine("\t\t{")
                        appendLine("\t\t\t\"manifest\"\t\t\"${manifest.gid}\"")
                        appendLine("\t\t\t\"size\"\t\t\"${manifest.size}\"")
                        appendLine("\t\t}")
                    }
                    appendLine("\t}")
                }

                // Declare shared-redist depots (228985/228989 etc.) as
                // SharedDepots owned by their parent app (e.g. 228980). Without
                // this block Steam loads the child's acf, notices via PICS that
                // these depots are "really" owned by 228980, logs
                // `config changed : removed depots` + `Dependency added: parent
                // 228980, child <me>`, and flips 228980 → `Update Required` →
                // cascades `Fully Installed,Update Queued,` onto us. That is
                // the gray-Play state. Declaring the ownership explicitly tells
                // Steam the link is already satisfied, so no recategorization /
                // queue flip happens.
                // Only depots with a real owning app (depotFromApp != 0) belong in SharedDepots.
                // Writing "<depotId>"  "0" tells Steam the depot is owned by app 0, which
                // makes PICS reconcile on every boot and can re-trigger the gray-Play cascade.
                val ownedSharedDepots = sharedDepots.filterValues { it.depotFromApp != 0 }
                if (ownedSharedDepots.isNotEmpty()) {
                    appendLine("\t\"SharedDepots\"")
                    appendLine("\t{")
                    ownedSharedDepots.forEach { (depotId, info) ->
                        appendLine("\t\t\"$depotId\"\t\t\"${info.depotFromApp}\"")
                    }
                    appendLine("\t}")
                }
                val unownedShared = sharedDepots.keys - ownedSharedDepots.keys
                if (unownedShared.isNotEmpty()) {
                    Timber.w(
                        "createAppManifest: appId=%d shared depots %s have no owner (depotFromApp=0) — omitting from SharedDepots",
                        steamAppId,
                        unownedShared,
                    )
                }

                // cloud_enabled="0" in both modes: GameNative's SteamAutoCloud owns cloud sync
                // (runs on app close + manual "Cloud Sync" button; skipped only on launch in
                // real-Steam mode to avoid a launch-time conflict dialog). Letting Wine-Steam
                // also sync in real-Steam mode re-introduced the Steam Input (241100) AutoCloud
                // watcher and blocked graceful steam.exe -shutdown on cloud upload — hence 0.
                appendLine("\t\"UserConfig\"")
                appendLine("\t{")
                appendLine("\t\t\"language\"\t\t\"english\"")
                appendLine("\t\t\"cloud_enabled\"\t\t\"0\"")
                appendLine("\t\t\"BetaKey\"\t\t\"${if (installedBranch == "public") "" else escapeString(installedBranch)}\"")
                appendLine("\t}")
                appendLine("\t\"MountedConfig\" { \"language\" \"english\" }")

                appendLine("}")
            }

            // Write ACF file
            val acfFile = File(steamappsDir, "appmanifest_$steamAppId.acf")
            acfFile.writeText(acfContent)

            // Always refresh shared helper manifests: 228980 (Steamworks Common
            // Redistributables — referenced via child's SharedDepots) and 241100
            // (Steam Controller Configs — auto-installed by Steam for any game
            // using Steam Input, regardless of child declaration). Without valid
            // manifests for these, the child gets stuck in Update Queued / gray
            // Play. The writers are no-ops when PICS has no AppInfo for a helper.
            writeAllSharedAppManifests(steamappsDir, commonDir, lastOwner, sharedDepots.keys)

            // Self-check: assert the acfs we just wrote are in the shape that
            // avoids gray Play. Catches silent regressions in the writers + any
            // case where Steam immediately rewrites the file between our write
            // and the next launch.
            validateAcfShape(acfFile, expectDepotCount = regularDepots.size, label = "child $steamAppId")
            sharedHelperApps.forEach { helper ->
                val helperAcf = File(steamappsDir, "appmanifest_${helper.appId}.acf")
                if (helperAcf.isFile) {
                    validateAcfShape(
                        helperAcf,
                        expectDepotCount = -1,
                        label = "shared ${helper.appId}",
                    )
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to create ACF manifest for appId $steamAppId")
        }
    }

    private data class ResolvedSharedDepot(
        val id: Int,
        val manifestGid: Long,
        val size: Long,
        val installScript: String,
    )

    // Static installscript paths by depot id — Steam convention, stable across
    // PICS churn. Only used to populate the acf's InstallScripts block; manifest
    // GID / size are always pulled from PICS at runtime. Missing entries here
    // just mean the depot is included in InstalledDepots without an
    // InstallScripts mapping, which is fine because applySteamInstallScriptShim
    // writes HKLM registry keys that mark every installscript Run=1 anyway.
    private val sharedDepotInstallScripts: Map<Int, String> = mapOf(
        228981 to "_CommonRedist\\\\vcredist\\\\2005\\\\installscript.vdf",
        228982 to "_CommonRedist\\\\vcredist\\\\2008\\\\installscript.vdf",
        228983 to "_CommonRedist\\\\vcredist\\\\2010\\\\installscript.vdf",
        228984 to "_CommonRedist\\\\vcredist\\\\2012\\\\installscript.vdf",
        228985 to "_CommonRedist\\\\vcredist\\\\2013\\\\installscript.vdf",
        228986 to "_CommonRedist\\\\vcredist\\\\2015\\\\installscript.vdf",
        228987 to "_CommonRedist\\\\vcredist\\\\2017\\\\installscript.vdf",
        228988 to "_CommonRedist\\\\vcredist\\\\2019\\\\installscript.vdf",
        228989 to "_CommonRedist\\\\vcredist\\\\2022\\\\installscript.vdf",
        228990 to "_CommonRedist\\\\DirectX\\\\Jun2010\\\\installscript.vdf",
        229000 to "_CommonRedist\\\\DotNet\\\\3.5\\\\installscript.vdf",
        229001 to "_CommonRedist\\\\DotNet\\\\3.5 Client Profile\\\\installscript.vdf",
        229002 to "_CommonRedist\\\\DotNet\\\\4.0\\\\installscript.vdf",
        229003 to "_CommonRedist\\\\DotNet\\\\4.0 Client Profile\\\\installscript.vdf",
        229004 to "_CommonRedist\\\\DotNet\\\\4.5.2\\\\installscript.vdf",
        229005 to "_CommonRedist\\\\DotNet\\\\4.6\\\\installscript.vdf",
        229006 to "_CommonRedist\\\\DotNet\\\\4.7\\\\installscript.vdf",
        229007 to "_CommonRedist\\\\DotNet\\\\4.8\\\\installscript.vdf",
        229011 to "_CommonRedist\\\\XNA\\\\3.1\\\\installscript.vdf",
        229012 to "_CommonRedist\\\\XNA\\\\4.0\\\\installscript.vdf",
        229020 to "_CommonRedist\\\\OpenAL\\\\2.0.7.0\\\\installscript.vdf",
        229030 to "_CommonRedist\\\\PhysX\\\\8.09.04\\\\installscript.vdf",
        229031 to "_CommonRedist\\\\PhysX\\\\9.12.1031\\\\installscript.vdf",
        229032 to "_CommonRedist\\\\PhysX\\\\9.13.1220\\\\installscript.vdf",
    )

    // Resolve depots for a shared parent app (228980, 241100, etc.) by pulling
    // manifest GID + size from the parent's own PICS entry. If depotFilter is
    // non-null, only those depot IDs are kept (used when a child game declares
    // its own SharedDepots list, e.g. 228980's 228985/228989). If null, every
    // depot PICS lists for the parent is included (used for apps Steam
    // auto-installs regardless of child declaration, e.g. 241100 Steam Input).
    private fun resolveSharedAppDepots(
        sharedAppId: Int,
        depotFilter: Set<Int>?,
    ): List<ResolvedSharedDepot> {
        val sharedAppInfo = SteamService.getAppInfoOf(sharedAppId) ?: return emptyList()
        val depotIds = depotFilter ?: sharedAppInfo.depots.keys
        return depotIds.mapNotNull { depotId ->
            val depot = sharedAppInfo.depots[depotId] ?: return@mapNotNull null
            val manifest = depot.manifests["public"]
                ?: depot.manifests.values.firstOrNull()
                ?: return@mapNotNull null
            if (manifest.gid == 0L) return@mapNotNull null
            ResolvedSharedDepot(
                id = depotId,
                manifestGid = manifest.gid,
                size = manifest.size,
                installScript = sharedDepotInstallScripts[depotId] ?: "",
            )
        }
    }

    // Shared helper apps that Steam will try to update before launching any
    // game unless we pre-declare them as installed. 228980 (Steamworks Common
    // Redistributables) is referenced via the child's PICS SharedDepots block
    // (228985/228989 for VC++ redist). Without a valid acf for it, Steam
    // cascades Update Queued onto the child and grays out Play.
    //
    // 241100 (Steam Input configs) was tried here too but caused a worse bug:
    // Steam's scheduler re-runs `Reconfiguring → Staging → Committing (0 files)`
    // every 20s indefinitely while the game is running, because its content
    // lives at workshop/content/241100 (not under common/) and our synthetic
    // acf makes Steam think it should exist. Left pre-fix behavior alone: 241100
    // reconciles once and stops.
    private data class SharedHelperApp(
        val appId: Int,
        val installDir: String,
        // If null: include all depots PICS lists for this app.
        // If set: only include depots that appear in both PICS and the
        // provided set (currently only 228980 uses the child-declared set).
        val useChildDeclaredDepots: Boolean,
    )

    private val sharedHelperApps = listOf(
        SharedHelperApp(appId = 228980, installDir = "Steamworks Shared", useChildDeclaredDepots = true),
    )

    /**
     * Write appmanifest_<sharedAppId>.acf for a shared helper app. The
     * "0 bytes to download" shape makes Steam's reconcile a ~1s no-op; an
     * empty or mismatched manifest otherwise leaves the helper stuck in
     * Update Queued and cascades gray Play onto the child game.
     */
    private fun writeSharedAppManifest(
        steamappsDir: File,
        commonDir: File,
        lastOwner: String,
        helper: SharedHelperApp,
        childSharedDepotIds: Set<Int>,
    ) {
        val sharedAppId = helper.appId
        val staleAcf = File(steamappsDir, "appmanifest_$sharedAppId.acf")
        val sharedAppInfo = SteamService.getAppInfoOf(sharedAppId)
        if (sharedAppInfo == null) {
            if (staleAcf.exists()) staleAcf.delete()
            Timber.w("writeSharedAppManifest: PICS has no AppInfo for $sharedAppId — Steam may gray Play")
            return
        }

        val sharedBuildId = sharedAppInfo.branches["public"]?.buildId
            ?: sharedAppInfo.branches.values.firstOrNull()?.buildId
            ?: 0L
        if (sharedBuildId == 0L) {
            Timber.w("writeSharedAppManifest: $sharedAppId PICS has no buildid — skipping")
            return
        }

        val depotFilter = if (helper.useChildDeclaredDepots) childSharedDepotIds else null
        val presentDepots = resolveSharedAppDepots(sharedAppId, depotFilter)
        if (presentDepots.isEmpty()) {
            if (staleAcf.exists()) staleAcf.delete()
            Timber.w(
                "writeSharedAppManifest: no $sharedAppId depots resolvable from PICS (filter=%s) — deleting stale acf",
                depotFilter,
            )
            return
        }
        val presentDepotIds = presentDepots.map { it.id }.toSet()
        val presentScriptDepotIds = presentDepots.filter { it.installScript.isNotEmpty() }.map { it.id }.toSet()
        if (staleAcf.isFile) {
            val existingBuildId = parseAcfBuildId(staleAcf)
            val existingDepots = parseAcfInstalledDepotIds(staleAcf)
            val existingScripts = parseAcfInstallScriptDepotIds(staleAcf)
            val existingOwner = parseAcfLastOwner(staleAcf)
            val depotsMatch = existingDepots == presentDepotIds
            val buildIdMatch = existingBuildId == sharedBuildId
            val scriptsMatch = existingScripts == presentScriptDepotIds
            // LastOwner mismatch means the manifest was last written by a different
            // signed-in account; reusing it would attribute the install to that account
            // and break cloud/ownership checks for the current user. Force a rewrite.
            val lastOwnerMatch = existingOwner == lastOwner
            if (depotsMatch && buildIdMatch && scriptsMatch && lastOwnerMatch) {
                val updateResult = parseAcfUpdateResult(staleAcf)
                if (updateResult == 0L) return
                staleAcf.delete()
            }
        }

        val sharedInstallDir = helper.installDir
        val sharedCommonDir = File(commonDir, sharedInstallDir)
        if (!sharedCommonDir.exists()) {
            sharedCommonDir.mkdirs()
        }

        val launcherPath = "C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe"

        val acfContent = buildString {
            appendLine("\"AppState\"")
            appendLine("{")
            appendLine("\t\"appid\"\t\t\"$sharedAppId\"")
            appendLine("\t\"Universe\"\t\t\"1\"")
            appendLine("\t\"LauncherPath\"\t\t\"$launcherPath\"")
            appendLine("\t\"name\"\t\t\"${escapeString(sharedAppInfo.name.ifBlank { "App $sharedAppId" })}\"")
            appendLine("\t\"StateFlags\"\t\t\"4\"")
            appendLine("\t\"installdir\"\t\t\"${escapeString(sharedInstallDir)}\"")
            appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
            appendLine("\t\"LastPlayed\"\t\t\"0\"")
            val presentSizeOnDisk = presentDepots.sumOf { it.size }
            appendLine("\t\"SizeOnDisk\"\t\t\"$presentSizeOnDisk\"")
            appendLine("\t\"StagingSize\"\t\t\"0\"")
            appendLine("\t\"buildid\"\t\t\"$sharedBuildId\"")
            appendLine("\t\"LastOwner\"\t\t\"$lastOwner\"")
            appendLine("\t\"DownloadType\"\t\t\"1\"")
            appendLine("\t\"UpdateResult\"\t\t\"0\"")
            appendLine("\t\"BytesToDownload\"\t\t\"0\"")
            appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
            appendLine("\t\"BytesToStage\"\t\t\"0\"")
            appendLine("\t\"BytesStaged\"\t\t\"0\"")
            appendLine("\t\"TargetBuildID\"\t\t\"$sharedBuildId\"")
            appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
            appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
            appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")

            appendLine("\t\"InstalledDepots\"")
            appendLine("\t{")
            presentDepots.forEach { d ->
                appendLine("\t\t\"${d.id}\"")
                appendLine("\t\t{")
                appendLine("\t\t\t\"manifest\"\t\t\"${d.manifestGid}\"")
                appendLine("\t\t\t\"size\"\t\t\"${d.size}\"")
                appendLine("\t\t}")
            }
            appendLine("\t}")

            val scriptedDepots = presentDepots.filter { it.installScript.isNotEmpty() }
            if (scriptedDepots.isNotEmpty()) {
                appendLine("\t\"InstallScripts\"")
                appendLine("\t{")
                scriptedDepots.forEach { d ->
                    appendLine("\t\t\"${d.id}\"\t\t\"${d.installScript}\"")
                }
                appendLine("\t}")
            }

            appendLine("\t\"UserConfig\"")
            appendLine("\t{")
            appendLine("\t\t\"BetaKey\"\t\t\"public\"")
            appendLine("\t}")
            appendLine("\t\"MountedConfig\"")
            appendLine("\t{")
            appendLine("\t\t\"BetaKey\"\t\t\"public\"")
            appendLine("\t}")
            appendLine("}")
        }

        staleAcf.writeText(acfContent)
    }

    // AppIds that an older build of GameNative wrote synthetic manifests for,
    // but which have since been removed from sharedHelperApps. Delete any
    // leftover acfs so Steam doesn't keep reconciling phantom installs.
    private val retiredSharedHelperAppIds = setOf(241100)

    private fun writeAllSharedAppManifests(
        steamappsDir: File,
        commonDir: File,
        lastOwner: String,
        childSharedDepotIds: Set<Int>,
    ) {
        retiredSharedHelperAppIds.forEach { retiredId ->
            val stale = File(steamappsDir, "appmanifest_$retiredId.acf")
            if (stale.isFile && stale.delete()) {
                Timber.i("writeAllSharedAppManifests: removed stale appmanifest_%d.acf from prior build", retiredId)
            }
        }
        sharedHelperApps.forEach { helper ->
            writeSharedAppManifest(steamappsDir, commonDir, lastOwner, helper, childSharedDepotIds)
        }
    }

    private fun escapeString(input: String?): String {
        if (input == null) return ""
        return input.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private val acfBuildIdRegex = Regex("\"buildid\"\\s*\"(\\d+)\"")
    private val acfUpdateResultRegex = Regex("\"UpdateResult\"\\s*\"(\\d+)\"")
    private val acfLastOwnerRegex = Regex("\"LastOwner\"\\s*\"(\\d+)\"")

    // Matches `"<depotId>" { "manifest" "<gid>" ... }` entries inside the
    // InstalledDepots block of our own acf output. Tolerant of whitespace /
    // newlines; depot ids are ints, manifest GIDs can be long-valued.
    private val acfInstalledDepotEntryRegex = Regex(
        "\"(\\d+)\"\\s*\\{\\s*\"manifest\"\\s*\"\\d+\"",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun parseAcfBuildId(acf: File): Long {
        return try {
            val match = acfBuildIdRegex.find(acf.readText()) ?: return 0L
            match.groupValues[1].toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseAcfUpdateResult(acf: File): Long {
        return try {
            val match = acfUpdateResultRegex.find(acf.readText()) ?: return 0L
            match.groupValues[1].toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseAcfLastOwner(acf: File): String {
        return try {
            acfLastOwnerRegex.find(acf.readText())?.groupValues?.get(1).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseAcfInstalledDepotIds(acf: File): Set<Int> {
        return try {
            val text = acf.readText()
            val start = text.indexOf("\"InstalledDepots\"")
            if (start < 0) return emptySet()
            val braceOpen = text.indexOf('{', start)
            if (braceOpen < 0) return emptySet()
            // Find the matching closing brace for InstalledDepots.
            var depth = 0
            var braceClose = -1
            for (i in braceOpen until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { braceClose = i; break }
                    }
                }
            }
            if (braceClose < 0) return emptySet()
            val section = text.substring(braceOpen, braceClose)
            acfInstalledDepotEntryRegex.findAll(section)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun parseAcfInstallScriptDepotIds(acf: File): Set<Int> {
        return try {
            val text = acf.readText()
            val start = text.indexOf("\"InstallScripts\"")
            if (start < 0) return emptySet()
            val braceOpen = text.indexOf('{', start)
            if (braceOpen < 0) return emptySet()
            var depth = 0
            var braceClose = -1
            for (i in braceOpen until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { braceClose = i; break }
                    }
                }
            }
            if (braceClose < 0) return emptySet()
            val section = text.substring(braceOpen, braceClose)
            Regex("\"(\\d+)\"\\s*\"[^\"]*installscript\\.vdf\"", RegexOption.IGNORE_CASE)
                .findAll(section)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private val acfStateFlagsRegex = Regex("\"StateFlags\"\\s*\"(\\d+)\"")
    private val acfBytesToDownloadRegex = Regex("\"BytesToDownload\"\\s*\"(\\d+)\"")

    // Post-write self-check. Flags the known-bad shapes that cause gray Play:
    //  - missing file
    //  - StateFlags has Update Required (bit 2) set
    //  - BytesToDownload > 0 (Steam thinks it needs to fetch something)
    //  - InstalledDepots block empty (unless the caller explicitly expects 0)
    // Logs ERROR instead of throwing so a regression is visible in logcat
    // without breaking the launch flow — the launch was already going to fail
    // at the gray Play step anyway, and a loud ERROR beats a silent hang.
    private fun validateAcfShape(acf: File, expectDepotCount: Int, label: String) {
        if (!acf.isFile) {
            Timber.e("ACF self-check FAIL [%s]: %s missing after write", label, acf.absolutePath)
            return
        }
        val text = runCatching { acf.readText() }.getOrNull()
        if (text == null) {
            Timber.e("ACF self-check FAIL [%s]: %s unreadable", label, acf.absolutePath)
            return
        }
        val stateFlags = acfStateFlagsRegex.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val bytesToDownload = acfBytesToDownloadRegex.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val installedDepots = parseAcfInstalledDepotIds(acf)
        val problems = mutableListOf<String>()
        if (stateFlags < 0) problems += "StateFlags missing"
        else if (stateFlags and 0x2L != 0L) problems += "StateFlags=$stateFlags has Update Required bit"
        if (bytesToDownload > 0L) problems += "BytesToDownload=$bytesToDownload"
        if (expectDepotCount >= 0 && installedDepots.size != expectDepotCount) {
            problems += "InstalledDepots count=${installedDepots.size} expected=$expectDepotCount"
        } else if (expectDepotCount < 0 && installedDepots.isEmpty()) {
            problems += "InstalledDepots empty"
        }
        if (problems.isNotEmpty()) {
            Timber.e("ACF self-check FAIL [%s] %s: %s", label, acf.name, problems.joinToString("; "))
        }
    }

    private fun createSteamCommonLink(link: File, target: File) {
        // File.exists() follows symlinks, so it returns false for broken symlinks (target gone)
        // and would let createSymbolicLink below blow up with FileAlreadyExistsException on
        // the dangling link entry. Use NOFOLLOW_LINKS to detect the link itself, and replace
        // it if it points somewhere other than the intended target.
        link.parentFile?.mkdirs()
        if (Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val existingTarget = runCatching { Files.readSymbolicLink(link.toPath()) }.getOrNull()
            if (existingTarget == target.toPath()) return
            try {
                deleteTreeNoFollowSymlinks(link)
            } catch (e: Exception) {
                Timber.w(e, "createSteamCommonLink: failed to remove existing entry at ${link.absolutePath}")
                return
            }
        }
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory()) {
            return 0L
        }

        var size = 0L
        try {
            directory.walkTopDown().forEach { file ->
                if (file.isFile()) {
                    size += file.length()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calculating directory size")
        }

        return size
    }

    /** Writes steam.cfg with update-inhibit keys if missing. Both real-Steam and emu paths need it present. */
    fun ensureSteamCfg(imageFs: ImageFs, container: Container? = null) {
        // Per-container path; imageFs.wineprefix resolves through the global
        // xuser symlink which is unsafe for writes. Fallback retained for
        // legacy callers without a container in scope.
        val cfgFile = container?.let {
            File(it.rootDir, ".wine/drive_c/Program Files (x86)/Steam/steam.cfg")
        } ?: File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steam.cfg")
        if (cfgFile.exists()) return
        try {
            cfgFile.parentFile?.mkdirs()
            Files.createFile(cfgFile.toPath())
            cfgFile.writeText("BootStrapperInhibitAll=Enable\nBootStrapperForceSelfUpdate=False")
        } catch (e: Exception) {
            Timber.w(e, "ensureSteamCfg: failed to write steam.cfg")
        }
    }

    /** Logs size + mtime of the Steam binaries whose unexpected change breaks launches. Diagnostic-only. */
    fun logSteamBinaryFingerprint(imageFs: ImageFs, tag: String, container: Container? = null) {
        try {
            // Per-container path with symlink fallback for legacy callers.
            val steamDir = container?.let { File(it.rootDir, ".wine/drive_c/Program Files (x86)/Steam") }
                ?: File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
            val parts = listOf("steam.exe", "steamclient.dll", "steamclient64.dll").map { name ->
                val f = File(steamDir, name)
                if (f.exists()) "$name=${f.length()}@${fmt.format(f.lastModified())}" else "$name=MISSING"
            }
            Timber.i("SteamBinaryFingerprint[$tag] ${parts.joinToString(" ")}")
        } catch (e: Exception) {
            Timber.w(e, "logSteamBinaryFingerprint[$tag] failed")
        }
    }

    /**
     * Restores the original steam_api.dll and steam_api64.dll files from their .orig backups
     * if they exist. Does not error if backup files are not found.
     */
    fun restoreSteamApi(context: Context, appId: String) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val imageFs = ImageFs.find(context)
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        ensureSteamCfg(imageFs, container)
        logSteamBinaryFingerprint(imageFs, "restoreSteamApi:emu:$steamAppId", container)

        // Update or modify localconfig.vdf
        updateOrModifyLocalConfig(imageFs, container, steamAppId.toString(), SteamService.userSteamId!!.accountID.toString())

        skipFirstTimeSteamSetup(imageFs.rootDir, container)
        val appDirPath = SteamService.getAppDirPath(steamAppId)

        val restoredMarkerPresent = MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        val needsRestore = !restoredMarkerPresent || !verifyRestoredState(context, appDirPath)
        if (needsRestore) {
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
            MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)

            autoLoginUserChanges(imageFs, container)
            setupLightweightSteamConfig(imageFs, SteamService.userSteamId!!.accountID.toString(), container)

            putBackSteamDlls(appDirPath)
            // In bionic-Steam mode, the original executable must remain in place
            // (the launch path runs steam.exe with the game's exe as its arg);
            // don't restore the .orig swap in that mode.
            if (!container.isLaunchBionicSteam) {
                restoreOriginalExecutable(context, steamAppId)
            }
            restoreSteamclientFiles(context, steamAppId)

            MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        }

        restoreLegacyStashedOverlayFiles(container)

        // Always refresh the manifest + symlinks: multi-account switches and branch
        // changes otherwise leave stale LastOwner/buildid/installdir behind.
        createAppManifest(context, steamAppId)

        // Mark 228980 install scripts as already run so Steam doesn't re-launch
        // vcredist/DirectX installers on every boot (they race the game's MSVC loader).
        applySteamInstallScriptShim(context, steamAppId)

        // Game-specific Handling
        ensureSaveLocationsForGames(context, steamAppId, container)
    }

    fun findSteamApiDllRootFile(file: File, depth: Int): File? {
        if (depth < 0) return null
        val (files, directories) = file.walkTopDown().maxDepth(1).partition { it.isFile }

        val steamApi = files.firstOrNull {
            it.toPath().name.startsWith("steam_api", true)
            && (
                it.toPath().name.endsWith(".dll", true)
                || it.toPath().name.endsWith(".dll.orig", true)
            )
        }

        if (steamApi != null)
            return steamApi.parentFile

        return directories.filter { it != file }.firstNotNullOfOrNull { findSteamApiDllRootFile(it, depth - 1) }
    }

    fun putBackSteamDlls(appDirPath: String) {
        val rootPath = Paths.get(appDirPath)

        val dllRootFile = findSteamApiDllRootFile(rootPath.toFile(), 10)

        if (dllRootFile == null) {
            Timber.w("Failed to find steam_api.dll/steam_api64.dll on a Steam game")
            return
        }

        dllRootFile.walkTopDown().maxDepth(1).forEach { file ->
            val path = file.toPath()
            if (!file.isFile || !path.name.startsWith("steam_api", ignoreCase = true) || !path.name.endsWith(".orig", ignoreCase = true)) return@forEach

            val is64Bit = path.name.equals("steam_api64.dll.orig", ignoreCase = true)
            val is32Bit = path.name.equals("steam_api.dll.orig", ignoreCase = true)

            if (!is32Bit && !is64Bit) return@forEach

            if (is64Bit || is32Bit) {
                try {
                    val dllName = if (is64Bit) "steam_api64.dll" else "steam_api.dll"
                    val originalPath = path.parent.resolve(dllName)
                    Timber.i("Found ${path.name} at ${path.absolutePathString()}, restoring...")

                    // Delete the current DLL if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(path, originalPath)

                    Timber.i("Restored $dllName from backup")
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore ${path.name} from backup")
                }
            }
        }
    }

    /**
     * Restores the original executable files from their .original.exe backups
     * if they exist. Does not error if backup files are not found.
     */
    fun restoreOriginalExecutable(context: Context, steamAppId: Int) {
        Timber.i("Starting restoreOriginalExecutable for appId: $steamAppId")
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        Timber.i("Checking directory: $appDirPath")
        var restoredCount = 0

        // Per-container path; imageFs.wineprefix resolves through the global
        // xuser symlink which is unsafe.
        val container = ContainerUtils.getContainer(context, "STEAM_$steamAppId")
        val dosDevicesPath = File(container.rootDir, ".wine/dosdevices/a:")

        dosDevicesPath.walkTopDown().maxDepth(10)
            .filter { it.isFile && it.name.endsWith(".original.exe", ignoreCase = true) }
            .forEach { file ->
                try {
                    val origPath = file.toPath()
                    val originalPath = origPath.parent.resolve(origPath.name.removeSuffix(".original.exe"))
                    Timber.i("Found ${origPath.name} at ${origPath.absolutePathString()}, restoring...")

                    // Delete the current exe if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(origPath, originalPath)

                    Timber.i("Restored ${originalPath.fileName} from backup")
                    restoredCount++
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore ${file.name} from backup")
                }
            }

        Timber.i("Finished restoreOriginalExecutable for appId: $steamAppId. Restored $restoredCount executable(s)")
    }

    /**
     * Copy GSE saves into Steam userdata only when the next launch is real Steam.
     * Running this in OFF mode would move GSE's own saves out from under Goldberg.
     */
    fun migrateGSESavesToSteamUserdata(context: Context, appId: Int, isLaunchRealSteam: Boolean = true) {
        if (!isLaunchRealSteam) return
        val accountId = SteamService.userSteamId?.accountID?.toInt()
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }

        if (accountId == null) {
            Timber.tag("migrateGSESavesToSteamUserdata").w("Cannot migrate GSE saves: no Steam account ID available")
            return
        }

        // Per-container path; imageFs.rootDir + ImageFs.WINEPREFIX resolves through
        // the global xuser symlink which is unsafe for writes.
        val container = ContainerUtils.getContainer(context, "STEAM_$appId")
        val winePrefix = File(container.rootDir, ".wine")

        val gseDir = File(winePrefix, "drive_c/users/xuser/AppData/Roaming/GSE Saves/$appId")
        val steamUserdataDir = File(winePrefix, "drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId")

        fun isDirectoryEmpty(file: File): Boolean {
            return file.isDirectory && file.list()?.isEmpty() ?: true
        }

        if (
            !gseDir.exists() ||
            !gseDir.isDirectory ||
            isDirectoryEmpty(gseDir) // No files inside gseDir
        ) {
            Timber.tag("migrateGSESavesToSteamUserdata").d("No GSE save directory found for appId=$appId")
            return
        }

        Timber.tag("migrateGSESavesToSteamUserdata").i("Starting GSE Saves Migration for appId=$appId")

        if (!steamUserdataDir.exists()) {
            try {
                Files.createDirectories(steamUserdataDir.toPath())
                Timber.tag("migrateGSESavesToSteamUserdata").i("Created Steam userdata directory: ${steamUserdataDir.absolutePath}")
            } catch (e: IOException) {
                Timber.tag("migrateGSESavesToSteamUserdata").e(e, "Failed to create Steam userdata directory")
                return
            }
        }

        var migratedCount = 0
        var migrationFailed = false

        gseDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = gseDir.toPath().relativize(file.toPath())
                val targetFile = steamUserdataDir.toPath().resolve(relativePath)
                try {
                    Files.createDirectories(targetFile.parent)

                    val fileTimestamp = file.lastModified()

                    // As Files.move use linux rename syscall (or simply mv command we know, no need to manually remove the target file)
                    Files.move(
                        file.toPath(),
                        targetFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE   // will throw if the FS can’t guarantee atomicity
                    )

                    // Preserve file timestamp
                    targetFile.setLastModifiedTime(FileTime.fromMillis(fileTimestamp))

                    Timber.tag("migrateGSESavesToSteamUserdata").i("Migrated ${file.name} from GSE saves to Steam userdata")
                    migratedCount++
                } catch (e: Exception) {
                    migrationFailed = true
                    Timber.tag("migrateGSESavesToSteamUserdata").w(e, "Failed to migrate ${file.name}")
                }
            }

        if (!migrationFailed) {
            gseDir.deleteRecursively()
        }

        Timber.tag("migrateGSESavesToSteamUserdata").i("Migration completed for appId=$appId. Migrated $migratedCount file(s)")
    }

    /**
     * Reverse of [migrateGSESavesToSteamUserdata]: when a user toggles Launch
     * Steam Client OFF, move saves from Steam/userdata back to GSE Saves so
     * Goldberg can find them.
     */
    fun migrateSteamUserdataToGSESaves(context: Context, appId: Int, isLaunchRealSteam: Boolean = false) {
        if (isLaunchRealSteam) return
        val accountId = SteamService.userSteamId?.accountID?.toInt()
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }

        if (accountId == null) {
            Timber.w("migrateSteamUserdataToGSESaves: no Steam account ID available")
            return
        }

        // Per-container path; imageFs.rootDir + ImageFs.WINEPREFIX resolves through
        // the global xuser symlink which is unsafe.
        val container = ContainerUtils.getContainer(context, "STEAM_$appId")
        val winePrefix = File(container.rootDir, ".wine")

        val steamUserdataDir = File(winePrefix, "drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId")
        val gseDir = File(winePrefix, "drive_c/users/xuser/AppData/Roaming/GSE Saves/$appId")

        fun isDirectoryEmpty(file: File): Boolean {
            return file.isDirectory && file.list()?.isEmpty() ?: true
        }

        if (
            !steamUserdataDir.exists() ||
            !steamUserdataDir.isDirectory ||
            isDirectoryEmpty(steamUserdataDir)
        ) {
            return
        }

        if (!gseDir.exists()) {
            try {
                Files.createDirectories(gseDir.toPath())
            } catch (e: IOException) {
                Timber.e(e, "migrateSteamUserdataToGSESaves: failed to create GSE Saves dir")
                return
            }
        }

        var migratedCount = 0
        var migrationFailed = false

        steamUserdataDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = steamUserdataDir.toPath().relativize(file.toPath())
                val targetFile = gseDir.toPath().resolve(relativePath)
                try {
                    Files.createDirectories(targetFile.parent)
                    val ts = file.lastModified()
                    Files.move(
                        file.toPath(),
                        targetFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    targetFile.setLastModifiedTime(FileTime.fromMillis(ts))
                    migratedCount++
                } catch (e: Exception) {
                    migrationFailed = true
                    Timber.w(e, "migrateSteamUserdataToGSESaves: failed to migrate %s", file.name)
                }
            }

        if (!migrationFailed) {
            steamUserdataDir.deleteRecursively()
        }
    }

    /**
     * Symlink-safe recursive delete. Kotlin's `File.deleteRecursively()` follows
     * symbolic links to directories and deletes the files on the *other* side,
     * which is catastrophic when the tree contains `steamapps/common/<installdir>`
     * symlinks pointing at the real game-files directory on the GameNative side.
     *
     * Java's NIO `Files.walkFileTree` does NOT follow symlinks by default — a
     * symlink to a directory is visited as a file, and `Files.delete` on it
     * unlinks the symlink without touching the target. This is the behaviour we
     * want: tear down the extracted Steam prefix but leave the actual game
     * content the symlinks point to alone.
     */
    internal fun deleteTreeNoFollowSymlinks(root: File) {
        if (!root.exists() && !java.nio.file.Files.isSymbolicLink(root.toPath())) return
        val rootPath = root.toPath()
        java.nio.file.Files.walkFileTree(rootPath, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): java.nio.file.FileVisitResult {
                // Broken symlink or permission issue — unlink and keep going.
                try { java.nio.file.Files.deleteIfExists(file) } catch (_: Exception) {}
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                java.nio.file.Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    fun cleanupExtractedSteamFiles(context: Context, container: Container? = null) {
        try {
            // Per-container path; imageFs.WINEPREFIX resolves through the
            // global xuser symlink which is unsafe for writes.
            val steamDir = container?.let { File(it.rootDir, ".wine/drive_c/Program Files (x86)/Steam") }
                ?: File(ImageFs.find(context).rootDir, ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam")
            if (!steamDir.exists()) return
            val steamExe = File(steamDir, "steam.exe")
            if (!steamExe.exists()) return
            deleteTreeNoFollowSymlinks(steamDir)
        } catch (e: Exception) {
            Timber.w(e, "cleanupExtractedSteamFiles failed")
        }
    }

    /**
     * Removes Steam userdata for a phantom AppID across every logged-in user. When a
     * synthetic appmanifest_<id>.acf was written in a past session, Steam registers
     * an AutoCloud watch for that app and on shutdown blocks exit until every
     * watched file (78 Steam Controller configs for 241100) round-trips the cloud.
     * Deleting userdata/<steamId>/<phantomAppId>/ breaks the association so
     * shutdown completes promptly for subsequent real-Steam launches.
     */
    fun purgePhantomAppUserdata(imageFs: ImageFs, phantomAppId: String, container: Container? = null) {
        try {
            // Per-container path with symlink fallback for legacy callers.
            val userdataRoot = container?.let { File(it.rootDir, ".wine/drive_c/Program Files (x86)/Steam/userdata") }
                ?: File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/userdata")
            if (!userdataRoot.isDirectory) return
            val victims = userdataRoot.listFiles { f -> f.isDirectory }?.mapNotNull { userDir ->
                File(userDir, phantomAppId).takeIf { it.exists() }
            }.orEmpty()
            if (victims.isEmpty()) return
            victims.forEach { dir ->
                deleteTreeNoFollowSymlinks(dir)
                Timber.i("purgePhantomAppUserdata: removed ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.w(e, "purgePhantomAppUserdata[$phantomAppId] failed")
        }
    }

    /**
     * Sibling folder "steam_settings" + empty "offline.txt" file, no-ops if they already exist.
     */
    private fun ensureSteamSettings(context: Context, dllPath: Path, appId: String, ticketBase64: String? = null, isOffline: Boolean = false) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val steamDir = dllPath.parent
        Files.createDirectories(steamDir)
        val appIdFileUpper = dllPath.parent.resolve("steam_appid.txt")
        if (Files.notExists(appIdFileUpper)) {
            Files.createFile(appIdFileUpper)
            appIdFileUpper.toFile().writeText(steamAppId.toString())
        }
        val settingsDir = dllPath.parent.resolve("steam_settings")
        if (Files.notExists(settingsDir)) {
            Files.createDirectories(settingsDir)
        }
        val appIdFile = settingsDir.resolve("steam_appid.txt")
        if (Files.notExists(appIdFile)) {
            Files.createFile(appIdFile)
            appIdFile.toFile().writeText(steamAppId.toString())
        }
        val depotsFile = settingsDir.resolve("depots.txt")
        if (Files.exists(depotsFile)) {
            Files.delete(depotsFile)
        }
        SteamService.getInstalledDepotsOf(steamAppId)?.sorted()?.let { depotsList ->
            Files.createFile(depotsFile)
            depotsFile.toFile().writeText(depotsList.joinToString(System.lineSeparator()))
        }

        val configsIni = settingsDir.resolve("configs.user.ini")
        val accountName   = PrefManager.username
        val accountSteamId = SteamService.userSteamId?.convertToUInt64()?.toString()
            ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }?.toString()
            ?: "0"
        val accountId = SteamService.userSteamId?.accountID
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
            ?: 0L
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val language = runCatching {
            (container.getExtra("language", null)
                ?: container.javaClass.getMethod("getLanguage").invoke(container) as? String)
                ?: "english"
        }.getOrDefault("english").lowercase()
        val useSteamInput = container.getExtra("useSteamInput", "false").toBoolean()

        // Get appInfo to check if saveFilePatterns exist (used for both user and app configs)
        val appInfo = getAppInfoOf(steamAppId)

        val iniContent = buildString {
            appendLine("[user::general]")
            appendLine("account_name=$accountName")
            appendLine("account_steamid=$accountSteamId")
            appendLine("language=$language")
            if (!ticketBase64.isNullOrEmpty()) {
                appendLine("ticket=$ticketBase64")
            }

            // ensureSteamSettings only runs in the emulated-Steam launch path, so
            // pull any leftover userdata back into GSE Saves for Goldberg.
            migrateSteamUserdataToGSESaves(context, steamAppId, isLaunchRealSteam = false)

            // Add [user::saves] section
            val steamUserDataPath = "C:\\Program Files (x86)\\Steam\\userdata\\$accountId"
            appendLine()
            appendLine("[user::saves]")
            appendLine("local_save_path=$steamUserDataPath")
        }

        if (Files.notExists(configsIni)) Files.createFile(configsIni)
        configsIni.toFile().writeText(iniContent)

        val appIni = settingsDir.resolve("configs.app.ini")
        val dlcIds = SteamService.getInstalledDlcDepotsOf(steamAppId)
        val dlcApps = SteamService.getDownloadableDlcAppsOf(steamAppId)
        val hiddenDlcApps = SteamService.getHiddenDlcAppsOf(steamAppId)
        val appendedDlcIds = mutableListOf<Int>()

        val forceDlc = container.isForceDlc()
        val appIniContent = buildString {
            appendLine("[app::dlcs]")
            appendLine("unlock_all=${if (forceDlc) 1 else 0}")
            dlcIds?.sorted()?.forEach {
                appendLine("$it=dlc$it")
                appendedDlcIds.add(it)
            }

            dlcApps?.forEach { dlcApp ->
                val installedDlcApp = SteamService.getInstalledApp(dlcApp.id)
                if (installedDlcApp != null && !appendedDlcIds.contains(dlcApp.id)) {
                    appendLine("${dlcApp.id}=dlc${dlcApp.id}")
                    appendedDlcIds.add(dlcApp.id)
                }
            }

            // only add hidden dlc apps if not found in appendedDlcIds
            hiddenDlcApps?.forEach { hiddenDlcApp ->
                if (!appendedDlcIds.contains(hiddenDlcApp.id) &&
                    // only add hidden dlc apps if it is not a DLC of the main app
                    appInfo!!.depots.filter { (_, depot) -> depot.dlcAppId == hiddenDlcApp.id }.size <= 1) {
                    appendLine("${hiddenDlcApp.id}=dlc${hiddenDlcApp.id}")
                }
            }

            // Add app paths and cloud save config sections if appInfo exists
            if (appInfo != null) {
                // Some games required this path to be setup for detecting dlc, e.g. Vampire Survivors
                val gameDir = File(SteamService.getAppDirPath(steamAppId))
                val gameName = gameDir.name
                val actualInstallDir = appInfo.config.installDir.ifEmpty { gameName }
                appendLine()
                appendLine("[app::paths]")
                appendLine("$steamAppId=./steamapps/common/$actualInstallDir")

                // Setup for cloud save
                appendLine()
                append(generateCloudSaveConfig(appInfo))
            }
        }

        if (Files.notExists(appIni)) Files.createFile(appIni)
        appIni.toFile().writeText(appIniContent)

        val mainIni = settingsDir.resolve("configs.main.ini")

        val steamOfflineMode = container.isSteamOfflineMode()
        val useOfflineConfig = steamOfflineMode || isOffline
        val mainIniContent = buildString {
            appendLine("[main::connectivity]")
            appendLine("disable_lan_only=${if (useOfflineConfig) 0 else 1}")
            if (useOfflineConfig) {
                appendLine("offline=1")
            }
        }

        if (Files.notExists(mainIni)) Files.createFile(mainIni)
        mainIni.toFile().writeText(mainIniContent)

        val controllerDir = settingsDir.resolve("controller")
        if (useSteamInput) {
            val controllerVdfText = SteamService.resolveSteamControllerVdfText(steamAppId)
            if (!controllerVdfText.isNullOrEmpty()) {
                runCatching {
                    SteamControllerVdfUtils.generateControllerConfig(controllerVdfText, controllerDir)
                }.onFailure { error ->
                    Timber.w(error, "Failed to generate controller config for $steamAppId")
                }
            }
        } else {
            runCatching {
                if (Files.exists(controllerDir)) {
                    controllerDir.toFile().deleteRecursively()
                }
            }.onFailure { error ->
                Timber.w(error, "Failed to delete controller config for $steamAppId")
            }
        }

        // Write supported languages list
        val supportedLanguagesFile = settingsDir.resolve("supported_languages.txt")
        if (Files.notExists(supportedLanguagesFile)) {
            Files.createFile(supportedLanguagesFile)
        }
        val supportedLanguages = listOf(
            "arabic",
            "bulgarian",
            "schinese",
            "tchinese",
            "czech",
            "danish",
            "dutch",
            "english",
            "finnish",
            "french",
            "german",
            "greek",
            "hungarian",
            "italian",
            "japanese",
            "koreana",
            "norwegian",
            "polish",
            "portuguese",
            "brazilian",
            "romanian",
            "russian",
            "spanish",
            "latam",
            "swedish",
            "thai",
            "turkish",
            "ukrainian",
            "vietnamese",
        )
        supportedLanguagesFile.toFile().writeText(supportedLanguages.joinToString("\n"))
    }

    /**
     * Generates cloud save configuration sections for configs.app.ini
     * Returns empty string if no Windows save patterns are found
     */
    private fun generateCloudSaveConfig(appInfo: SteamApp): String {
        // Filter to only Windows save patterns
        val windowsPatterns = appInfo.ufs.saveFilePatterns.filter { it.root.isWindows }

        return buildString {
            if (windowsPatterns.isNotEmpty()) {
                appendLine("[app::cloud_save::general]")
                appendLine("create_default_dir=1")
                appendLine("create_specific_dirs=1")
                appendLine()
                appendLine("[app::cloud_save::win]")
                val uniqueDirs = LinkedHashSet<String>()
                windowsPatterns.forEach { pattern ->
                    val root = if (pattern.root.name == "GameInstall") "gameinstall" else pattern.root.name
                    val path = pattern.path
                        .replace("{64BitSteamID}", "{::64BitSteamID::}")
                        .replace("{Steam3AccountID}", "{::Steam3AccountID::}")
                    uniqueDirs.add("{::$root::}/$path")
                }

                uniqueDirs.forEachIndexed { index, dir ->
                    appendLine("dir${index + 1}=$dir")
                }
            }
        }
    }

    private fun convertToWindowsPath(unixPath: String): String {
        // Find the drive_c component and convert everything after to Windows semantics
        val marker = "/drive_c/"
        val idx = unixPath.indexOf(marker)
        val tail = if (idx >= 0) {
            unixPath.substring(idx + marker.length)
        } else if (unixPath.contains("drive_c/")) {
            val i = unixPath.indexOf("drive_c/")
            unixPath.substring(i + "drive_c/".length)
        } else {
            // Fallback: best-effort replacement of leading wineprefix
            unixPath
        }
        val windowsTail = tail.replace('/', '\\')
        return "C:" + if (windowsTail.startsWith("\\")) windowsTail else "\\" + windowsTail
    }

    /**
     * Gets the Android user-editable device name or falls back to [HardwareUtils.getMachineName]
     */
    fun getMachineName(context: Context): String {
        return try {
            // Try different methods to get device name
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.System.getString(context.contentResolver, "device_name")
                // ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                // ?: BluetoothAdapter.getDefaultAdapter()?.name
                ?: HardwareUtils.getMachineName() // Fallback to machine name if all else fails
        } catch (e: Exception) {
            HardwareUtils.getMachineName() // Return machine name as last resort
        }
    }

    // Set LoginID to a non-zero value if you have another client connected using the same account,
    // the same private ip, and same public ip.
    // source: https://github.com/Longi94/JavaSteam/blob/08690d0aab254b44b0072ed8a4db2f86d757109b/javasteam-samples/src/main/java/in/dragonbra/javasteamsamples/_000_authentication/SampleLogonAuthentication.java#L146C13-L147C56
    /**
     * This ID is unique to the device and app combination
     */
    @SuppressLint("HardwareIds")
    fun getUniqueDeviceId(context: Context): Int {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        return androidId.hashCode()
    }

    private fun skipFirstTimeSteamSetup(rootDir: File?, container: Container? = null) {
        // Per-container path when available — see autoLoginUserChanges for the
        // cascade-corruption rationale.
        val systemRegFile = container?.let { File(it.rootDir, ".wine/system.reg") }
            ?: File(rootDir, ImageFs.WINEPREFIX + "/system.reg")
        val redistributables = listOf(
            "DirectX\\Jun2010" to "DXSetup",              // DirectX Jun 2010
            ".NET\\3.5" to "3.5 SP1",              // .NET 3.5
            ".NET\\3.5 Client Profile" to "3.5 Client Profile SP1",
            ".NET\\4.0" to "4.0",                   // .NET 4.0
            ".NET\\4.0 Client Profile" to "4.0 Client Profile",
            ".NET\\4.5.2" to "4.5.2",
            ".NET\\4.6" to "4.6",
            ".NET\\4.7" to "4.7",
            ".NET\\4.8" to "4.8",
            "XNA\\3.0" to "3.0",                   // XNA 3.0
            "XNA\\3.1" to "3.1",
            "XNA\\4.0" to "4.0",
            "OpenAL\\2.0.7.0" to "2.0.7.0",               // OpenAL 2.0.7.0
            ".NET\\4.5.1" to "4.5.1",   // some Unity 5 titles
            ".NET\\4.6.1" to "4.6.1",   // Space Engineers, Far Cry 5 :contentReference[oaicite:1]{index=1}
            ".NET\\4.6.2" to "4.6.2",
            ".NET\\4.7.1" to "4.7.1",
            ".NET\\4.7.2" to "4.7.2",   // common fix loops :contentReference[oaicite:2]{index=2}
            ".NET\\4.8.1" to "4.8.1",
        )

        WineRegistryEditor(systemRegFile).use { registryEditor ->
            redistributables.forEach { (subPath, valueName) ->
                registryEditor.setDwordValue(
                    "Software\\Valve\\Steam\\Apps\\CommonRedist\\$subPath",
                    valueName,
                    1,
                )
                registryEditor.setDwordValue(
                    "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\$subPath",
                    valueName,
                    1,
                )
            }
        }
    }

    fun fetchDirect3DMajor(steamAppId: Int, callback: (Int) -> Unit) {
        // Build a single Cargo query: SELECT API.direct3d_versions WHERE steam_appid="<appId>"
        Timber.i("[DX Fetch] Starting fetchDirect3DMajor for appId=%d", steamAppId)
        val where = URLEncoder.encode("Infobox_game.Steam_AppID HOLDS \"$steamAppId\"", "UTF-8")
        val url =
            "https://pcgamingwiki.com/w/api.php" +
                    "?action=cargoquery" +
                    "&tables=Infobox_game,AP" +
                    "I&join_on=Infobox_game._pageID=API._pageID" +
                    "&fields=API.Direct3D_versions" +
                    "&where=$where" +
                    "&format=json"

        Timber.i("[DX Fetch] Starting fetchDirect3DMajor for query=%s", url)

        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(-1)

            override fun onResponse(call: Call, res: Response) {
                res.use {
                    try {
                        val body = it.body?.string() ?: run { callback(-1); return }
                        Timber.i("[DX Fetch] Raw body fetchDirect3DMajor for body=%s", body)
                        val arr = JSONObject(body)
                            .optJSONArray("cargoquery") ?: run { callback(-1); return }

                        // There should be at most one row; take the first.
                        val raw = arr.optJSONObject(0)
                            ?.optJSONObject("title")
                            ?.optString("Direct3D versions")
                            ?.trim() ?: ""

                        Timber.i("[DX Fetch] Raw fetchDirect3DMajor for raw=%s", raw)

                        // Extract highest DX major number present.
                        val dx = Regex("\\b(9|10|11|12)\\b")
                            .findAll(raw)
                            .map { it.value.toInt() }
                            .maxOrNull() ?: -1

                        Timber.i("[DX Fetch] dx fetchDirect3DMajor is dx=%d", dx)

                        callback(dx)
                    } catch (e: Exception){
                        callback(-1)
                    }
                }
            }
        })
    }

    fun updateOrModifyLocalConfig(imageFs: ImageFs, container: Container, appId: String, steamUserId64: String) {
        try {
            val exeCommandLine = container.execArgs

            // Per-container path; imageFs.wineprefix resolves through the global
            // xuser symlink which is unsafe for writes.
            val steamPath = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam")

            // Create necessary directories
            val userDataPath = File(steamPath, "userdata/$steamUserId64")
            val configPath = File(userDataPath, "config")
            configPath.mkdirs()

            val localConfigFile = File(configPath, "localconfig.vdf")

            if (localConfigFile.exists()) {
                val vdfContent = FileUtils.readFileAsString(localConfigFile.absolutePath)
                val vdfData = KeyValue.loadFromString(vdfContent!!)!!
                val app = vdfData["Software"]["Valve"]["Steam"]["apps"][appId]
                val option = app.children.firstOrNull { it.name == "LaunchOptions" }
                if (option != null) {
                    option.value = exeCommandLine.orEmpty()
                } else {
                    app.children.add(KeyValue("LaunchOptions", exeCommandLine))
                }

                // Suppress Wine-Steam's per-app cloud sync. GameNative's SteamAutoCloud handles
                // cloud (runs on closeApp + manual sync); letting Wine-Steam also sync
                // blocked graceful shutdown on cloud upload and resurrected the Steam Input
                // (241100) AutoCloud watcher that purgePhantomAppUserdata works around.
                //
                // "cloudenabled" (no underscore) is the canonical per-app key documented in
                // community reverse-engineering (l3laze/Steam-Data, l3laze/SteamConfig) and
                // is what the Steam GUI writes when the user unchecks cloud for a game.
                // "cloud_enabled" (with underscore) is retained as belt-and-suspenders in
                // case a client build reads the underscored variant.
                setOrReplaceKey(app, "cloudenabled", "0")
                setOrReplaceKey(app, "cloud_enabled", "0")
                setOrReplaceKey(app, "cce", "0")

                // Wipe AutoCloud reconciliation state left over from a prior run where
                // cloud_enabled was "1". Wine-Steam uses last_sync_state + autocloud
                // lastlaunch/lastexit to decide whether to show "Synchronizing cloud
                // saves" on startup and block shutdown on upload; flipping cloud_enabled
                // to "0" alone doesn't clear those, so the client keeps reconciling
                // ghosts on every launch. GameNative's SteamAutoCloud owns cloud now, so
                // Wine-Steam shouldn't retain any sync bookkeeping for this app.
                app.children.removeAll { it.name == "last_sync_state" || it.name == "autocloud" }

                vdfData.saveToFile(localConfigFile, false)
            } else {
                val vdfData = KeyValue(name = "UserLocalConfigStore")
                val option = KeyValue("LaunchOptions", exeCommandLine)
                val software = KeyValue("Software")
                val valve = KeyValue("Valve")
                val steam = KeyValue("Steam")
                val apps = KeyValue("apps")
                val app = KeyValue(appId)

                app.children.add(option)
                app.children.add(KeyValue("cloudenabled", "0"))
                app.children.add(KeyValue("cloud_enabled", "0"))
                app.children.add(KeyValue("cce", "0"))
                apps.children.add(app)
                steam.children.add(apps)
                valve.children.add(steam)
                software.children.add(valve)
                vdfData.children.add(software)

                vdfData.saveToFile(localConfigFile, false)
            }

            // Mirror the per-app cloud-off state into sharedconfig.vdf. Steam keeps
            // two per-app cloud flags: one in localconfig.vdf (UserLocalConfigStore)
            // and one in sharedconfig.vdf (UserRoamingConfigStore/Software/Valve/Steam/Apps/<id>).
            // Different client versions read the roaming vs. local copy at different
            // points in the login flow, so writing only to localconfig leaves a live
            // "cloudenabled=1" (or absent=default-on) in sharedconfig that still drives
            // the "Synchronizing cloud saves" dialog on startup.
            writeSharedConfigCloudDisabled(steamPath, steamUserId64, appId)

            val userLanguage = container.language
            val steamappsDir = File(steamPath, "steamapps")
            val appManifestFile = File(steamappsDir, "appmanifest_$appId.acf")

            if (appManifestFile.exists()) {
                val manifestContent = FileUtils.readFileAsString(appManifestFile.absolutePath)
                val manifestData = KeyValue.loadFromString(manifestContent!!)!!

                val userConfig = manifestData.children.firstOrNull { it.name == "UserConfig" }
                if (userConfig != null) {
                    val languageKey = userConfig.children.firstOrNull { it.name == "language" }
                    if (languageKey != null) {
                        languageKey.value = userLanguage
                    } else {
                        userConfig.children.add(KeyValue("language", userLanguage))
                    }
                } else {
                    val newUserConfig = KeyValue("UserConfig")
                    newUserConfig.children.add(KeyValue("language", userLanguage))
                    manifestData.children.add(newUserConfig)
                }

                val mountedConfig = manifestData.children.firstOrNull { it.name == "MountedConfig" }
                if (mountedConfig != null) {
                    val languageKey = mountedConfig.children.firstOrNull { it.name == "language" }
                    if (languageKey != null) {
                        languageKey.value = userLanguage
                    } else {
                        mountedConfig.children.add(KeyValue("language", userLanguage))
                    }
                } else {
                    val newMountedConfig = KeyValue("MountedConfig")
                    newMountedConfig.children.add(KeyValue("language", userLanguage))
                    manifestData.children.add(newMountedConfig)
                }

                manifestData.saveToFile(appManifestFile, false)
                Timber.i("Updated app manifest language to $userLanguage for appId $appId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update or modify local config")
        }
    }

    private fun writeSharedConfigCloudDisabled(steamPath: File, steamUserId64: String, appId: String) {
        try {
            val sharedConfigFile = File(steamPath, "userdata/$steamUserId64/7/remote/sharedconfig.vdf")
            sharedConfigFile.parentFile?.mkdirs()

            val root = if (sharedConfigFile.exists()) {
                val content = FileUtils.readFileAsString(sharedConfigFile.absolutePath)
                KeyValue.loadFromString(content!!) ?: KeyValue(name = "UserRoamingConfigStore")
            } else {
                KeyValue(name = "UserRoamingConfigStore")
            }

            val software = getOrCreateChild(root, "Software")
            val valve = getOrCreateChild(software, "Valve")
            val steam = getOrCreateChild(valve, "Steam")
            // Both casings appear in the wild ("Apps" vs. "apps"); prefer "Apps" per
            // the documented UserRoamingConfigStore schema but don't duplicate if the
            // lowercase variant already exists in the file Steam wrote.
            val apps = steam.children.firstOrNull { it.name.equals("Apps", ignoreCase = true) }
                ?: KeyValue("Apps").also { steam.children.add(it) }
            val app = getOrCreateChild(apps, appId)
            setOrReplaceKey(app, "cloudenabled", "0")

            root.saveToFile(sharedConfigFile, false)
        } catch (e: Exception) {
            Timber.w(e, "Failed to write cloudenabled=0 to sharedconfig.vdf for appId=$appId")
        }
    }

    data class RemoteCacheFile(
        val relativePath: String,
        val size: Long,
        val timeSeconds: Long,
        val shaHex: String,
    )

    /**
     * Writes <userdata>/<sid>/<appId>/remotecache.vdf so Wine-Steam's ISteamRemoteStorage
     * reports the cloud files to the game. With cloudenabled=0, Wine-Steam does not
     * rescan the remote/ directory on startup; it trusts remotecache.vdf. Without this
     * write, SDK-cloud games (e.g. Dead Cells) see FileExists=false on their saves and
     * load a blank state even though the files are on disk.
     */
    fun writeRemoteCacheVdf(
        remoteDir: File,
        appId: Int,
        changeNumber: Long,
        files: List<RemoteCacheFile>,
    ) {
        try {
            val userAppDir = remoteDir.parentFile ?: return
            if (!userAppDir.exists()) userAppDir.mkdirs()
            val vdfFile = File(userAppDir, "remotecache.vdf")

            val root = KeyValue(appId.toString())
            root.children.add(KeyValue("ChangeNumber", changeNumber.toString()))
            root.children.add(KeyValue("ostype", "0"))

            for (entry in files) {
                val key = entry.relativePath.replace('/', '\\')
                val node = KeyValue(key)
                node.children.add(KeyValue("root", "0"))
                node.children.add(KeyValue("size", entry.size.toString()))
                node.children.add(KeyValue("localtime", entry.timeSeconds.toString()))
                node.children.add(KeyValue("time", entry.timeSeconds.toString()))
                node.children.add(KeyValue("remotetime", entry.timeSeconds.toString()))
                node.children.add(KeyValue("sha", entry.shaHex))
                node.children.add(KeyValue("syncstate", "1"))
                node.children.add(KeyValue("persiststate", "0"))
                node.children.add(KeyValue("platformstosync2", "-1"))
                root.children.add(node)
            }

            root.saveToFile(vdfFile, false)
            Timber.i("Wrote remotecache.vdf for appId=$appId (${files.size} file(s), ChangeNumber=$changeNumber)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to write remotecache.vdf for appId=$appId")
        }
    }

    private fun getOrCreateChild(parent: KeyValue, name: String): KeyValue {
        return parent.children.firstOrNull { it.name == name }
            ?: KeyValue(name).also { parent.children.add(it) }
    }

    private fun setOrReplaceKey(parent: KeyValue, name: String, value: String) {
        val existing = parent.children.firstOrNull { it.name == name }
        if (existing != null) {
            existing.value = value
        } else {
            parent.children.add(KeyValue(name, value))
        }
    }

    fun getSteamId64(): Long? {
        return SteamService.userSteamId?.convertToUInt64()?.toLong()
            ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }
    }

    fun getSteam3AccountId(): Long? {
        return SteamService.userSteamId?.accountID?.toLong()
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
    }

    /**
     * Ensures save locations for games that require special handling (e.g., symlinks)
     * This function checks if the current game needs any save location mappings
     * and applies them automatically.
     *
     * Supports placeholders in paths:
     * - {64BitSteamID} - Replaced with the user's 64-bit Steam ID
     * - {Steam3AccountID} - Replaced with the user's Steam3 account ID
     */
    fun ensureSaveLocationsForGames(context: Context, steamAppId: Int, container: Container) {
        val mapping = SpecialGameSaveMapping.registry.find { it.appId == steamAppId } ?: return

        try {
            // safe accessors fall back to PrefManager — match siblings (SteamAutoCloud, SaveFilePattern)
            val accountId = getSteam3AccountId() ?: 0L
            val steamId64 = getSteamId64()?.toString() ?: "0"
            val steam3AccountId = accountId.toString()

            val basePath = mapping.pathType.toAbsPath(container, steamAppId, accountId)

            // Substitute placeholders in paths
            val sourceRelativePath = mapping.sourceRelativePath
                .replace("{64BitSteamID}", steamId64)
                .replace("{Steam3AccountID}", steam3AccountId)
            val targetRelativePath = mapping.targetRelativePath
                .replace("{64BitSteamID}", steamId64)
                .replace("{Steam3AccountID}", steam3AccountId)

            val sourcePath = File(basePath, sourceRelativePath)
            val targetPath = File(basePath, targetRelativePath)

            if (!sourcePath.exists()) {
                Timber.i("[${mapping.description}] Source save folder does not exist yet: ${sourcePath.absolutePath}")
                return
            }

            if (targetPath.exists()) {
                if (Files.isSymbolicLink(targetPath.toPath())) {
                    Timber.i("[${mapping.description}] Symlink already exists: ${targetPath.absolutePath}")
                    return
                } else {
                    Timber.w("[${mapping.description}] Target path exists but is not a symlink: ${targetPath.absolutePath}")
                    return
                }
            }

            targetPath.parentFile?.mkdirs()

            Files.createSymbolicLink(targetPath.toPath(), sourcePath.toPath())
            Timber.i("[${mapping.description}] Created symlink: ${targetPath.absolutePath} -> ${sourcePath.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "[${mapping.description}] Failed to create save location symlink")
        }
    }

    /**
     * Recommended SDK-cloud save subdir for a known game. Sourced from the Ludusavi
     * community manifest (fetched on demand, cached on disk by [LudusaviRegistry]).
     */
    data class SdkCloudSaveRecommendation(val appId: Int, val subdir: String, val name: String, val notes: String)

    /**
     * Returns the recommended save subdir from the Ludusavi manifest (network-fetched
     * and disk-cached by [LudusaviRegistry]). Returns null if Ludusavi has no entry
     * or the manifest is unavailable (offline with no cache).
     */
    suspend fun getRecommendedSdkCloudSaveSubdirAsync(context: Context, appId: Int): SdkCloudSaveRecommendation? {
        return LudusaviRegistry.lookup(context, appId)
    }

    /**
     * Pattern B detection: returns a recommendation only when the game is
     * (a) known to Ludusavi with an install-relative save path, AND
     * (b) has NO saveFilePatterns declared in Steam's PICS UFS (which would mean
     *     Auto-Cloud already covers it and the bridge isn't needed).
     *
     * Intersection-filtered lookup used by the launch-time prompt to avoid crying
     * wolf on the thousands of Auto-Cloud games in Ludusavi that don't need our mirror.
     */
    suspend fun shouldSuggestSdkCloudBridge(context: Context, appId: Int): SdkCloudSaveRecommendation? {
        val rec = getRecommendedSdkCloudSaveSubdirAsync(context, appId) ?: return null
        val hasAutoCloud = SteamService.getAppInfoOf(appId)?.ufs?.saveFilePatterns?.isNotEmpty() == true
        return rec.takeIf { !hasAutoCloud }
    }

    private fun sdkCloudRemoteDir(context: Context, appId: Int): File? {
        val accountId = SteamService.userSteamId?.accountID?.toLong() ?: return null
        val container = runCatching {
            ContainerUtils.getContainer(context, "STEAM_$appId")
        }.getOrNull() ?: return null
        return File(PathType.SteamUserData.toAbsPath(container, appId, accountId))
    }

    private fun sdkCloudGameSaveDir(context: Context, appId: Int): File? {
        // Mirror runs only when the user has explicitly enabled the bridge for this
        // container (via the launch-time prompt or the Cloud Save Bridge UI). No
        // implicit fallback — prevents silent REPLACE_EXISTING copies into a guessed
        // subdir that might not be the game's actual save location.
        val container = runCatching {
            ContainerUtils.getContainer(context, "STEAM_$appId")
        }.getOrNull() ?: return null
        val sub = container.sdkCloudSaveSubdir.trim().takeIf {
            it.isNotEmpty() && isValidSdkCloudSubdir(it)
        } ?: return null
        return File(SteamService.getAppDirPath(appId), sub)
    }

    /**
     * Validation for a user-supplied install-relative save subdir. Rejects anything
     * that could escape the install dir or address files outside a single component:
     * path separators (/ or \), .. traversal, absolute paths, Windows drive letters,
     * and empty values after trimming.
     */
    fun isValidSdkCloudSubdir(raw: String): Boolean {
        val v = raw.trim()
        if (v.isEmpty()) return false
        if (v.contains('/') || v.contains('\\')) return false
        if (v == "." || v == "..") return false
        if (v.contains(':')) return false
        return true
    }

    /**
     * Heuristic: find a single-component subdir under the game's install dir whose
     * file names overlap with what's at `<userdata>/<appid>/remote/`. Scans install
     * top-level only (no recursion — saves typically live one level deep). Returns
     * the subdir name with the highest filename overlap, or null if none match.
     *
     * Used by the container-config UI "Detect" button. Resolves paths via the
     * container's own rootDir rather than the global xuser symlink so it works even
     * when a different container is currently active.
     *
     * Best-effort — never returns a guess on ambiguous/empty state. Users still
     * confirm before enabling the mirror.
     */
    fun detectSdkCloudSaveSubdir(context: Context, appId: Int): String? {
        val accountId = SteamService.userSteamId?.accountID?.toLong()
        if (accountId == null) {
            Timber.w("detectSdkCloudSaveSubdir: no Steam account — cannot resolve userdata path for $appId")
            return null
        }

        val container = runCatching {
            ContainerUtils.getContainer(context, "STEAM_$appId")
        }.getOrNull() ?: run {
            Timber.w("detectSdkCloudSaveSubdir: no container found for STEAM_$appId")
            return null
        }

        val remoteDir = File(
            container.rootDir,
            ".wine/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId/remote",
        )
        if (!remoteDir.isDirectory) {
            Timber.i("detectSdkCloudSaveSubdir: no remote/ at ${remoteDir.absolutePath}")
            return null
        }
        val remoteNames = remoteDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                Timber.i("detectSdkCloudSaveSubdir: remote/ empty at ${remoteDir.absolutePath}")
                return null
            }

        val installDir = File(SteamService.getAppDirPath(appId))
        if (!installDir.isDirectory) {
            Timber.i("detectSdkCloudSaveSubdir: install dir missing at ${installDir.absolutePath}")
            return null
        }

        var bestName: String? = null
        var bestOverlap = 0
        installDir.listFiles()?.forEach { sub ->
            if (!sub.isDirectory) return@forEach
            if (!isValidSdkCloudSubdir(sub.name)) return@forEach
            val names = sub.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: return@forEach
            val overlap = remoteNames.intersect(names).size
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestName = sub.name
            }
        }
        Timber.i("detectSdkCloudSaveSubdir appId=$appId: remote=${remoteNames.size} file(s), best=$bestName overlap=$bestOverlap")
        return bestName
    }

    fun mirrorSdkCloudRemoteToSave(context: Context, appId: Int) {
        val gameSaveDir = sdkCloudGameSaveDir(context, appId) ?: return
        val remoteDir = sdkCloudRemoteDir(context, appId) ?: return

        if (!remoteDir.exists()) {
            Timber.i("mirrorSdkCloudRemoteToSave: remote/ missing for appId=$appId")
            return
        }
        if (!gameSaveDir.exists()) gameSaveDir.mkdirs()

        val remoteBase = remoteDir.toPath()
        val saveBase = gameSaveDir.toPath()
        Files.walk(remoteBase).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { src ->
                    val rel = remoteBase.relativize(src)
                    val dst = saveBase.resolve(rel)
                    try {
                        dst.parent?.let { Files.createDirectories(it) }
                        Files.copy(
                            src,
                            dst,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                        Timber.i("SDK cloud mirror remote->save appId=$appId: $rel (${Files.size(src)} bytes)")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to mirror $rel remote->save appId=$appId")
                    }
                }
        }
    }

    fun mirrorSdkCloudSaveToRemote(context: Context, appId: Int) {
        val gameSaveDir = sdkCloudGameSaveDir(context, appId) ?: return
        val remoteDir = sdkCloudRemoteDir(context, appId) ?: return

        if (!gameSaveDir.exists()) return
        if (!remoteDir.exists()) remoteDir.mkdirs()

        val saveBase = gameSaveDir.toPath()
        val remoteBase = remoteDir.toPath()
        Files.walk(saveBase).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { src ->
                    // Skip local-only artifacts (e.g. Dead Cells writes
                    // backup-YYYY-MM-DD-N.zip snapshots alongside saves; those aren't
                    // cloud-synced). Filename-only check — a nested "backup-*.zip" deep
                    // in a saves hierarchy is still treated the same; preserves the
                    // pre-recursive behavior for that specific exclusion.
                    val fname = src.fileName.toString()
                    if (fname.startsWith("backup-") && fname.endsWith(".zip")) return@forEach
                    val rel = saveBase.relativize(src)
                    val dst = remoteBase.resolve(rel)
                    try {
                        dst.parent?.let { Files.createDirectories(it) }
                        Files.copy(
                            src,
                            dst,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                        Timber.i("SDK cloud mirror save->remote appId=$appId: $rel (${Files.size(src)} bytes)")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to mirror $rel save->remote appId=$appId")
                    }
                }
        }
    }

    fun generateAchievementsFile(dllPath: Path, appId: String) {
        if (!SteamService.isLoggedIn) {
            Timber.w("Skipping achievements generation for $appId — Steam not logged in")
            return
        }

        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val settingsDir = dllPath.parent.resolve("steam_settings")
        if (Files.notExists(settingsDir)) {
            Files.createDirectories(settingsDir)
        }

        try {
            runBlocking {
                SteamService.generateAchievements(steamAppId, settingsDir.absolutePathString())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate achievements for $appId")
        }
    }
}

