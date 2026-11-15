package app.gamenative.service.ubisoft

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import app.gamenative.service.rockstar.RockstarLaunchSupport
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.WineRegistryEditor
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import java.io.File
import java.util.Locale
import java.util.Properties
import org.json.JSONObject
import timber.log.Timber

/** How a run of the stub ended, when it is something the app has to act on. */
sealed class UbisoftExitOutcome {
    data object SessionRejected : UbisoftExitOutcome()

    /** The stub found no session or an expired one; a new launch writes a fresh one. */
    data object SessionExpired : UbisoftExitOutcome()

    data class Failed(val exitCode: Int) : UbisoftExitOutcome()
}

/**
 * Prefix setup and session handoff for Steam games the stand-in serves: Ubisoft's runtime files in
 * the launcher folder, the registry keys the loaders read, the stub, and the session file.
 */
object UbisoftLaunchSupport {
    private const val PRODUCTS_FILE = "ubisoft/products.properties"
    private val loaders = setOf(
        "upc_r1_loader.dll", "upc_r1_loader64.dll",
        "uplay_r1_loader.dll", "uplay_r1_loader64.dll",
        "upc_r2_loader.dll", "upc_r2_loader64.dll",
        "ubiorbitapi_r2_loader.dll",
    )
    private val rejectRetried = mutableSetOf<Int>()
    private val detected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The game ships one of the launcher's loader DLLs in its root or one folder down. Files only, never app ids. */
    fun isUbisoftTitle(installDir: File): Boolean {
        if (installDir.path in detected) return true
        if (!installDir.isDirectory) return false
        return scanForLoader(installDir).also { if (it) detected.add(installDir.path) }
    }

    private fun scanForLoader(installDir: File): Boolean {
        val dirs = listOf(installDir) + installDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != UbisoftHelperDeployment.DIRECTORY }
        return dirs.any { dir -> dir.listFiles().orEmpty().any { it.name.lowercase() in loaders && it.isFile } } &&
            !RockstarLaunchSupport.isRockstarTitle(installDir)
    }

    /** For a Steam game: the launch goes through the stand-in. */
    fun servesLaunch(container: Container, installDir: File): Boolean = servesLaunch(container) { installDir }

    /** The launch mode is checked first, so other launches never resolve or read the install folder. */
    inline fun servesLaunch(container: Container, installDir: () -> File): Boolean =
        (container.isLaunchHeadlessSteam || container.isLaunchBionicSteam) && isUbisoftTitle(installDir())

    private fun sessionDir(prefixDriveC: File) = File(prefixDriveC, UbisoftConstants.SESSION_DIR)

    private fun statusFile(prefixDriveC: File) = File(sessionDir(prefixDriveC), UbisoftConstants.STATUS_FILE)

    private fun driveC(container: Container) = File(container.rootDir, ".wine/drive_c")

    suspend fun prepare(context: Context, container: Container, steamAppId: Int, installDir: File, onProgress: (Float) -> Unit = {}) {
        val runtime = UbisoftRuntime.ensureCached(context, onProgress)
        val prefixDriveC = driveC(container)
        val launcherDir = UbisoftRuntime.installInto(runtime, prefixDriveC)
        UbisoftHelperArchive.stub(context.filesDir).copyTo(File(launcherDir, UbisoftConstants.LAUNCHER_EXE), overwrite = true)
        UbisoftHelperArchive.configFile(context.filesDir).copyTo(File(launcherDir, UbisoftHelperArchive.CONFIG), overwrite = true)
        // Under the Steam client the game's own DLLs start the stub as upc.exe; only bionic Steam launches it from the game folder.
        if (container.isLaunchBionicSteam) UbisoftHelperDeployment.prepare(context.filesDir, installDir)
        writeRegistry(container, installDir, productId(context, steamAppId))
        statusFile(prefixDriveC).delete()
        writeSession(context, prefixDriveC, steamAppId)
    }

    /**
     * The launcher folder in both registry views (32-bit games read the Wow6432Node one), and the
     * game's own install entry once a previous run has told us its product id.
     */
    fun writeRegistry(container: Container, installDir: File, productId: Long?) {
        val systemReg = File(container.rootDir, ".wine/system.reg")
        val gameRoot = "C:/Program Files (x86)/Steam/steamapps/common/${installDir.name}/"
        try {
            WineRegistryEditor(systemReg).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for (base in listOf("Software\\Wow6432Node\\Ubisoft\\Launcher", "Software\\Ubisoft\\Launcher")) {
                    editor.setStringValue(base, "InstallDir", UbisoftConstants.RUNTIME_DIR_WINDOWS)
                    if (productId != null) editor.setStringValue("$base\\Installs\\$productId", "InstallDir", gameRoot)
                }
            }
            Timber.i("Ubisoft: launcher keys written (product %s)", productId?.toString() ?: "not known yet")
        } catch (e: Exception) {
            Timber.e(e, "Ubisoft: failed to write registry keys")
        }
    }

    private suspend fun writeSession(context: Context, prefixDriveC: File, steamAppId: Int) {
        val creds = UbisoftAuthManager.load(context) ?: error("No Ubisoft session to hand to the launcher")
        val steamId = PrefManager.steamUserSteamId64.toString()
        val appTicket = runCatching { SteamService.instance?.getEncryptedAppTicketBase64(steamAppId) }
            .onFailure { Timber.w(it, "Ubisoft: no encrypted app ticket for %d", steamAppId) }.getOrNull()
        val persona = runCatching {
            SteamService.instance?.steamClient?.getHandler(SteamFriends::class.java)?.getPersonaName()
        }.getOrNull()
        val json = sessionJson(creds, steamId, steamAppId, persona, appTicket, Locale.getDefault().toLanguageTag())
        writePrivate(File(sessionDir(prefixDriveC), UbisoftConstants.SESSION_FILE), json.toString())
        Timber.i("Ubisoft: session file written (%s)", if (appTicket != null) "with app ticket" else "no app ticket")
    }

    /** The remember-me ticket is never included. */
    internal fun sessionJson(
        creds: UbisoftCredentials,
        steamId: String,
        steamAppId: Int,
        persona: String?,
        encryptedAppTicket: String?,
        language: String? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("ticket", creds.ticket)
            .put("sessionId", creds.sessionId)
            .put("expiresAt", creds.expiresAt / 1000)
            .put("userId", creds.userId)
            .put("profileId", creds.profileId)
            .put("nameOnPlatform", creds.nameOnPlatform)
            .put("email", creds.email)
            .put("country", creds.country)
            .put("steamId", steamId)
            .put("steamAppId", steamAppId)
        if (!persona.isNullOrEmpty()) json.put("steamPersona", persona)
        if (!language.isNullOrEmpty()) json.put("language", language)
        if (!encryptedAppTicket.isNullOrEmpty()) json.put("encryptedAppTicket", encryptedAppTicket)
        return json
    }

    /** Reads and removes the stub's status after a run; null when the run needs nothing from the app. */
    fun takeExitOutcome(context: Context, appId: String, steamAppId: Int): UbisoftExitOutcome? {
        val container = ContainerUtils.getContainer(context, appId)
        if (!servesLaunch(container) { File(SteamService.getAppDirPath(steamAppId)) }) return null
        val file = statusFile(driveC(container))
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        file.delete()
        if (json == null) return null
        json.optLong("productId").takeIf { it > 0 }?.let { rememberProductId(context, steamAppId, it) }
        val outcome = mapStatus(json)
        Timber.i("Ubisoft: stub finished with %s (%d) -> %s", json.optString("status"), json.optInt("exitCode"), outcome?.javaClass?.simpleName ?: "nothing to do")
        if (outcome == null) synchronized(rejectRetried) { rejectRetried.remove(steamAppId) }
        return outcome
    }

    internal fun mapStatus(json: JSONObject): UbisoftExitOutcome? {
        val status = json.optString("status").lowercase()
        val code = json.optInt("exitCode", 0)
        // "exited" carries the game's own exit code.
        if (status == "exited") return null
        // A refused account is not fixed by signing in again.
        if (status == "banned") return UbisoftExitOutcome.Failed(code)
        if (status == "no_session" || status == "session_expired") return UbisoftExitOutcome.SessionExpired
        return when (code) {
            UbisoftConstants.EXIT_SESSION_REJECTED -> UbisoftExitOutcome.SessionRejected
            in UbisoftConstants.EXIT_SETUP..UbisoftConstants.EXIT_ACTIVATION_REFUSED -> UbisoftExitOutcome.Failed(code)
            else -> null
        }
    }

    /** The first rejection after a run reopens the sign-in; a second in a row is reported. */
    fun shouldRetrySignIn(steamAppId: Int): Boolean = synchronized(rejectRetried) { rejectRetried.add(steamAppId) }

    private fun productsFile(context: Context) = File(context.filesDir, PRODUCTS_FILE)

    fun productId(context: Context, steamAppId: Int): Long? = runCatching {
        val file = productsFile(context)
        if (!file.isFile) return null
        Properties().apply { file.inputStream().use { load(it) } }.getProperty(steamAppId.toString())?.toLongOrNull()
    }.getOrNull()

    private fun rememberProductId(context: Context, steamAppId: Int, productId: Long) {
        runCatching {
            val file = productsFile(context)
            val props = Properties()
            if (file.isFile) file.inputStream().use { props.load(it) }
            if (props.getProperty(steamAppId.toString()) == productId.toString()) return
            props.setProperty(steamAppId.toString(), productId.toString())
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        }.onFailure { Timber.w(it, "Ubisoft: could not record the product id") }
    }

    /** Signing out has to remove every session file placed in a prefix, as the Rockstar tokens are. */
    fun clearPlacedSessions(context: Context) {
        val containers = runCatching { ContainerManager(context).containers }.getOrNull() ?: return
        for (container in containers) {
            val dir = sessionDir(driveC(container))
            for (name in listOf(UbisoftConstants.SESSION_FILE, UbisoftConstants.STATUS_FILE)) {
                val file = File(dir, name)
                if (file.exists() && file.delete()) Timber.i("Ubisoft: removed %s from a prefix", name)
            }
        }
    }

    private fun writePrivate(file: File, text: String) {
        val dir = file.parentFile!!
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create the Ubisoft session folder" }
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(text)
        temp.setReadable(false, false)
        temp.setReadable(true, true)
        temp.setWritable(false, false)
        temp.setWritable(true, true)
        check(temp.renameTo(file) || run { file.delete(); temp.renameTo(file) }) { "Cannot write the Ubisoft session file" }
    }
}
