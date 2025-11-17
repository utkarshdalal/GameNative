package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.gamenative.PrefManager
import app.gamenative.data.DepotInfo
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirName
import app.gamenative.service.SteamService.Companion.getAppInfoOf
import com.winlator.container.ContainerManager
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.util.HardwareUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import timber.log.Timber
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SteamUtils {

    internal val http = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)      // from 2 min → 5 min
        .protocols(listOf(Protocol.HTTP_1_1))  // skip HTTP/2 stream stalls
        .retryOnConnectionFailure(true)
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

    private fun copyOriginalSteamDll(dllPath: Path, appDirPath: String) {
        // 1️⃣  back-up next to the original DLL
        val backup = dllPath.parent.resolve("${dllPath.fileName}.orig")
        if (Files.notExists(backup)) {
            try {
                Files.copy(dllPath, backup)
                Timber.i("Copied original ${dllPath.fileName} to $backup")

                // 2️⃣  record the relative path inside the app directory
                val relPath = Paths.get(appDirPath).relativize(backup)
                Files.write(
                    Paths.get(appDirPath).resolve("orig_dll_path.txt"),
                    listOf(relPath.toString()),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            } catch (e: IOException) {
                Timber.w(e, "Failed to back up ${dllPath.fileName}")
            }
        }
    }

    /**
     * Replaces any existing `steam_api.dll` or `steam_api64.dll` in the app directory
     * with our pipe dll stored in assets
     */
    suspend fun replaceSteamApi(context: Context, appId: String) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED)) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        Timber.i("Starting replaceSteamApi for appId: $appId")
        Timber.i("Checking directory: $appDirPath")
        var replaced32 = false
        var replaced64 = false
        val imageFs = ImageFs.find(context)
        autoLoginUserChanges(imageFs)
        setupLightweightSteamConfig(imageFs, SteamService.userSteamId?.toString())

        FileUtils.walkThroughPath(Paths.get(appDirPath), -1) {
            if (it.name == "steam_api.dll" && it.exists()) {
                Timber.i("Found steam_api.dll at ${it.absolutePathString()}, replacing...")
                generateInterfacesFile(it)
                copyOriginalSteamDll(it, appDirPath)
                Files.delete(it)
                Files.createFile(it)
                FileOutputStream(it.absolutePathString()).use { fos ->
                    context.assets.open("steampipe/steam_api.dll").use { fs ->
                        fs.copyTo(fos)
                    }
                }
                Timber.i("Replaced steam_api.dll")
                replaced32 = true
                ensureSteamSettings(context, it, appId)
            }
            if (it.name == "steam_api64.dll" && it.exists()) {
                Timber.i("Found steam_api64.dll at ${it.absolutePathString()}, replacing...")
                generateInterfacesFile(it)
                copyOriginalSteamDll(it, appDirPath)
                Files.delete(it)
                Files.createFile(it)
                FileOutputStream(it.absolutePathString()).use { fos ->
                    context.assets.open("steampipe/steam_api64.dll").use { fs ->
                        fs.copyTo(fos)
                    }
                }
                Timber.i("Replaced steam_api64.dll")
                replaced64 = true
                ensureSteamSettings(context, it, appId)
            }
        }
        Timber.i("Finished replaceSteamApi for appId: $appId. Replaced 32bit: $replaced32, Replaced 64bit: $replaced64")

        // Restore unpacked executable if it exists (for DRM-free mode)
        restoreUnpackedExecutable(context, steamAppId)

        // Create Steam ACF manifest for real Steam compatibility
        createAppManifest(context, steamAppId)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
    }

    /**
     * Replaces any existing `steamclient.dll` or `steamclient64.dll` in the Steam directory
     */
    suspend fun replaceSteamclientDll(context: Context, appId: String) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        val container = ContainerUtils.getContainer(context, appId)

        // Create ColdClientLoader.ini file
        val gameName = getAppDirName(getAppInfoOf(steamAppId))
        val executablePath = container.executablePath.replace("/", "\\")
        val exePath = "steamapps\\common\\$gameName\\$executablePath"
        val exeCommandLine = container.execArgs
        val iniFile = File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini")
        iniFile.parentFile?.mkdirs()
        iniFile.writeText("""
            [SteamClient]

            Exe=$exePath
            ExeRunDir=
            ExeCommandLine=$exeCommandLine
            AppId=$steamAppId

            # path to the steamclient dlls, both must be set, absolute paths or relative to the loader directory
            SteamClientDll=steamclient.dll
            SteamClient64Dll=steamclient64.dll
        """.trimIndent())

        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED) && File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamclient_loader_x64.dll").exists()) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        val imageFs = ImageFs.find(context)
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            context.assets,
            "experimental-drm.tzst",
            imageFs.getRootDir(),
        );
        putBackSteamDlls(appDirPath)
        restoreUnpackedExecutable(context, steamAppId)
        ensureSteamSettings(context, File(container.getRootDir(), ".wine/drive_c/Program Files (x86)/Steam/steamclient.dll").toPath(), appId)

        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
    }

    private fun autoLoginUserChanges(imageFs: ImageFs) {
        val vdfFileText = SteamService.getLoginUsersVdfOauth(
            steamId64 = SteamService.userSteamId?.convertToUInt64().toString(),
            account = PrefManager.username,
            refreshToken = PrefManager.refreshToken,
            accessToken = PrefManager.accessToken,      // may be blank
        )
        val steamConfigDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/config")
        try {
            File(steamConfigDir, "loginusers.vdf").writeText(vdfFileText)
            val rootDir = imageFs.rootDir
            val userRegFile = File(rootDir, ImageFs.WINEPREFIX + "/user.reg")
            val steamRoot = "C:\\Program Files (x86)\\Steam"
            val steamExe = "$steamRoot\\steam.exe"
            val hkcu = "Software\\Valve\\Steam"
            WineRegistryEditor(userRegFile).use { reg ->
                reg.setStringValue("Software\\Valve\\Steam", "AutoLoginUser", PrefManager.username)
                reg.setDwordValue("Software\\Valve\\Steam", "RememberPassword", 1)
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
    private fun setupLightweightSteamConfig(imageFs: ImageFs, steamId64: String?) {
        Timber.i("Setting up lightweight steam configs")
        try {
            val steamPath = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam")

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
            val imageFs = ImageFs.find(context)
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

            val exe = File(imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/'))
            val unpackedExe = File(imageFs.wineprefix + "/dosdevices/" + executableFile.replace("A:", "a:").replace('\\', '/') + ".unpacked.exe")

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
            Timber.i("Attempting to createAppManifest for appId: $steamAppId")
            val appInfo = SteamService.getAppInfoOf(steamAppId)
            if (appInfo == null) {
                Timber.w("No app info found for appId: $steamAppId")
                return
            }

            val imageFs = ImageFs.find(context)

            // Create the steamapps folder structure
            val steamappsDir = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamapps")
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

            // Create symlink from Steam common directory to actual game directory
            val steamGameLink = File(commonDir, gameName)
            if (!steamGameLink.exists()) {
                Files.createSymbolicLink(steamGameLink.toPath(), gameDir.toPath())
                Timber.i("Created symlink from ${steamGameLink.absolutePath} to ${gameDir.absolutePath}")
            }

            // Get build ID and depot information
            val buildId = appInfo.branches["public"]?.buildId ?: 0L
            val downloadableDepots = SteamService.getDownloadableDepots(steamAppId)

            // Separate depots into regular depots (with manifests) and shared depots (without manifests)
            val regularDepots = mutableMapOf<Int, DepotInfo>()
            val sharedDepots = mutableMapOf<Int, DepotInfo>()

            downloadableDepots.forEach { (depotId, depotInfo) ->
                val manifest = depotInfo.manifests["public"]
                if (manifest != null && manifest.gid != 0L) {
                    regularDepots[depotId] = depotInfo
                } else {
                    sharedDepots[depotId] = depotInfo
                }
            }

            // Find the main content depot (owner) - typically the one with the lowest ID that has content
            val mainDepotId = regularDepots.keys.minOrNull()

            // Create ACF content
            val acfContent = buildString {
                appendLine("\"AppState\"")
                appendLine("{")
                appendLine("\t\"appid\"\t\t\"$steamAppId\"")
                appendLine("\t\"Universe\"\t\t\"1\"")
                appendLine("\t\"name\"\t\t\"${escapeString(appInfo.name)}\"")
                appendLine("\t\"StateFlags\"\t\t\"4\"") // 4 = fully installed
                appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
                appendLine("\t\"SizeOnDisk\"\t\t\"$sizeOnDisk\"")
                appendLine("\t\"buildid\"\t\t\"$buildId\"")

                // Use the actual install directory name
                val actualInstallDir = appInfo.config.installDir.ifEmpty { gameName }
                appendLine("\t\"installdir\"\t\t\"${escapeString(actualInstallDir)}\"")

                appendLine("\t\"LastOwner\"\t\t\"0\"")
                appendLine("\t\"BytesToDownload\"\t\t\"0\"")
                appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
                appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
                appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
                appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")

                // Add InstalledDepots section (only regular depots with actual manifests)
                if (regularDepots.isNotEmpty()) {
                    appendLine("\t\"InstalledDepots\"")
                    appendLine("\t{")
                    regularDepots.forEach { (depotId, depotInfo) ->
                        val manifest = depotInfo.manifests["public"]
                        appendLine("\t\t\"$depotId\"")
                        appendLine("\t\t{")
                        appendLine("\t\t\t\"manifest\"\t\t\"${manifest?.gid ?: "0"}\"")
                        appendLine("\t\t\t\"size\"\t\t\"${manifest?.size ?: 0}\"")
                        appendLine("\t\t}")
                    }
                    appendLine("\t}")
                }

                appendLine("\t\"UserConfig\" { \"language\" \"english\" }")
                appendLine("\t\"MountedConfig\" { \"language\" \"english\" }")

                appendLine("}")
            }

            // Write ACF file
            val acfFile = File(steamappsDir, "appmanifest_$steamAppId.acf")
            acfFile.writeText(acfContent)

            Timber.i("Created ACF manifest for ${appInfo.name} at ${acfFile.absolutePath}")

            // Create separate ACF for Steamworks Common Redistributables if we have shared depots
            if (sharedDepots.isNotEmpty()) {
                val steamworksAcfContent = buildString {
                    appendLine("\"AppState\"")
                    appendLine("{")
                    appendLine("\t\"appid\"\t\t\"228980\"")
                    appendLine("\t\"Universe\"\t\t\"1\"")
                    appendLine("\t\"name\"\t\t\"Steamworks Common Redistributables\"")
                    appendLine("\t\"StateFlags\"\t\t\"4\"")
                    appendLine("\t\"installdir\"\t\t\"Steamworks Shared\"")
                    appendLine("\t\"buildid\"\t\t\"1\"")

                    appendLine("\t\"BytesToDownload\"\t\t\"0\"")
                    appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
                    appendLine("}")
                }

                // Write Steamworks ACF file
                val steamworksAcfFile = File(steamappsDir, "appmanifest_228980.acf")
                steamworksAcfFile.writeText(steamworksAcfContent)

                Timber.i("Created Steamworks Common Redistributables ACF manifest at ${steamworksAcfFile.absolutePath}")
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to create ACF manifest for appId $steamAppId")
        }
    }

    private fun escapeString(input: String?): String {
        if (input == null) return ""
        return input.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
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

    /**
     * Restores the original steam_api.dll and steam_api64.dll files from their .orig backups
     * if they exist. Does not error if backup files are not found.
     */
    fun restoreSteamApi(context: Context, appId: String) {

        Timber.i("Starting restoreSteamApi for appId: ${appId}")
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
        val imageFs = ImageFs.find(context)
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val cfgFile = File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steam.cfg")
        if (container.isAllowSteamUpdates){
            Timber.i("Allowing steam updates, deleting the steam.cfg file")
            if (cfgFile.exists()){
                Timber.i("Allowing steam updates and file exists, deleting the steam.cfg file")
                cfgFile.delete()
            }
        } else {
            if (!cfgFile.exists()){
                cfgFile.parentFile?.mkdirs()
                Files.createFile(cfgFile.toPath())
                cfgFile.writeText("BootStrapperInhibitAll=Enable\nBootStrapperForceSelfUpdate=False")
            }
        }
        skipFirstTimeSteamSetup(imageFs.rootDir)
        val appDirPath = SteamService.getAppDirPath(steamAppId)
        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_RESTORED)) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        Timber.i("Checking directory: $appDirPath")

        autoLoginUserChanges(imageFs)
        setupLightweightSteamConfig(imageFs, SteamService.userSteamId!!.accountID.toString())

        putBackSteamDlls(appDirPath)

        Timber.i("Finished restoreSteamApi for appId: ${appId}")

        // Restore original executable if it exists (for real Steam mode)
        restoreOriginalExecutable(context, steamAppId)

        // Create Steam ACF manifest for real Steam compatibility
        createAppManifest(context, steamAppId)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
    }

    fun putBackSteamDlls(appDirPath: String) {
        FileUtils.walkThroughPath(Paths.get(appDirPath), -1) {
            if (it.name == "steam_api.dll.orig" && it.exists()) {
                try {
                    val originalPath = it.parent.resolve("steam_api.dll")
                    Timber.i("Found steam_api.dll.orig at ${it.absolutePathString()}, restoring...")

                    // Delete the current DLL if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(it, originalPath)

                    Timber.i("Restored steam_api.dll from backup")
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore steam_api.dll from backup")
                }
            }

            if (it.name == "steam_api64.dll.orig" && it.exists()) {
                try {
                    val originalPath = it.parent.resolve("steam_api64.dll")
                    Timber.i("Found steam_api64.dll.orig at ${it.absolutePathString()}, restoring...")

                    // Delete the current DLL if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(it, originalPath)

                    Timber.i("Restored steam_api64.dll from backup")
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore steam_api64.dll from backup")
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

        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")

        FileUtils.walkThroughPath(dosDevicesPath.toPath(), -1) {
            if (it.name.endsWith(".original.exe") && it.exists()) {
                try {
                    val originalPath = it.parent.resolve(it.name.removeSuffix(".original.exe"))
                    Timber.i("Found ${it.name} at ${it.absolutePathString()}, restoring...")

                    // Delete the current exe if it exists
                    if (Files.exists(originalPath)) {
                        Files.delete(originalPath)
                    }

                    // Copy the backup back to the original location
                    Files.copy(it, originalPath)

                    Timber.i("Restored ${originalPath.fileName} from backup")
                    restoredCount++
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore ${it.name} from backup")
                }
            }
        }

        Timber.i("Finished restoreOriginalExecutable for appId: $steamAppId. Restored $restoredCount executable(s)")
    }

    /**
     * Sibling folder “steam_settings” + empty “offline.txt” file, no-ops if they already exist.
     */
    private fun ensureSteamSettings(context: Context, dllPath: Path, appId: String) {
        val steamAppId = ContainerUtils.extractGameIdFromContainerId(appId)
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
        if (Files.notExists(depotsFile)) {
            SteamService.getInstalledDepotsOf(steamAppId)?.let { depotsList ->
                Files.createFile(depotsFile)
                depotsFile.toFile().writeText(depotsList.joinToString(System.lineSeparator()))
            }
        }

        val configsIni = settingsDir.resolve("configs.user.ini")
        val accountName   = PrefManager.username
        val accountSteamId = SteamService.userSteamId?.convertToUInt64()?.toString() ?: "0"
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val language = runCatching {
            (container.getExtra("language", null)
                ?: container.javaClass.getMethod("getLanguage").invoke(container) as? String)
                ?: "english"
        }.getOrDefault("english").lowercase()

        val iniContent = """
            [user::general]
            account_name=$accountName
            account_steamid=$accountSteamId
            language=$language
        """.trimIndent()

        if (Files.notExists(configsIni)) Files.createFile(configsIni)
        configsIni.toFile().writeText(iniContent)

        val appIni = settingsDir.resolve("configs.app.ini")
        val dlcIds = SteamService.getDlcDepotsOf(steamAppId)

        val forceDlc = container.isForceDlc()
        val appIniContent = buildString {
            appendLine("[app::dlcs]")
            appendLine("unlock_all=${if (forceDlc) 1 else 0}")
            dlcIds?.forEach { appendLine("$it=dlc$it") }
        }

        if (Files.notExists(appIni)) Files.createFile(appIni)
        appIni.toFile().writeText(appIniContent)

        val mainIni = settingsDir.resolve("configs.main.ini")

        val mainIniContent = buildString {
            appendLine("[main::connectivity]")
            appendLine("disable_lan_only=1")
        }

        if (Files.notExists(mainIni)) Files.createFile(mainIni)
        mainIni.toFile().writeText(mainIniContent)


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

    private fun skipFirstTimeSteamSetup(rootDir: File?) {
        val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")
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
                    val body = it.body?.string() ?: run { callback(-1); return }
                    Timber.i("[DX Fetch] Raw fbody etchDirect3DMajor for body=%s", body)
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
                }
            }
        })
    }

    fun getSteamId64(): Long? {
        return SteamService.userSteamId?.convertToUInt64()?.toLong()
    }

    fun getSteam3AccountId(): Long? {
        return SteamService.userSteamId?.accountID?.toLong()
    }
}
