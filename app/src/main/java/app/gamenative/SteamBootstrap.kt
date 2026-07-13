package app.gamenative

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

object SteamBootstrap {

    sealed class ProcessStatus {
        data object Init : ProcessStatus()
        data class Failed(val reason: String) : ProcessStatus()
        data object Ready : ProcessStatus()
        data object Closed : ProcessStatus()

        companion object {
            fun fromString(value: String): ProcessStatus {
                return when {
                    value == "INIT" -> Init
                    value.startsWith("FAILED:") -> Failed(value.substringAfter("FAILED:"))
                    value == "READY" -> Ready
                    value == "CLOSED" -> Closed
                    else -> Init
                }
            }
        }

        override fun toString(): String = when (this) {
            is Init -> "INIT"
            is Failed -> "FAILED:$reason"
            is Ready -> "READY"
            is Closed -> "CLOSED"
        }
    }

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var sqliteCompatLink: String? = null

    @Volatile
    private var hostProcess: Process? = null

    @Volatile
    private var hostCfg: HostCfg? = null

    private data class HostCfg(
        val context: Context,
        val libPath: String,
        val home: String,
        val master: String,
        val clientService: String,
        val extraEnv: Map<String, String>,
        val account: String?,
        val refreshToken: String?,
        val steamId64: Long,
    )

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
        if (initialized) return 0
        installSqliteCompatLinkIfNeeded(libsteamclientPath)
        hostCfg = HostCfg(
            context = context.applicationContext,
            libPath = libsteamclientPath,
            home = wineSteamRootLinux,
            master = steam3Master,
            clientService = steamClientService,
            extraEnv = extraEnv,
            account = accountName?.takeIf { it.isNotEmpty() },
            refreshToken = refreshToken?.takeIf { it.isNotEmpty() },
            steamId64 = steamId64,
        )
        initialized = true
        return 0
    }

    fun stop() {
        if (!initialized) return
        stopHost()
        initialized = false
        hostCfg = null
        removeSqliteCompatLink()
    }

    private fun stopHost() {
        val p = hostProcess ?: return
        val pid = pidOf(p)
        if (pid != null) {
            runCatching { android.os.Process.sendSignal(pid, OsConstants.SIGTERM) }
            waitExit(p, 6000)
        }
        if (p.isAlive) {
            runCatching { p.destroy() }
            waitExit(p, 2000)
        }
        if (!p.isAlive) hostProcess = null
    }

    private fun waitExit(p: Process, timeoutMs: Int) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    private fun pidOf(p: Process): Int? =
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()

    fun prepareApp(appId: Int) {
        val cfg = hostCfg
        if (!initialized || cfg == null || appId <= 0) return
        stopHost()

        val hostBin = File(cfg.context.applicationInfo.nativeLibraryDir, "libsteambootstrap.so")
        if (!hostBin.exists()) return

        val readyFile = File(cfg.context.cacheDir, "sb_host_ready").apply {
            delete()
            writeText("INIT")
        }
        
        val logFile = File(cfg.context.cacheDir, "sb_host.log")

        val pb = ProcessBuilder(
            hostBin.absolutePath,
            appId.toString(),
            cfg.libPath,
            readyFile.absolutePath,
        )
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.environment().apply {
            put("HOME", cfg.home)
            put("Steam3Master", cfg.master)
            put("SteamClientService", cfg.clientService)
            for ((k, v) in cfg.extraEnv) put(k, v)
            cfg.account?.let { put("SB_ACCOUNT", it) }
            cfg.refreshToken?.let { put("SB_REFRESH_TOKEN", it) }
            if (cfg.steamId64 != 0L) put("SB_STEAMID64", cfg.steamId64.toString())
        }

        val proc = try { pb.start() } catch (_: Throwable) { return }
        hostProcess = proc

        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) return

            val status = getProcessStatus()
            when (status) {
                is ProcessStatus.Ready -> return
                is ProcessStatus.Failed, is ProcessStatus.Closed -> return
                is ProcessStatus.Init -> {}
            }

            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    fun getProcessStatus(): ProcessStatus {
        val cfg = hostCfg ?: return ProcessStatus.Init
        val readyFile = File(cfg.context.cacheDir, "sb_host_ready")

        if (!readyFile.exists()) {
            return if (hostProcess?.isAlive == true) {
                ProcessStatus.Init
            } else {
                ProcessStatus.Closed
            }
        }

        val content = runCatching { readyFile.readText().trim() }.getOrNull() ?: ""
        return if (content.isEmpty()) {
            ProcessStatus.Init
        } else {
            ProcessStatus.fromString(content)
        }
    }

    private fun installSqliteCompatLinkIfNeeded(libsteamclientPath: String) {
        if (!needsSqliteCompatLink()) return
        installSqliteCompatLink(libsteamclientPath)
    }

    private fun needsSqliteCompatLink(): Boolean {
        val is64Bit = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val systemSqlitePath = if (is64Bit) "/system/lib64/libsqlite.so" else "/system/lib/libsqlite.so"
        if (!File(systemSqlitePath).exists()) return false
        return checkSystemSqliteSymbols(systemSqlitePath) ?: true
    }

    private fun checkSystemSqliteSymbols(systemSqlitePath: String): Boolean? {
        val readelfPath = listOf("/system/bin/readelf", "/system/xbin/readelf")
            .firstOrNull { File(it).exists() } ?: return null
        return try {
            val process = ProcessBuilder(readelfPath, "-s", systemSqlitePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) return null
            output.contains("OpenSSL_add_all_algorithms")
        } catch (_: Exception) {
            null
        }
    }

    private fun installSqliteCompatLink(libsteamclientPath: String) {
        val libDir = File(libsteamclientPath).parentFile ?: return
        val link = File(libDir, "libsqlite.so")
        try {
            val existingTarget = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
            if (existingTarget == null && link.exists()) return
            if (existingTarget != "libsqlite3.so.0") {
                if (existingTarget != null) link.delete()
                Os.symlink("libsqlite3.so.0", link.absolutePath)
            }
            sqliteCompatLink = link.absolutePath
        } catch (_: ErrnoException) {
        }
    }

    private fun removeSqliteCompatLink() {
        val path = sqliteCompatLink ?: return
        sqliteCompatLink = null
        if (runCatching { Os.readlink(path) }.getOrNull() != "libsqlite3.so.0") return
        File(path).delete()
    }
}
