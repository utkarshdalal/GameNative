package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.gamenative.PrefManager
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
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

object SteamUtils {

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
    suspend fun replaceSteamApi(context: Context, appId: Int) {
        val appDirPath = SteamService.getAppDirPath(appId)
        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_REPLACED)) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
        Timber.i("Starting replaceSteamApi for appId: $appId")
        Timber.i("Checking directory: $appDirPath")
        var replaced32 = false
        var replaced64 = false
        val imageFs = ImageFs.find(context)
        val vdfFileText = SteamService.getLoginUsersVdfOauth(
            steamId64 = SteamService.userSteamId?.convertToUInt64().toString(),
            account = PrefManager.username,
            refreshToken = PrefManager.refreshToken,
            accessToken = PrefManager.accessToken      // may be blank
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
                reg.setStringValue("Software\\Valve\\Steam", "RememberPassword", "1")
                reg.setStringValue(hkcu, "SteamExe", steamExe)
                reg.setStringValue(hkcu, "SteamPath", steamRoot)
                reg.setStringValue(hkcu, "InstallPath", steamRoot)
            }
        } catch (e: Exception) {
            Timber.w("Could not add steam config options: $e")
        }

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
                ensureSteamSettings(it, appId)
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
                ensureSteamSettings(it, appId)
            }
        }
        Timber.i("Finished replaceSteamApi for appId: $appId. Replaced 32bit: $replaced32, Replaced 64bit: $replaced64")

        // Restore unpacked executable if it exists (for DRM-free mode)
        restoreUnpackedExecutable(context, appId)

        // Create Steam ACF manifest for real Steam compatibility
        createAppManifest(context, appId)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
    }

    /**
     * Restores the unpacked executable (.unpacked.exe) if it exists and is different from current .exe
     * This ensures we use the DRM-free version when not using real Steam
     */
    private fun restoreUnpackedExecutable(context: Context, appId: Int) {
        try {
            val imageFs = ImageFs.find(context)
            val appDirPath = SteamService.getAppDirPath(appId)
            val executablePath = SteamService.getInstalledExe(appId)

            // Convert to Wine path format
            val container = ContainerUtils.getContainer(context, appId)
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
            Timber.e(e, "Failed to restore unpacked executable for appId $appId")
        }
    }

    /**
     * Creates a Steam ACF (Application Cache File) manifest for the given app
     * This allows real Steam to detect the game as installed
     */
    private fun createAppManifest(context: Context, appId: Int) {
        try {
            Timber.i("Attempting to createAppManifest for appId: $appId")
            val appInfo = SteamService.getAppInfoOf(appId)
            if (appInfo == null) {
                Timber.w("No app info found for appId: $appId")
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
            val gameDir = File(SteamService.getAppDirPath(appId))
            val gameName = gameDir.name
            val sizeOnDisk = calculateDirectorySize(gameDir)

            // Create symlink from Steam common directory to actual game directory
            val steamGameLink = File(commonDir, gameName)
            if (!steamGameLink.exists()) {
                Files.createSymbolicLink(steamGameLink.toPath(), gameDir.toPath())
                Timber.i("Created symlink from ${steamGameLink.absolutePath} to ${gameDir.absolutePath}")
            }

            // Create ACF content
            val acfContent = buildString {
                appendLine("\"AppState\"")
                appendLine("{")
                appendLine("\t\"appid\"\t\t\"$appId\"")
                appendLine("\t\"Universe\"\t\t\"1\"")
                appendLine("\t\"name\"\t\t\"${escapeString(appInfo.name)}\"")
                appendLine("\t\"StateFlags\"\t\t\"4\"") // 4 = fully installed
                appendLine("\t\"LastUpdated\"\t\t\"${System.currentTimeMillis() / 1000}\"")
                appendLine("\t\"SizeOnDisk\"\t\t\"$sizeOnDisk\"")

                // Use the actual install directory name
                val actualInstallDir = appInfo.config.installDir.ifEmpty { gameName }
                appendLine("\t\"InstallDir\"\t\t\"${escapeString(actualInstallDir)}\"")

                appendLine("\t\"LastOwner\"\t\t\"0\"")
                appendLine("\t\"BytesToDownload\"\t\t\"0\"")
                appendLine("\t\"BytesDownloaded\"\t\t\"0\"")
                appendLine("\t\"AutoUpdateBehavior\"\t\t\"0\"")
                appendLine("\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"")
                appendLine("\t\"ScheduledAutoUpdate\"\t\t\"0\"")
                appendLine("}")
            }

            // Write ACF file
            val acfFile = File(steamappsDir, "appmanifest_$appId.acf")
            acfFile.writeText(acfContent)

            Timber.i("Created ACF manifest for ${appInfo.name} at ${acfFile.absolutePath}")

        } catch (e: Exception) {
            Timber.e(e, "Failed to create ACF manifest for appId $appId")
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
    fun restoreSteamApi(context: Context, appId: Int) {
        Timber.i("Starting restoreSteamApi for appId: $appId")
        val appDirPath = SteamService.getAppDirPath(appId)
        if (MarkerUtils.hasMarker(appDirPath, Marker.STEAM_DLL_RESTORED)) {
            return
        }
        MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
        Timber.i("Checking directory: $appDirPath")
        var restored32 = false
        var restored64 = false

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
                    restored32 = true
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
                    restored64 = true
                } catch (e: IOException) {
                    Timber.w(e, "Failed to restore steam_api64.dll from backup")
                }
            }
        }

        Timber.i("Finished restoreSteamApi for appId: $appId. Restored 32bit: $restored32, Restored 64bit: $restored64")

        // Restore original executable if it exists (for real Steam mode)
        restoreOriginalExecutable(context, appId)

        // Create Steam ACF manifest for real Steam compatibility
        createAppManifest(context, appId)
        MarkerUtils.addMarker(appDirPath, Marker.STEAM_DLL_RESTORED)
    }

    /**
     * Restores the original executable files from their .original.exe backups
     * if they exist. Does not error if backup files are not found.
     */
    fun restoreOriginalExecutable(context: Context, appId: Int) {
        Timber.i("Starting restoreOriginalExecutable for appId: $appId")
        val appDirPath = SteamService.getAppDirPath(appId)
        Timber.i("Checking directory: $appDirPath")
        var restoredCount = 0

        val imageFs = ImageFs.find(context)
        val dosDevicesPath = File(imageFs.wineprefix, "dosdevices/a:")

        FileUtils.walkThroughPath(dosDevicesPath.toPath(), -1) {
            if (it.name.endsWith(".original.exe") && it.exists()) {
                try {
                    val originalPath = it.parent.resolve(it.name.removeSuffix(".original.exe") + ".exe")
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

        Timber.i("Finished restoreOriginalExecutable for appId: $appId. Restored $restoredCount executable(s)")
    }

    /**
     * Sibling folder “steam_settings” + empty “offline.txt” file, no-ops if they already exist.
     */
    private fun ensureSteamSettings(dllPath: Path, appId: Int) {
        val appIdFileUpper = dllPath.parent.resolve("steam_appid.txt")
        if (Files.notExists(appIdFileUpper)) {
            Files.createFile(appIdFileUpper)
            appIdFileUpper.toFile().writeText(appId.toString())
        }
        val settingsDir = dllPath.parent.resolve("steam_settings")
        if (Files.notExists(settingsDir)) {
            Files.createDirectories(settingsDir)
        }
        val offlineFile = settingsDir.resolve("offline.txt")
        if (Files.notExists(offlineFile)) {
            Files.createFile(offlineFile)
        }
        val disableNetworkingFile = settingsDir.resolve("disable_networking.txt")
        if (Files.notExists(disableNetworkingFile)) {
            Files.createFile(disableNetworkingFile)
        }
        val appIdFile = settingsDir.resolve("steam_appid.txt")
        if (Files.notExists(appIdFile)) {
            Files.createFile(appIdFile)
            appIdFile.toFile().writeText(appId.toString())
        }
        val steamIdFile = settingsDir.resolve("force_steamid.txt")
        if (Files.notExists(steamIdFile)) {
            Files.createFile(steamIdFile)
            steamIdFile.toFile().writeText(SteamService.userSteamId?.convertToUInt64().toString())
        }
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
}
