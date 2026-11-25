package app.gamenative.service.xbox

import android.content.Context
import java.io.File
import timber.log.Timber

/** What the shared launch path needs for an Xbox title: the executable, its folder, and environment additions. */
data class XboxLaunchPlan(
    /** Executable relative to the install folder, Windows separators. */
    val executableRelative: String,
    /** Working directory relative to the install folder, Windows separators; empty for the install root. */
    val workingDirRelative: String,
    /** Merged into the container environment; WINEDLLOVERRIDES entries are appended to the existing value. */
    val environment: Map<String, String>,
)

/**
 * Prepares a launch: the runtime stand-in goes into system32 and beside the game executable (the game's
 * runtime loader looks the DLL up by name), with xboxhost.ini carrying the package identity and the loopback
 * endpoint for account tokens. Nothing in Wine changes; the DLL is a native file with a native override.
 */
object XboxLaunchSupport {
    const val PROGRAM_DATA_DIR = "ProgramData/GameNative/xbox"
    const val INI = "xboxhost.ini"

    @Volatile private var server: XboxRuntimeServer? = null

    suspend fun prepare(
        context: Context,
        prefixDriveC: File,
        installDir: File,
        installDirWindows: String,
        install: XboxInstallRecord,
        tokens: RuntimeMsaTokenProvider,
        onProgress: (Float) -> Unit = {},
    ): XboxLaunchPlan {
        XboxHelperArchive.downloadAndExtract(context, onProgress)
        val config = MicrosoftGameConfig.read(installDir)
        val exe = install.executableOverride ?: config?.pcExecutable?.name
            ?: error("The package declares no executable")
        val exeRelative = exe.replace('/', '\\').trimStart('\\')
        require(!exeRelative.split('\\').any { it == ".." }) { "Executable path leaves the install folder" }
        val exeDirRelative = exeRelative.substringBeforeLast('\\', "")
        val exeDir = if (exeDirRelative.isEmpty()) installDir else File(installDir, exeDirRelative.replace('\\', '/'))

        val runtime = XboxRuntimeServer(tokens)
        stop()
        val port = runtime.start()
        server = runtime

        val ini = buildIni(
            port = port,
            session = runtime.session,
            install = install,
            config = config,
            installDirWindows = installDirWindows,
            logPath = "C:\\$PROGRAM_DATA_DIR\\xboxhost.log".replace('/', '\\'),
        )
        val dll = XboxHelperArchive.runtimeDll(context.filesDir)
        val system32 = File(prefixDriveC, "windows/system32")
        val programData = File(prefixDriveC, PROGRAM_DATA_DIR)
        for (dir in listOf(system32, exeDir)) {
            dll.copyTo(File(dir, XboxHelperArchive.RUNTIME_DLL), overwrite = true)
        }
        check(programData.isDirectory || programData.mkdirs()) { "Cannot create the runtime configuration folder" }
        for (dir in listOf(programData, exeDir)) File(dir, INI).writeText(ini)
        val proxied = runCatching {
            XboxEarlyLoadProxy.install(exeDir, File(installDir, exeRelative.replace('\\', '/')), XboxHelperArchive.proxyTemplate(context.filesDir))
        }.onFailure { Timber.w(it, "Xbox: early-load proxy not installed") }.getOrNull()
        Timber.i(
            "Xbox: runtime prepared for %s (%s), exe=%s, early load via %s",
            install.storeId, config?.packageFamilyName, exeRelative, proxied?.original ?: "none",
        )

        return XboxLaunchPlan(
            executableRelative = exeRelative,
            workingDirRelative = exeDirRelative,
            environment = mapOf("WINEDLLOVERRIDES" to "xgameruntime=n"),
        )
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** Removes the per-game copies written by [prepare] and puts back the game's own DLL; the prefix copy stays. */
    fun cleanInstallFolder(installDir: File, executableRelative: String) {
        val exeDir = executableRelative.substringBeforeLast('\\', "").replace('\\', '/')
        val dir = if (exeDir.isEmpty()) installDir else File(installDir, exeDir)
        XboxEarlyLoadProxy.restore(dir)
        File(dir, XboxHelperArchive.RUNTIME_DLL).delete()
        File(dir, INI).delete()
    }

    internal fun buildIni(
        port: Int,
        session: String,
        install: XboxInstallRecord,
        config: MicrosoftGameConfig?,
        installDirWindows: String,
        logPath: String,
    ): String {
        val values = linkedMapOf(
            "port" to port.toString(),
            "session" to session,
            "storeId" to (config?.storeId ?: install.storeId),
            "titleId" to config?.titleId.orEmpty(),
            "msaAppId" to config?.msaAppId.orEmpty(),
            "packageFamilyName" to (config?.packageFamilyName ?: install.packageFamilyName.orEmpty()),
            "packageFullName" to config?.packageFullName.orEmpty(),
            "contentId" to install.contentId,
            "installDir" to installDirWindows.trimEnd('\\'),
            "sandbox" to "RETAIL",
            "packageIdentity" to "1",
            "log" to "1",
            "logPath" to logPath,
        )
        return values.entries.joinToString("\r\n", postfix = "\r\n") { (k, v) -> "$k=${v.replace("\r", "").replace("\n", "")}" }
    }
}
