package app.gamenative.service.ubisoft

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.WineRegistryEditor
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import java.io.File
import java.util.Properties
import org.json.JSONObject
import timber.log.Timber

/** How a run of the stub ended, when it is something the app has to act on. */
sealed class UbisoftExitOutcome {
    /** The account does not hold this Steam purchase yet; the stub waits for the user's consent. */
    data class NeedsLink(
        val steamAppId: Int,
        val productId: Long,
        val userId: String,
        val nameOnPlatform: String,
        val maskedEmail: String,
    ) : UbisoftExitOutcome()

    data object SessionRejected : UbisoftExitOutcome()

    data class Failed(val exitCode: Int) : UbisoftExitOutcome()
}

/**
 * Prefix setup and session handoff for Steam games the stand-in serves: Ubisoft's runtime files in
 * the launcher folder, the registry keys the loaders read, the stub, and the session file.
 */
object UbisoftLaunchSupport {
    private const val PRODUCTS_FILE = "ubisoft/products.properties"
    private val normalStatuses = setOf("ok", "exited", "game_exited", "normal")
    private val rejectRetried = mutableSetOf<Int>()

    fun isUbisoftTitle(installDir: File) = UbisoftGameDetection.needsStandIn(installDir)

    fun sessionDir(prefixDriveC: File) = File(prefixDriveC, UbisoftConstants.SESSION_DIR)

    fun sessionFile(prefixDriveC: File) = File(sessionDir(prefixDriveC), UbisoftConstants.SESSION_FILE)

    fun statusFile(prefixDriveC: File) = File(sessionDir(prefixDriveC), UbisoftConstants.STATUS_FILE)

    private fun driveC(container: Container) = File(container.rootDir, ".wine/drive_c")

    /** Everything the stub needs in place before the game starts. Runs on every launch. */
    suspend fun prepare(context: Context, container: Container, steamAppId: Int, installDir: File, onProgress: (Float) -> Unit = {}) {
        val runtime = UbisoftRuntime.ensureCached(context, onProgress)
        val prefixDriveC = driveC(container)
        val launcherDir = UbisoftRuntime.installInto(runtime, prefixDriveC)
        UbisoftHelperArchive.stub(context.filesDir).copyTo(File(launcherDir, UbisoftConstants.LAUNCHER_EXE), overwrite = true)
        UbisoftHelperArchive.configFile(context.filesDir).copyTo(File(launcherDir, UbisoftHelperArchive.CONFIG), overwrite = true)
        UbisoftHelperDeployment.prepare(context.filesDir, installDir)
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
        val file = sessionFile(prefixDriveC)
        val previousConsent = runCatching { JSONObject(file.readText()).optJSONObject(CONSENT) }.getOrNull()
        val json = sessionJson(creds, steamId, steamAppId, persona, appTicket, previousConsent, java.util.Locale.getDefault().toLanguageTag())
        writePrivate(file, json.toString())
        Timber.i("Ubisoft: session file written (ticket %s, consent %s)", if (appTicket != null) "with app ticket" else "no app ticket", json.has(CONSENT))
    }

    /**
     * The session file the stub reads. The remember-me ticket is never included. A consent from the
     * previous write is kept only while it names the same account, Steam user and game.
     */
    internal fun sessionJson(
        creds: UbisoftCredentials,
        steamId: String,
        steamAppId: Int,
        persona: String?,
        encryptedAppTicket: String?,
        previousConsent: JSONObject?,
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
        if (previousConsent != null &&
            previousConsent.optString("userId") == creds.userId &&
            previousConsent.optString("steamId") == steamId &&
            previousConsent.optInt("steamAppId") == steamAppId &&
            previousConsent.optLong("productId") > 0
        ) json.put(CONSENT, previousConsent)
        return json
    }

    internal fun consentJson(userId: String, steamId: String, steamAppId: Int, productId: Long, timeSeconds: Long): JSONObject =
        JSONObject()
            .put("userId", userId)
            .put("steamId", steamId)
            .put("steamAppId", steamAppId)
            .put("productId", productId)
            .put("time", timeSeconds)

    /** Only ever called from the user's tap on Link in the confirmation dialog. */
    fun writeLinkConsent(context: Context, appId: String, link: UbisoftExitOutcome.NeedsLink): Boolean = runCatching {
        val container = app.gamenative.utils.ContainerUtils.getContainer(context, appId)
        val file = sessionFile(driveC(container))
        val creds = UbisoftAuthManager.load(context) ?: error("signed out")
        check(creds.userId == link.userId || link.userId.isEmpty()) { "the signed-in account changed" }
        val json = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        json.put(
            CONSENT,
            consentJson(creds.userId, PrefManager.steamUserSteamId64.toString(), link.steamAppId, link.productId, System.currentTimeMillis() / 1000),
        )
        writePrivate(file, json.toString())
        Timber.i("Ubisoft: link consent recorded for product %d", link.productId)
        true
    }.onFailure { Timber.e(it, "Ubisoft: could not record the link consent") }.getOrDefault(false)

    /** Reads and removes the stub's status after a run; null when the run needs nothing from the app. */
    fun takeExitOutcome(context: Context, appId: String, steamAppId: Int): UbisoftExitOutcome? {
        val installDir = File(SteamService.getAppDirPath(steamAppId))
        if (!isUbisoftTitle(installDir)) return null
        val container = app.gamenative.utils.ContainerUtils.getContainer(context, appId)
        val file = statusFile(driveC(container))
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        file.delete()
        if (json == null) return null
        json.optLong("productId").takeIf { it > 0 }?.let { rememberProductId(context, steamAppId, it) }
        val creds = UbisoftAuthManager.load(context)
        val outcome = mapStatus(json, steamAppId, creds)
        Timber.i("Ubisoft: stub finished with %s (%d) -> %s", json.optString("status"), json.optInt("exitCode"), outcome?.javaClass?.simpleName ?: "nothing to do")
        if (outcome == null) synchronized(rejectRetried) { rejectRetried.remove(steamAppId) }
        return outcome
    }

    internal fun mapStatus(json: JSONObject, steamAppId: Int, creds: UbisoftCredentials?): UbisoftExitOutcome? {
        val status = json.optString("status").lowercase()
        val code = json.optInt("exitCode", 0)
        if (status in normalStatuses) return null
        // A refused account is not fixed by signing in again.
        if (status == "banned") return UbisoftExitOutcome.Failed(code.takeIf { it != 0 } ?: UbisoftConstants.EXIT_SESSION_REJECTED)
        if (status == "needs_link" || code == UbisoftConstants.EXIT_LINK_CONSENT_NEEDED) {
            val productId = json.optLong("productId")
            if (productId <= 0) return UbisoftExitOutcome.Failed(UbisoftConstants.EXIT_LINK_CONSENT_NEEDED)
            return UbisoftExitOutcome.NeedsLink(
                steamAppId = steamAppId,
                productId = productId,
                userId = json.optString("userId").ifEmpty { creds?.userId.orEmpty() },
                nameOnPlatform = json.optString("nameOnPlatform").ifEmpty { creds?.nameOnPlatform.orEmpty() },
                maskedEmail = json.optString("maskedEmail").ifEmpty { maskEmail(creds?.email.orEmpty()) },
            )
        }
        return when (code) {
            0 -> null
            UbisoftConstants.EXIT_SESSION_REJECTED -> UbisoftExitOutcome.SessionRejected
            in UbisoftConstants.EXIT_SETUP..UbisoftConstants.EXIT_ACTIVATION_REFUSED -> UbisoftExitOutcome.Failed(code)
            else -> null
        }
    }

    /** The first rejection after a run reopens the sign-in; a second in a row is reported. */
    fun shouldRetrySignIn(steamAppId: Int): Boolean = synchronized(rejectRetried) { rejectRetried.add(steamAppId) }

    internal fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return if (email.isEmpty()) "" else "***"
        val domain = email.substring(at + 1)
        val dot = domain.lastIndexOf('.')
        val head = email.substring(0, 1) + "***@"
        return if (dot <= 0) "$head***" else head + domain.substring(0, 1) + "***" + domain.substring(dot)
    }

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

    private const val CONSENT = "link_consent"
}
