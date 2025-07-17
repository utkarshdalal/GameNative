package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import `in`.dragonbra.javasteam.util.HardwareUtils
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
    fun replaceSteamApi(context: Context, appId: Int) {
        Timber.i("Starting replaceSteamApi for appId: $appId")
        val appDirPath = SteamService.getAppDirPath(appId)
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
        steamConfigDir.mkdirs()
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
