package app.gamenative.service.gog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.OsConstants
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

object GogGalaxyService {
    private const val TAG = "GogGalaxy"
    private const val COMET_LIB = "libcomet.so"
    private const val DUMMY_ASSET = "gog/GalaxyCommunication.exe"
    private const val SERVICE_EXE_RELATIVE = "drive_c/ProgramData/GOG.com/Galaxy/redists/GalaxyCommunication.exe"
    private const val REAL_SERVICE_MIN_SIZE = 1L shl 20
    private const val USERNAME_FALLBACK = "GOG User"

    internal const val SERVICE_KEY = "System\\CurrentControlSet\\Services\\GalaxyCommunication"
    internal const val CLIENT_PATHS_KEY = "Software\\Wow6432Node\\GOG.com\\GalaxyClient\\paths"
    internal const val SERVICE_IMAGE_PATH = "C:\\ProgramData\\GOG.com\\Galaxy\\redists\\GalaxyCommunication.exe"
    internal const val CLIENT_PATH = "C:\\Program Files\\GOG Galaxy"
    internal const val SERVICE_OBJECT_NAME = "LocalSystem"
    internal const val SERVICE_TYPE_WIN32_OWN_PROCESS = 0x10
    internal const val SERVICE_START_DEMAND = 3
    internal const val SERVICE_ERROR_NORMAL = 1
    internal const val SERVICE_PRESHUTDOWN_TIMEOUT = 180000

    internal data class CometCommand(val args: List<String>, val env: Map<String, String>)

    private val lock = Any()
    private var process: Process? = null
    private var generation = 0

    private val usernameClient: OkHttpClient by lazy {
        Net.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    }

    fun isActive(context: Context, container: Container, offline: Boolean): Boolean =
        !offline &&
            !container.isDisableGogGalaxyService &&
            File(context.applicationInfo.nativeLibraryDir, COMET_LIB).exists() &&
            hasUsableCredentials(context)

    private fun hasUsableCredentials(context: Context): Boolean {
        val galaxy = runCatching {
            JSONObject(File(GOGAuthManager.getAuthConfigPath(context)).readText()).optJSONObject(GOGConstants.GOG_CLIENT_ID)
        }.getOrNull() ?: return false
        if (galaxy.optString("refresh_token").isEmpty() || galaxy.optString("user_id").isEmpty()) return false
        val expiresAt = galaxy.optDouble("loginTime", 0.0) + galaxy.optInt("expires_in", 0)
        return System.currentTimeMillis() / 1000.0 < expiresAt - 60 || hasInternet(context)
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun preparePrefix(context: Context, container: Container) {
        runCatching {
            val wineDir = File(container.rootDir, ".wine")
            if (!installDummy(context, File(wineDir, SERVICE_EXE_RELATIVE))) return
            if (writeRegistry(File(wineDir, "system.reg"))) {
                Timber.tag(TAG).i("Registered GalaxyCommunication service in prefix")
            }
        }.onFailure { Timber.tag(TAG).e(it, "Failed to prepare prefix for Galaxy service") }
    }

    fun unregisterPrefix(container: Container) {
        runCatching {
            val wineDir = File(container.rootDir, ".wine")
            val exe = File(wineDir, SERVICE_EXE_RELATIVE)
            if (exe.isFile && exe.length() > REAL_SERVICE_MIN_SIZE) return
            if (removeRegistry(File(wineDir, "system.reg"))) {
                Timber.tag(TAG).i("Removed GalaxyCommunication service registration from prefix")
            }
        }.onFailure { Timber.tag(TAG).e(it, "Failed to unregister Galaxy service") }
    }

    fun start(context: Context) {
        val gen: Int
        synchronized(lock) {
            stopLocked(blocking = false)
            gen = ++generation
        }
        val appContext = context.applicationContext
        Thread({ launch(appContext, gen) }, "comet-start").start()
    }

    fun stop(blocking: Boolean = true) {
        synchronized(lock) {
            generation++
            stopLocked(blocking)
        }
    }

    private fun launch(context: Context, gen: Int) {
        runCatching {
            val binary = File(context.applicationInfo.nativeLibraryDir, COMET_LIB)
            if (!binary.exists()) {
                Timber.tag(TAG).w("$COMET_LIB not available, skipping Galaxy service")
                return
            }
            val credentials = runBlocking { GOGAuthManager.getStoredCredentials(context) }.getOrNull()
            if (credentials == null || credentials.accessToken.isEmpty() || credentials.refreshToken.isEmpty() || credentials.userId.isEmpty()) {
                Timber.tag(TAG).w("No GOG credentials, skipping Galaxy service")
                return
            }
            val home = File(context.filesDir, "comet").apply { mkdirs() }
            val tmp = File(context.cacheDir, "comet-tmp").apply { mkdirs() }
            val username = resolveUsername(home, credentials.userId, credentials.accessToken)
            val command = buildCommand(binary, credentials.accessToken, credentials.refreshToken, credentials.userId, username, home, tmp)
            command.env["XDG_DATA_HOME"]?.let { File(it).mkdirs() }
            command.env["XDG_CONFIG_HOME"]?.let { File(it).mkdirs() }
            synchronized(lock) {
                if (gen != generation) return
                val builder = ProcessBuilder(command.args)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File(context.cacheDir, "comet.log")))
                builder.environment().putAll(command.env)
                process = builder.start()
            }
            Timber.tag(TAG).i("Started Galaxy service (comet)")
        }.onFailure { Timber.tag(TAG).e(it, "Failed to start Galaxy service") }
    }

    private fun stopLocked(blocking: Boolean) {
        val p = process ?: return
        process = null
        runCatching { pidOf(p)?.let { android.os.Process.sendSignal(it, OsConstants.SIGTERM) } }
            .onFailure { Timber.tag(TAG).w(it, "Failed to signal Galaxy service") }
        if (blocking) reap(p) else Thread({ reap(p) }, "comet-stop").start()
    }

    internal fun buildCommand(
        binary: File,
        accessToken: String,
        refreshToken: String,
        userId: String,
        username: String,
        homeDir: File,
        tmpDir: File,
    ): CometCommand = CometCommand(
        args = listOf(binary.absolutePath, "--username", username),
        env = mapOf(
            "COMET_ACCESS_TOKEN" to accessToken,
            "COMET_REFRESH_TOKEN" to refreshToken,
            "COMET_USER_ID" to userId,
            "HOME" to homeDir.absolutePath,
            "XDG_DATA_HOME" to File(homeDir, ".local/share").absolutePath,
            "XDG_CONFIG_HOME" to File(homeDir, ".config").absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "COMET_LOG" to "info",
        ),
    )

    internal fun writeRegistry(systemReg: File): Boolean {
        if (!systemReg.isFile) {
            Timber.tag(TAG).w("No system.reg at ${systemReg.absolutePath}, skipping Galaxy service registration")
            return false
        }
        return WineRegistryEditor(systemReg).use { editor ->
            if (isRegistered(editor)) return@use false
            editor.setCreateKeyIfNotExist(true)
            editor.setExpandStringValue(SERVICE_KEY, "ImagePath", SERVICE_IMAGE_PATH)
            editor.setStringValue(SERVICE_KEY, "ObjectName", SERVICE_OBJECT_NAME)
            editor.setDwordValue(SERVICE_KEY, "Start", SERVICE_START_DEMAND)
            editor.setDwordValue(SERVICE_KEY, "ErrorControl", SERVICE_ERROR_NORMAL)
            editor.setDwordValue(SERVICE_KEY, "Type", SERVICE_TYPE_WIN32_OWN_PROCESS)
            editor.setDwordValue(SERVICE_KEY, "PreshutdownTimeout", SERVICE_PRESHUTDOWN_TIMEOUT)
            editor.setStringValue(CLIENT_PATHS_KEY, "client", CLIENT_PATH)
            true
        }
    }

    internal fun removeRegistry(systemReg: File): Boolean {
        if (!systemReg.isFile) return false
        return WineRegistryEditor(systemReg).use { editor ->
            if (editor.getExpandStringValue(SERVICE_KEY, "ImagePath") != SERVICE_IMAGE_PATH) return@use false
            val removedService = editor.removeKey(SERVICE_KEY, true)
            val removedPaths = editor.removeKey(CLIENT_PATHS_KEY, true)
            removedService || removedPaths
        }
    }

    private fun isRegistered(editor: WineRegistryEditor): Boolean =
        editor.getExpandStringValue(SERVICE_KEY, "ImagePath") == SERVICE_IMAGE_PATH &&
            editor.getStringValue(SERVICE_KEY, "ObjectName") == SERVICE_OBJECT_NAME &&
            dword(editor, "Start") == SERVICE_START_DEMAND &&
            dword(editor, "ErrorControl") == SERVICE_ERROR_NORMAL &&
            dword(editor, "Type") == SERVICE_TYPE_WIN32_OWN_PROCESS &&
            dword(editor, "PreshutdownTimeout") == SERVICE_PRESHUTDOWN_TIMEOUT &&
            editor.getStringValue(CLIENT_PATHS_KEY, "client") == CLIENT_PATH

    private fun dword(editor: WineRegistryEditor, name: String): Int? =
        runCatching { editor.getDwordValue(SERVICE_KEY, name) }.getOrNull()

    private fun installDummy(context: Context, dest: File): Boolean {
        if (dest.isFile && dest.length() > REAL_SERVICE_MIN_SIZE) {
            Timber.tag(TAG).i("Real GalaxyCommunication.exe present in prefix, leaving it alone")
            return false
        }
        val bytes = context.assets.open(DUMMY_ASSET).use { it.readBytes() }
        if (dest.isFile && dest.length() == bytes.size.toLong() && dest.readBytes().contentEquals(bytes)) return true
        val dir = dest.parentFile ?: return false
        dir.mkdirs()
        val tmp = File(dir, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            Timber.tag(TAG).w("Failed to install GalaxyCommunication.exe into prefix")
            return false
        }
        return true
    }

    private fun resolveUsername(home: File, userId: String, accessToken: String): String {
        val cache = File(home, "gog_user.json")
        val cached = runCatching { JSONObject(cache.readText()) }.getOrNull()
        if (cached != null && cached.optString("user_id") == userId) {
            val name = cached.optString("username")
            if (name.isNotBlank()) return name
        }
        val fetched = runCatching { fetchUsername(userId, accessToken) }
            .onFailure { Timber.tag(TAG).w("GOG username lookup failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (fetched.isNullOrBlank()) return USERNAME_FALLBACK
        runCatching { cache.writeText(JSONObject().put("user_id", userId).put("username", fetched).toString()) }
        return fetched
    }

    private fun fetchUsername(userId: String, accessToken: String): String? {
        val request = Request.Builder()
            .url("https://users.gog.com/users/$userId")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return usernameClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            JSONObject(body).optString("username").ifBlank { null }
        }
    }

    private fun reap(p: Process) {
        runCatching {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(1, TimeUnit.SECONDS)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to stop Galaxy service") }
    }

    private fun pidOf(p: Process): Int? =
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
}
