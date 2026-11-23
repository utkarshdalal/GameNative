package app.gamenative.service.rockstar

/**
 * Whether a Rockstar launch died before the game started, from the helper's log and Rockstar's
 * own launch log.
 *
 * Under emulation a freshly started process sometimes takes an access violation before it does
 * anything, and a plain relaunch works. The helper already starts the game itself again when the
 * game is the one that dies. What is left: the helper (Launcher.exe) dies, and Rockstar's
 * redirector then logs the crash status and shows "Unable to launch game, please try reinstalling
 * the game"; the helper gives up after its own retries (exit 7), with the same message; or the
 * redirector dies before writing anything, and the session ends a few seconds after Steam
 * started the game.
 */
object RockstarStartupCrash {
    /** A session that ends by itself this soon after the game step, with no output from either side, died while starting. */
    const val SESSION_WINDOW_MS = 30_000L

    /** The helper's exit code when the game never connected. */
    const val HELPER_GAME_NOT_CONNECTED = 7L

    enum class Kind { HELPER_CRASHED, HELPER_GAVE_UP, REDIRECTOR_CRASHED }

    sealed interface Verdict {
        /** Nothing decides it yet; the session is still starting. */
        data object Undecided : Verdict

        data object GameConnected : Verdict

        /** The launch ended or is going some other way. */
        data object OtherOutcome : Verdict

        data class Crashed(val kind: Kind) : Verdict
    }

    private const val HELPER_START = "---- rgscstub start"
    private const val GAME_CONNECTED = "game instance connected"
    private const val GAVE_UP = "times in a row; giving up"
    private val redirectorExit = Regex("""Application exited with code (\d+)""")
    private val gameExitBeforeConnection = Regex("""game exited before launcher connection: 0x([0-9A-Fa-f]{1,8})""")

    /** The statuses the helper treats as a start-up crash of the game. */
    private val crashStatuses = setOf(
        0xC0000005L, // access violation
        0xC000001DL, // illegal instruction
        0xC0000096L, // privileged instruction
        0xC00000FDL, // stack overflow
        0xC0000094L, // integer divide by zero
        0xC0000095L, // integer overflow
        0xC0000006L, // in-page error
        0xC0000409L, // stack buffer overrun, fail fast
        0xC0000374L, // heap corruption
        0xC000041DL, // unhandled exception in a callback
        0x80000003L, // breakpoint
        0x80000001L, // guard page
    )

    fun isCrashStatus(code: Long): Boolean = code in crashStatuses

    /** The last exit code the redirector logged for the helper, as the unsigned number it writes. */
    fun redirectorExitCode(stubLog: String): Long? =
        redirectorExit.findAll(stubLog).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()

    /**
     * [helperLog] is what the helper appended to its log since the launch. [stubLog] is Rockstar's
     * stub.log when the redirector wrote it since the launch, else null. [launchStatus] is
     * steamhost's status word. [sessionLengthMs] is the time from the game step to the end of a
     * session that closed by itself; null while the session is open or when the player ended it.
     */
    fun decide(helperLog: String, stubLog: String?, launchStatus: String?, sessionLengthMs: Long?): Verdict {
        if (GAME_CONNECTED in helperLog) return Verdict.GameConnected
        if (stubLog != null) {
            val code = redirectorExitCode(stubLog)
                ?: return if (sessionLengthMs == null) Verdict.Undecided else Verdict.OtherOutcome
            return when {
                isCrashStatus(code) -> Verdict.Crashed(Kind.HELPER_CRASHED)
                code == HELPER_GAME_NOT_CONNECTED && helperGaveUp(helperLog) -> Verdict.Crashed(Kind.HELPER_GAVE_UP)
                else -> Verdict.OtherOutcome
            }
        }
        if (sessionLengthMs == null) return Verdict.Undecided
        val steamStartedIt = launchStatus?.trim()?.startsWith("launch") == true
        return if (steamStartedIt && sessionLengthMs <= SESSION_WINDOW_MS && HELPER_START !in helperLog) {
            Verdict.Crashed(Kind.REDIRECTOR_CRASHED)
        } else {
            Verdict.OtherOutcome
        }
    }

    /** The helper's own retries ran out, or the game crashed before it connected. Offline, the helper exits 0 instead. */
    private fun helperGaveUp(helperLog: String): Boolean =
        GAVE_UP in helperLog ||
            gameExitBeforeConnection.findAll(helperLog).any { isCrashStatus(it.groupValues[1].toLong(16)) }
}
