package app.gamenative.ui.screen.xr.windows

import android.content.Context
import app.gamenative.CrashHandler
import app.gamenative.ui.util.SnackbarManager
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import com.winlator.core.envvars.EnvVars
import java.io.Closeable
import java.io.File
import timber.log.Timber

class WindowsVrRuntimeService(context: Context) : Closeable {
    private val applicationContext = context.applicationContext
    private val diagnostics = WindowsVrDiagnostics(applicationContext)
    private val payloadManager = WindowsVrPayloadManager(applicationContext, diagnostics)
    private val snapshots = WindowsVrSnapshotProvider()
    private var config: WindowsVrRuntimeConfig? = null
    private var controlServer: WindowsVrControlServer? = null
    private var presentationState = ""
    private var runtimeLogDirectory: File? = null
    private var container: Container? = null

    fun beforeWineSystemSetup(container: Container) {
        this.container = container
        config = WindowsVrRuntimeConfig.from(container)
        if (config?.enabled != true) return
        diagnostics.begin(container.id, container.executablePath, container.execArgs)
        diagnostics.record("activity", "Immersive VR host active")
        diagnostics.record("configuration", "enabled=${config?.enabled} openComposite=${config?.openCompositeEnabled}")
        diagnostics.record("emulation", WindowsVrEmulationDiagnostics.snapshot(container))
    }

    fun afterContainerEnvironmentMerged(env: EnvVars, container: Container) {
        this.container = container
        val active = config ?: WindowsVrRuntimeConfig.from(container).also { config = it }
        if (!active.enabled) return
        val payload = try {
            check(container.wineVersion.contains("arm64ec", ignoreCase = true)) {
                "Windows VR requires a supported ARM64EC Wine or Proton build"
            }
            check(container.dxWrapper.contains("dxvk", ignoreCase = true)) {
                "Windows VR D3D11 requires DXVK native interop"
            }
            diagnostics.record("launch", "Preparing Windows OpenXR payload")
            val prepared = payloadManager.prepare(container)
            if (active.openCompositeEnabled) payloadManager.installOpenComposite(container)
            prepared
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            diagnostics.record("launch", "Windows VR disabled for this session: $reason")
            Timber.w(e, "Windows VR disabled for this session")
            SnackbarManager.show("VR mode unavailable: $reason")
            runCatching { payloadManager.restore() }
            config = active.copy(enabled = false)
            return
        }
        runtimeLogDirectory = payload.prefixDirectory
        listOf("runtime.log", "unix.log").forEach { payload.prefixDirectory.resolve(it).delete() }
        env.put("XR_RUNTIME_JSON", active.runtimeManifest)
        env.put("GAMENATIVE_XR", "1")
        env.put("GAMENATIVE_XR_LOG", "1")
        env.put("GAMENATIVE_XR_SOCKET", active.transportEndpoint)
        env.put("GAMENATIVE_XR_BRIDGE_HOST", "127.0.0.1")
        env.put("GAMENATIVE_XR_BRIDGE_PORT", active.controlPort.toString())
        env.put("GAMENATIVE_XR_CONTROL", "127.0.0.1:${active.controlPort}")
        env.put("GAMENATIVE_XR_TRANSPORT", active.transportEndpoint)
        env.put("GAMENATIVE_XR_RUNTIME_DIR", active.runtimeDirectory)
        env.put("GAMENATIVE_XR_UNIX_LOG", payload.prefixDirectory.resolve("unix.log").path)
        val overrides = env.get("WINEDLLOVERRIDES").split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot {
                val name = it.substringBefore('=')
                name.equals("gamenative_xr_unixbridge", ignoreCase = true) ||
                    name.equals("gameoverlayrenderer", ignoreCase = true) ||
                    name.equals("gameoverlayrenderer64", ignoreCase = true)
            }
            .plus("gameoverlayrenderer=n")
            .plus("gameoverlayrenderer64=n")
            .plus("gamenative_xr_unixbridge=b")
        env.put("WINEDLLOVERRIDES", overrides.joinToString(";"))
        diagnostics.record(
            "environment",
            "runtime=${active.runtimeManifest} transport=${active.transportEndpoint} " +
                "control=127.0.0.1:${active.controlPort}",
        )
        diagnostics.record("effective-launch", WindowsVrEmulationDiagnostics.effective(container))
        diagnostics.record("payload", payloadSummary(payload.prefixDirectory))
    }

    fun beforeGuestProcessStart() {
        val active = config ?: return
        if (!active.enabled || controlServer != null) return
        val server = WindowsVrControlServer(active, diagnostics, snapshots)
        try {
            server.start()
        } catch (e: Exception) {
            runCatching { server.close() }
            val reason = e.message ?: e.javaClass.simpleName
            diagnostics.record("control", "Windows VR disabled for this session: $reason")
            Timber.w(e, "Windows VR control server failed to start")
            SnackbarManager.show("VR mode unavailable: $reason")
            config = active.copy(enabled = false)
            return
        }
        controlServer = server
        diagnostics.record("control", "listening on 127.0.0.1:${active.controlPort}")
    }

    fun onEnvironmentStarted() {
        diagnostics.record("environment", "Wine environment started; waiting for OpenXR")
    }

    fun onPresentationState(state: String) {
        if (presentationState == state) return
        presentationState = state
        diagnostics.record("presentation", state)
    }

    fun onGuestProcessError(error: String) {
        diagnostics.record("guest-error", error)
        captureDiagnostics()
    }

    fun status(): List<String> = diagnostics.snapshot()

    fun exportDiagnostics(): File {
        captureDiagnostics()
        val directory = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "diagnostics")
        check(directory.exists() || directory.mkdirs())
        val report = File(directory, "windows-vr-${System.currentTimeMillis()}.txt")
        diagnostics.record("diagnostics", "exported path=${report.path}")
        report.writeText(
            runCatching { diagnostics.logFile().takeIf(File::isFile)?.readText() }.getOrNull()?.ifBlank { null }
                ?: diagnostics.snapshot().joinToString("\n"),
        )
        return report
    }

    fun attachSession(handle: Long) {
        snapshots.attach(handle)
        diagnostics.record("openxr", "Immersive session attached handle=$handle")
    }

    fun detachSession() {
        snapshots.detach()
        diagnostics.record("openxr", "Immersive session detached")
    }

    private fun captureDiagnostics() {
        diagnostics.record("android", "app log tail:\n${CrashHandler.getAppLogs(800)}")
        val active = container
        if (active != null) {
            val runtime = File(active.rootDir, ".wine/drive_c/gamenative-xr")
            diagnostics.recordFileTail("Windows OpenXR runtime log", runtime.resolve("runtime.log"))
            diagnostics.recordFileTail("Wine OpenXR Unix bridge log", runtime.resolve("unix.log"))
            diagnostics.recordFileTail(
                "ColdClientLoader configuration",
                File(active.rootDir, ".wine/drive_c/Program Files (x86)/Steam/ColdClientLoader.ini"),
            )
            val users = File(active.rootDir, ".wine/drive_c/users")
            val openComposite = users.walkTopDown().maxDepth(6)
                .filter { it.isFile && it.name.equals("opencomposite.log", ignoreCase = true) }
                .maxByOrNull(File::lastModified)
            diagnostics.recordFileTail(
                "OpenComposite log",
                openComposite ?: File(users, "<wine-user>/AppData/Local/OpenComposite/logs/opencomposite.log"),
            )
            val playerLog = users.walkTopDown().maxDepth(6)
                .filter { it.isFile && it.name.equals("Player.log", ignoreCase = true) }
                .maxByOrNull(File::lastModified)
            diagnostics.recordFileTail(
                "latest Unity Player log",
                playerLog ?: File(users, "<wine-user>/AppData/LocalLow/<game>/Player.log"),
            )
        }
        val processes = runCatching {
            ProcessHelper.listSubProcesses().sortedBy { it.pid }.joinToString("\n") { process ->
                val command = runCatching {
                    File("/proc/${process.pid}/cmdline").readBytes().toString(Charsets.UTF_8)
                        .replace('\u0000', ' ').trim()
                }.getOrDefault("<unavailable>")
                val state = runCatching {
                    File("/proc/${process.pid}/status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("State:") }
                    }
                }.getOrNull() ?: "State: <unavailable>"
                val wait = runCatching { File("/proc/${process.pid}/wchan").readText().trim() }
                    .getOrDefault("<unavailable>")
                "pid=${process.pid} ppid=${process.ppid} rss=${process.rssBytes} " +
                    "name=${process.name} $state wchan=$wait cmd=$command"
            }
        }.getOrElse { "<unavailable: ${it.message}>" }
        diagnostics.record("processes", processes.ifBlank { "<none>" })
        diagnostics.recordFileTail(
            "Wine debug log",
            File(applicationContext.getExternalFilesDir(null), "wine_logs/wine_debug.log"),
        )
    }

    private fun payloadSummary(directory: File): String = buildString {
        append("directory=${directory.path}")
        directory.listFiles().orEmpty().sortedBy(File::getName).forEach { file ->
            append(" ${file.name}=")
            append(if (file.isFile) "file(${file.length()} bytes)" else if (file.isDirectory) "directory" else "missing")
        }
    }

    override fun close() {
        diagnostics.record("service", "closing")
        runCatching { controlServer?.close() }.onFailure { Timber.w(it, "Windows VR control server close failed") }
        controlServer = null
        snapshots.detach()
        runCatching { payloadManager.restore() }.onFailure { Timber.w(it, "Windows VR payload restore failed") }
        runtimeLogDirectory = null
        container = null
    }
}
