package app.gamenative.service.rockstar

import com.winlator.core.ProcessHelper
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * Follows the start of a Rockstar session and decides, when it ends, whether to start the game
 * again (see [RockstarStartupCrash]). Armed only for the game step of a Rockstar launch through
 * the helper; nothing runs otherwise.
 */
object RockstarStartupWatch {
    const val AUTOMATIC_RELAUNCHES = 2
    private const val POLL_MS = 2_000L
    private const val POLL_WINDOW_MS = 60_000L
    private const val LOG_READ_LIMIT = 1 shl 20
    private const val STUB_LOG = "drive_c/users/xuser/AppData/Local/Rockstar Games/Launcher/stub.log"
    private const val STEAMHOST_STATUS = "drive_c/Program Files (x86)/Steam/steamhost_status"

    sealed interface Outcome {
        val installPath: String

        data class Relaunch(override val installPath: String, val attempt: Int, val attempts: Int) : Outcome

        data class GaveUp(override val installPath: String, val attempts: Int) : Outcome
    }

    private class Launch(
        val installPath: String,
        val helperLog: File,
        val helperLogLength: Long,
        val stubLog: File,
        val stubLogModified: Long,
        val statusFile: File,
        val startedAt: Long,
    )

    private var launch: Launch? = null
    private var crashedWhileOpen: RockstarStartupCrash.Kind? = null
    private var outcome: Outcome? = null
    private var relaunches = 0
    private var automaticRelaunchOf: String? = null

    /** Call as the game step starts, after steamhost's status file is reset. [onCrash] ends the session. */
    @Synchronized
    fun start(installDir: File, wineprefix: String, onCrash: () -> Unit) {
        val installPath = installDir.absolutePath
        // Only a launch this object started keeps the count; any other launch is the player's.
        if (automaticRelaunchOf != installPath) relaunches = 0
        automaticRelaunchOf = null
        outcome = null
        crashedWhileOpen = null
        val helperLog = File(RockstarHelperArchive.titleDir(installDir), "${RockstarHelperDeployment.DIRECTORY}/rgscstub.log")
        val stubLog = File(wineprefix, STUB_LOG)
        val current = Launch(
            installPath = installPath,
            helperLog = helperLog,
            helperLogLength = helperLog.length(),
            stubLog = stubLog,
            stubLogModified = stubLog.lastModified(),
            statusFile = File(wineprefix, STEAMHOST_STATUS),
            startedAt = System.currentTimeMillis(),
        )
        launch = current
        Timber.i("Rockstar: watching the start of %s (automatic relaunches so far: %d)", installDir.name, relaunches)
        thread(name = "rockstar-start-watch", isDaemon = true) { poll(current, onCrash) }
    }

    private fun poll(current: Launch, onCrash: () -> Unit) {
        while (System.currentTimeMillis() - current.startedAt < POLL_WINDOW_MS) {
            Thread.sleep(POLL_MS)
            val crashed = synchronized(this) {
                if (launch !== current) return
                when (val verdict = read(current, null)) {
                    RockstarStartupCrash.Verdict.Undecided -> null
                    RockstarStartupCrash.Verdict.GameConnected -> {
                        relaunches = 0
                        return
                    }
                    RockstarStartupCrash.Verdict.OtherOutcome -> return
                    is RockstarStartupCrash.Verdict.Crashed -> verdict.kind.also { crashedWhileOpen = it }
                }
            } ?: continue
            Timber.w("Rockstar: the launch closed while starting (%s), ending the session", crashed)
            onCrash()
            return
        }
    }

    /** Call when the session ends, before the exit handling reads [takeOutcome]. */
    @Synchronized
    fun sessionEnded(endedByItself: Boolean) {
        val current = launch ?: return
        launch = null
        val sessionLength = System.currentTimeMillis() - current.startedAt
        val verdict = crashedWhileOpen?.let { RockstarStartupCrash.Verdict.Crashed(it) }
            ?: read(current, sessionLength.takeIf { endedByItself })
        crashedWhileOpen = null
        when (verdict) {
            RockstarStartupCrash.Verdict.GameConnected -> relaunches = 0
            is RockstarStartupCrash.Verdict.Crashed -> {
                outcome = if (relaunches < AUTOMATIC_RELAUNCHES) {
                    relaunches++
                    Outcome.Relaunch(current.installPath, relaunches + 1, AUTOMATIC_RELAUNCHES + 1)
                } else {
                    relaunches = 0
                    Outcome.GaveUp(current.installPath, AUTOMATIC_RELAUNCHES + 1)
                }
                Timber.w("Rockstar: closed while starting (%s) after %d ms: %s", verdict.kind, sessionLength, outcome)
            }
            else -> {}
        }
    }

    @Synchronized
    fun takeOutcome(): Outcome? = outcome.also { outcome = null }

    /** The next launch of [installPath] is this object's own relaunch, so it keeps the count. */
    @Synchronized
    fun markAutomaticRelaunch(installPath: String) {
        automaticRelaunchOf = installPath
    }

    /**
     * Waits, at most [timeoutMs], until no Wine process of the previous session is left. A launch
     * started while the last one is still closing runs into it.
     */
    fun awaitSessionGone(timeoutMs: Long = 15_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val left = runCatching { ProcessHelper.listSubProcesses() }.getOrDefault(emptyList()).filter { process ->
                val name = process.name.lowercase()
                ".exe" in name || name.startsWith("wine")
            }
            if (left.isEmpty()) return
            Thread.sleep(500)
        }
        Timber.w("Rockstar: the previous session was still closing after %d ms", timeoutMs)
    }

    private fun read(current: Launch, sessionLengthMs: Long?): RockstarStartupCrash.Verdict {
        val helperLog = readFrom(current.helperLog, current.helperLogLength)
        // The redirector rewrites its log on every start, so a newer file is this launch's.
        val stubLog = current.stubLog.takeIf { it.isFile && it.lastModified() > current.stubLogModified }
            ?.let { readFrom(it, 0).replace("\u0000", "") }
        val status = runCatching { current.statusFile.takeIf { it.isFile }?.readText() }.getOrNull()
        return RockstarStartupCrash.decide(helperLog, stubLog, status, sessionLengthMs)
    }

    /** The text from [offset] on; all of it when the file is now shorter, as after it was replaced. */
    private fun readFrom(file: File, offset: Long): String = runCatching {
        if (!file.isFile) return@runCatching ""
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = if (length < offset) 0L else offset
            val bytes = ByteArray(minOf(length - start, LOG_READ_LIMIT.toLong()).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")
}
