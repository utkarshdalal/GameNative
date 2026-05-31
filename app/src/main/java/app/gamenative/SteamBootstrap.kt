package app.gamenative

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Bootstraps the Linux-side libsteamclient.so inside the Android process so
 * that Proton's lsteamclient.dll (running in the Wine subprocess we launch
 * for "real Steam" mode) has something to talk to via the Steam IPC sockets
 * exposed by libsteamclient.so itself.
 *
 * This is implemented in native code (see app/src/main/cpp/steambootstrap)
 * because the dlopen + CreateInterface + CreateSteamPipe + ConnectToGlobalUser
 * dance has to happen in a single process where the .so's globals stay live.
 *
 * NOTE on HOME: nativeInit() calls setenv("HOME", ...) inside the Android
 * process (the only place that affects libsteamclient.so's view of HOME). The
 * Wine subprocess we spawn afterwards is launched with an explicit envp via
 * ProcessHelper.exec(...), so its HOME is unchanged by what we do here.
 */
object SteamBootstrap {
    private const val TAG = "SteamBootstrap"

    @Volatile
    private var initialized: Boolean = false

    /** Absolute path of the libsqlite.so compat symlink we created in [start], so [stop] can
     *  remove it. Null when no symlink was created (or already torn down). */
    @Volatile
    private var sqliteCompatLink: String? = null

    init {
        try {
            System.loadLibrary("steambootstrap")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libsteambootstrap.so", t)
        }
    }

    @JvmStatic
    private external fun nativeInit(
        context: Context,
        libPath: String,
        home: String,
        steam3Master: String,
        steamClientService: String,
        extraEnv: Array<String>,
        accountName: String?,
        refreshToken: String?,
        steamId64: Long,
    ): Int

    @JvmStatic
    private external fun nativeShutdown()

    @JvmStatic
    private external fun nativePrepareApp(appIds: IntArray)

    @JvmStatic
    private external fun nativeSetCloudEnabled(appId: Int, enabled: Boolean)

    /**
     * Boot the native Steam client. Safe to call multiple times: only the
     * first call performs the dlopen + handshake; subsequent calls are no-ops
     * (the native side guards on the global client pointer).
     *
     * @param libsteamclientPath Absolute path to the linux/android libsteamclient.so on disk.
     * @param wineSteamRootLinux Linux path to the dir that mirrors the Wine
     *   `C:\Program Files (x86)` directory. libsteamclient.so reads HOME to
     *   locate `<HOME>/Steam/config/config.vdf`, so this should be the parent
     *   of `Steam/`.
     * @param steam3Master       Value to publish as the `Steam3Master` env var (e.g. "tcp:127.0.0.1:57343").
     * @param steamClientService Value to publish as the `SteamClientService` env var (e.g. "tcp:127.0.0.1:57344").
     * @param extraEnv           Additional env vars to setenv() before the dlopen
     *   (e.g. `_STEAM_SETENV_MANAGER`, `STEAM_BASE_FOLDER`, `BREAKPAD_DUMP_LOCATION`,
     *   `ENABLE_VK_LAYER_VALVE_steam_overlay_1`, `STEAMVIDEOTOKEN`, …).
     * @param accountName        Steam account login name (the username typed at the
     *   Steam login box, NOT the persona name and NOT the email). If null/blank,
     *   the explicit logon is skipped and we fall back to the engine's cached
     *   auto-logon attempt.
     * @param refreshToken       JWT-style refresh token from your existing app
     *   login (the same value normally cached in `config.vdf`'s ConnectCache).
     * @param steamId64          The 64-bit SteamID for that account. 0 disables
     *   the explicit logon path.
     * @return 0 on success, negative status code on failure (see steam_bootstrap.c).
     */
    fun start(
        context: Context,
        libsteamclientPath: String,
        wineSteamRootLinux: String,
        steam3Master: String,
        steamClientService: String,
        extraEnv: Map<String, String> = emptyMap(),
        accountName: String? = null,
        refreshToken: String? = null,
        steamId64: Long = 0L,
    ): Int {
        if (initialized) {
            Log.i(TAG, "Already initialized; skipping nativeInit()")
            return 0
        }

        installSqliteCompatLinkIfNeeded(libsteamclientPath)

        val flat = ArrayList<String>(extraEnv.size * 2)
        for ((k, v) in extraEnv) {
            flat += k
            flat += v
        }

        val rc = try {
            nativeInit(
                context.applicationContext,
                libsteamclientPath,
                wineSteamRootLinux,
                steam3Master,
                steamClientService,
                flat.toTypedArray(),
                accountName?.takeIf { it.isNotEmpty() },
                refreshToken?.takeIf { it.isNotEmpty() },
                steamId64,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit threw", t)
            return -100
        }

        if (rc == 0) {
            initialized = true
            Log.i(TAG, "libsteamclient.so initialized via dlopen at $libsteamclientPath")
        } else {
            Log.e(TAG, "nativeInit failed with rc=$rc (libPath=$libsteamclientPath)")
        }
        return rc
    }

    /**
     * Tear down the native Steam pipe + global user without unloading
     * libsteamclient.so. Call when a real-Steam game session ends so a
     * subsequent [start] call gets a fresh pipe / user.
     *
     * Safe to call when [start] never ran (or failed): the native side guards
     * on the cached pipe / user globals.
     */
    fun stop() {
        if (!initialized) {
            Log.i(TAG, "stop() called but not initialized; skipping")
            return
        }
        try {
            nativeShutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeShutdown threw", t)
        } finally {
            initialized = false
            removeSqliteCompatLink()
        }
    }

    /**
     * Conditionally install `<libsteamclient.so's dir>/libsqlite.so -> libsqlite3.so.0` only if
     * needed on this device.
     *
     * Strategy:
     * 1. Check if system libsqlite.so exists - if not, no symlink needed
     * 2. Use readelf to inspect system libsqlite.so for problematic OpenSSL 1.0.x symbols
     * 3. If readelf unavailable, fall back to conservative approach (create symlink)
     *
     * Why: on devices whose `/system/lib64/libsqlite.so` references the OpenSSL 1.0.x symbol
     * `OpenSSL_add_all_algorithms` (deprecated, gone in OpenSSL 3 which we bundle), the bionic
     * linker chains into that system libsqlite when nothing earlier on `LD_LIBRARY_PATH` provides
     * a `libsqlite.so`, and dlopen of libsteamclient.so fails. Pointing the bare name at our
     * SQLite-3 file short-circuits that fall-through.
     */
    private fun installSqliteCompatLinkIfNeeded(libsteamclientPath: String) {
        if (!needsSqliteCompatLink()) {
            Log.i(TAG, "SQLite compat symlink not needed on this device")
            return
        }
        Log.i(TAG, "SQLite compat symlink needed, installing...")
        installSqliteCompatLink(libsteamclientPath)
    }

    /**
     * Determine if the SQLite compatibility symlink is needed on this device.
     * Uses symbol check to inspect system libsqlite.so for OpenSSL 1.0.x symbols (if readelf available).
     * Falls back to conservative approach (create symlink) if check is unavailable.
     */
    private fun needsSqliteCompatLink(): Boolean {
        val is64Bit = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val systemSqlitePath = if (is64Bit) "/system/lib64/libsqlite.so" else "/system/lib/libsqlite.so"

        val systemSqlite = File(systemSqlitePath)
        if (!systemSqlite.exists()) {
            Log.i(TAG, "System libsqlite.so not found at $systemSqlitePath, symlink not needed")
            return false
        }

        // Check for problematic OpenSSL symbols using readelf
        val symbolCheckResult = checkSystemSqliteSymbols(systemSqlitePath)
        if (symbolCheckResult != null) {
            Log.i(TAG, "Symbol check completed: symlink ${if (symbolCheckResult) "needed" else "not needed"}")
            return symbolCheckResult
        }

        // Conservative fallback: if we can't check, create the symlink to be safe
        Log.i(TAG, "Symbol check unavailable, defaulting to creating symlink (conservative approach)")
        return true
    }

    /**
     * Check if system libsqlite.so contains problematic OpenSSL 1.0.x symbols using readelf.
     * Returns null if readelf is not available or check fails.
     */
    private fun checkSystemSqliteSymbols(systemSqlitePath: String): Boolean? {
        // First check if readelf exists
        val readelfPaths = listOf("/system/bin/readelf", "/system/xbin/readelf")
        val readelfPath = readelfPaths.firstOrNull { File(it).exists() }

        if (readelfPath == null) {
            Log.i(TAG, "readelf not found, skipping symbol check")
            return null
        }

        return try {
            val process = ProcessBuilder(readelfPath, "-s", systemSqlitePath)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Log.w(TAG, "readelf exited with code $exitCode")
                return null
            }

            // Check if the problematic OpenSSL 1.0.x symbol is referenced
            val hasProblematicSymbol = output.contains("OpenSSL_add_all_algorithms")
            Log.i(TAG, "System libsqlite.so ${if (hasProblematicSymbol) "contains" else "does not contain"} OpenSSL_add_all_algorithms")
            hasProblematicSymbol
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system libsqlite symbols: $e")
            null
        }
    }

    /**
     * Install `<libsteamclient.so's dir>/libsqlite.so -> libsqlite3.so.0` for the duration of
     * this bionic-Steam session.
     *
     * The Wine subprocess we spawn later does its own dlopen of the same libsteamclient.so, so
     * the symlink must persist until [stop] runs; we tear it down there to keep it ephemeral
     * for the bionic-Steam session only (a user who flips bionic-Steam off shouldn't be left
     * with a stray libsqlite.so symlink in their imagefs).
     */
    private fun installSqliteCompatLink(libsteamclientPath: String) {
        val libDir = File(libsteamclientPath).parentFile ?: return
        val link = File(libDir, "libsqlite.so")
        try {
            val existingTarget = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
            if (existingTarget == null && link.exists()) {
                Log.w(TAG, "Skipping sqlite compat symlink; ${link.absolutePath} exists and is not a symlink")
                return
            }
            if (existingTarget != "libsqlite3.so.0") {
                if (existingTarget != null) link.delete()
                Os.symlink("libsqlite3.so.0", link.absolutePath)
                Log.i(TAG, "Created sqlite compat symlink ${link.absolutePath} -> libsqlite3.so.0")
            }
            sqliteCompatLink = link.absolutePath
        } catch (e: ErrnoException) {
            Log.w(TAG, "Failed to create sqlite compat symlink at ${link.absolutePath}: $e")
        }
    }

    private fun removeSqliteCompatLink() {
        val path = sqliteCompatLink ?: return
        sqliteCompatLink = null
        // Only unlink if it's still the symlink we placed; don't touch unrelated files.
        val target = runCatching { Os.readlink(path) }.getOrNull()
        if (target != "libsqlite3.so.0") return
        if (File(path).delete()) {
            Log.i(TAG, "Removed sqlite compat symlink $path")
        } else {
            Log.w(TAG, "Failed to remove sqlite compat symlink at $path")
        }
    }

    /**
     * Pre-warm PICS metadata + an encrypted app ticket for [appId] (the
     * parent game) and refresh the per-DLC ownership tickets / SetDlcEnabled
     * for every entry in [dlcAppIds] so the Wine subprocess we spawn
     * afterwards doesn't stall in "Validating Subscriptions" / "creating
     * online session" and the engine has decryption keys for all owned DLC
     * depots. Should be called after [start] returns 0, and before the Wine
     * launch.
     *
     * The native side treats index 0 of the assembled array as the parent
     * (drives PICS refresh + SetLanguage), and indices 1..n as DLC AppIDs
     * (drives SetDlcEnabled + UpdateAppOwnershipTicket per entry).
     *
     * Best-effort: if the engine isn't initialized or not yet logged on,
     * the native side logs a warning and returns without blocking. Safe to
     * call multiple times.
     */
    fun prepareApp(appId: Int, dlcAppIds: IntArray = IntArray(0)) {
        if (!initialized) {
            Log.i(TAG, "prepareApp($appId) called but not initialized; skipping")
            return
        }
        if (appId <= 0) {
            Log.i(TAG, "prepareApp called with appId=$appId; skipping")
            return
        }
        // Assemble [parent, dlc1, dlc2, ...]. Filter junk (<=0) and
        // dedupe so we never accidentally tell the engine "this DLC is the
        // parent" or process the same AppID twice.
        val combined = IntArray(1 + dlcAppIds.size)
        combined[0] = appId
        var written = 1
        for (id in dlcAppIds) {
            if (id <= 0 || id == appId) continue
            var dup = false
            for (j in 1 until written) {
                if (combined[j] == id) { dup = true; break }
            }
            if (!dup) {
                combined[written] = id
                written++
            }
        }
        val finalIds = if (written == combined.size) combined else combined.copyOf(written)

        try {
            nativePrepareApp(finalIds)
        } catch (t: Throwable) {
            Log.e(TAG, "nativePrepareApp threw", t)
        }
    }

    fun setCloudEnabled(appId: Int, enabled: Boolean) {
        if (!initialized) {
            Log.i(TAG, "setCloudEnabled($appId, $enabled) called but not initialized; skipping")
            return
        }
        if (appId <= 0) {
            Log.i(TAG, "setCloudEnabled called with appId=$appId; skipping")
            return
        }
        try {
            nativeSetCloudEnabled(appId, enabled)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeSetCloudEnabled threw", t)
        }
    }
}
