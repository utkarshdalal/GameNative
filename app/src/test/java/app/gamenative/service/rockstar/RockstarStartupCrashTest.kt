package app.gamenative.service.rockstar

import app.gamenative.service.rockstar.RockstarStartupCrash.Kind
import app.gamenative.service.rockstar.RockstarStartupCrash.Verdict
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RockstarStartupCrashTest {
    @get:Rule val temporary = TemporaryFolder()

    private val redirectorStart = """
        [2026-11-22 11:24:09.367] [   448] [  1C4] Launching...
        [2026-11-22 11:24:09.368] [   448] [  1C4] Running Social Club build.
        [2026-11-22 11:24:09.369] [   448] [  1C4] Preparing Steam launch
        [2026-11-22 11:24:09.374] [   448] [  1C4] Launching 'c:\program files (x86)\steam\steamapps\common\grand theft auto v\.gamenative-rockstar\launcher.exe' with arguments: -skipPatcherCheck -skipInstallers @args.txt
        [2026-11-22 11:24:09.412] [   448] [  1C4] Waiting for exit...
    """.trimIndent()

    private fun redirectorExited(code: Long) = redirectorStart + "\n" + """
        [2026-11-22 11:24:09.666] [   448] [  1C4] Application exited with code $code
        [2026-11-22 11:24:09.667] [   448] [  1C4] Error: Unable to launch game, please try reinstalling the game.
    """.trimIndent()

    private val helperStart = """
        [3822688881] ---- rgscstub start (pid 496) ----
        [3822688881] image=C:\program files (x86)\Steam\steamapps\common\grand theft auto v\.gamenative-rockstar\Launcher.exe
        [3822688907] status: GameNative is starting the game. This window closes by itself.
        [3832501011] game pid 524
        [3832501011] waiting for the game to connect
    """.trimIndent()

    private val helperConnected = helperStart + "\n" + """
        [3832607447] game instance connected (pid 520)
        [3832607450] status: The game is running. Signing in to Rockstar Games...
    """.trimIndent()

    private val helperGaveUp = helperStart + "\n" + """
        [3832502319] game closed while starting (0xC0000005) after 812 ms, 3 times in a row; giving up
        [3832502415] Launcher.exe exited with 7
    """.trimIndent()

    private val helperCrashBeforeConnection = helperStart + "\n" + """
        [3832502319] game exited before launcher connection: 0xC0000005
        [3832502369] status: The game closed before it connected to the launcher (0xC0000005). Try launching again.
        [3832502370] game connection failed (997)
        [3832502415] Launcher.exe exited with 7
    """.trimIndent()

    @Test fun helperThatDiesBeforeItsFirstLineIsAStartupCrash() {
        assertEquals(Verdict.Crashed(Kind.HELPER_CRASHED), RockstarStartupCrash.decide("", redirectorExited(3221225477), "launched", null))
    }

    @Test fun everyFatalStatusTheHelperUsesCounts() {
        for (code in listOf(0xC000001DL, 0xC00000FDL, 0xC0000409L, 0xC0000374L, 0x80000003L)) {
            assertEquals(Verdict.Crashed(Kind.HELPER_CRASHED), RockstarStartupCrash.decide("", redirectorExited(code), "launched", null))
        }
    }

    @Test fun reRunChildThatCrashesStillCounts() {
        // The 32-bit helper starts itself again under the Launcher.exe name and returns the child's status.
        val parentOnly = """
            [3822688140] ---- rgscstub start (pid 464) ----
            [3822688425] re-running as Launcher.exe: "C:\program files (x86)\Steam\steamapps\common\grand theft auto iv\GTAIV\.gamenative-rockstar\Launcher.exe" -skipPatcherCheck
            [3822688700] Launcher.exe exited with 3221225477
        """.trimIndent()
        assertEquals(Verdict.Crashed(Kind.HELPER_CRASHED), RockstarStartupCrash.decide(parentOnly, redirectorExited(3221225477), "launched", null))
    }

    @Test fun helperThatGaveUpOnTheGameCounts() {
        assertEquals(Verdict.Crashed(Kind.HELPER_GAVE_UP), RockstarStartupCrash.decide(helperGaveUp, redirectorExited(7), "launched", null))
        assertEquals(
            Verdict.Crashed(Kind.HELPER_GAVE_UP),
            RockstarStartupCrash.decide(helperCrashBeforeConnection, redirectorExited(7), "launched", null),
        )
    }

    @Test fun exitSevenWithoutACrashIsSomethingElse() {
        val notConnected = helperStart + "\n" + """
            [3832502319] game exited before launcher connection: 0x00000001
            [3832502415] Launcher.exe exited with 7
        """.trimIndent()
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide(notConnected, redirectorExited(7), "launched", null))
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide(helperStart, redirectorExited(7), "launched", 20_000))
    }

    @Test fun offlineGiveUpExitsZeroAndIsLeftAlone() {
        val offline = helperStart + "\n" + """
            [3832502319] game closed while starting (0xC0000005) after 812 ms, 3 times in a row; giving up
            [3832502320] offline: the game closed while starting (0xC0000005), exiting with 0
        """.trimIndent()
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide(offline, redirectorExited(0), "launched", 25_000))
    }

    @Test fun unsupportedBuildAndOtherExitsAreLeftAlone() {
        for (code in listOf(0L, 2L, 3L, 8L, 0xC000013AL)) {
            assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide(helperStart, redirectorExited(code), "launched", 10_000))
        }
    }

    @Test fun connectedGameIsNeverACrash() {
        val crashedLater = helperConnected + "\n[3832900000] game exited with 3221225477\n[3832900010] Launcher.exe exited with 0"
        assertEquals(Verdict.GameConnected, RockstarStartupCrash.decide(crashedLater, redirectorExited(3221225477), "launched", 12_000))
        assertEquals(Verdict.GameConnected, RockstarStartupCrash.decide(helperConnected, null, "launched", 5_000))
    }

    @Test fun redirectorStillWaitingIsUndecided() {
        assertEquals(Verdict.Undecided, RockstarStartupCrash.decide(helperStart, redirectorStart, "launched", null))
        assertEquals(Verdict.Undecided, RockstarStartupCrash.decide("", null, "launching", null))
    }

    @Test fun sessionThatEndsSoonWithNoOutputIsARedirectorCrash() {
        assertEquals(Verdict.Crashed(Kind.REDIRECTOR_CRASHED), RockstarStartupCrash.decide("", null, "launched", 9_500))
        assertEquals(Verdict.Crashed(Kind.REDIRECTOR_CRASHED), RockstarStartupCrash.decide("", null, "launching", 14_000))
    }

    @Test fun sessionEndWithoutSteamStartingTheGameIsLeftAlone() {
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide("", null, "failed launch status=35", 9_000))
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide("", null, "logon", 9_000))
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide("", null, null, 9_000))
    }

    @Test fun lateOrHelperBackedSessionEndIsLeftAlone() {
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide("", null, "launched", 45_000))
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide(helperStart, null, "launched", 9_000))
    }

    @Test fun redirectorLogWithoutAnExitAtSessionEndIsLeftAlone() {
        assertEquals(Verdict.OtherOutcome, RockstarStartupCrash.decide(helperStart, redirectorStart, "launched", 9_000))
    }

    @Test fun readsTheLastUnsignedExitCode() {
        assertEquals(3221225477L, RockstarStartupCrash.redirectorExitCode(redirectorExited(7) + "\n" + "Application exited with code 3221225477"))
        assertNull(RockstarStartupCrash.redirectorExitCode(redirectorStart))
    }

    // The watch, end to end over files laid out as on the device.

    private lateinit var installDir: File
    private lateinit var prefix: File

    private fun stubLog() = File(prefix, "drive_c/users/xuser/AppData/Local/Rockstar Games/Launcher/stub.log")
    private fun helperLog() = File(installDir, ".gamenative-rockstar/rgscstub.log")
    private fun status() = File(prefix, "drive_c/Program Files (x86)/Steam/steamhost_status")

    private fun layout() {
        installDir = temporary.newFolder("Grand Theft Auto V")
        prefix = temporary.newFolder("prefix")
        helperLog().apply { parentFile!!.mkdirs(); writeText(helperConnected + "\n") }
        stubLog().apply { parentFile!!.mkdirs(); writeText(redirectorExited(3221225477)); setLastModified(stubModified) }
        status().apply { parentFile!!.mkdirs(); writeText("launched") }
    }

    private var stubModified = 1_000_000L

    private fun crashedLaunch(): RockstarStartupWatch.Outcome? {
        RockstarStartupWatch.start(installDir, prefix.path) {}
        stubModified += 1_000L
        stubLog().apply { writeText(redirectorExited(3221225477)); setLastModified(stubModified) }
        RockstarStartupWatch.sessionEnded(endedByItself = true)
        return RockstarStartupWatch.takeOutcome()
    }

    @After fun clearWatch() {
        RockstarStartupWatch.sessionEnded(endedByItself = false)
        RockstarStartupWatch.takeOutcome()
    }

    @Test fun earlierSessionsInTheLogsDoNotCount() {
        layout()
        // The old stub.log holds a crash and the helper log an earlier connection; neither is this launch.
        status().writeText("logon")
        RockstarStartupWatch.start(installDir, prefix.path) {}
        RockstarStartupWatch.sessionEnded(endedByItself = true)
        assertNull(RockstarStartupWatch.takeOutcome())
    }

    @Test fun earlierConnectionDoesNotHideThisCrash() {
        layout()
        assertEquals(RockstarStartupWatch.Outcome.Relaunch(installDir.absolutePath, 2, 3), crashedLaunch())
    }

    @Test fun relaunchesTwiceThenGivesUp() {
        layout()
        val first = crashedLaunch()
        assertEquals(RockstarStartupWatch.Outcome.Relaunch(installDir.absolutePath, 2, 3), first)
        RockstarStartupWatch.markAutomaticRelaunch(installDir.absolutePath)
        assertEquals(RockstarStartupWatch.Outcome.Relaunch(installDir.absolutePath, 3, 3), crashedLaunch())
        RockstarStartupWatch.markAutomaticRelaunch(installDir.absolutePath)
        assertEquals(RockstarStartupWatch.Outcome.GaveUp(installDir.absolutePath, 3), crashedLaunch())
    }

    @Test fun playersOwnLaunchStartsTheCountAgain() {
        layout()
        crashedLaunch()
        RockstarStartupWatch.markAutomaticRelaunch(installDir.absolutePath)
        crashedLaunch()
        // No mark: the player launched it.
        assertEquals(RockstarStartupWatch.Outcome.Relaunch(installDir.absolutePath, 2, 3), crashedLaunch())
    }

    @Test fun playerExitInsideTheWindowIsNotARelaunch() {
        layout()
        RockstarStartupWatch.start(installDir, prefix.path) {}
        RockstarStartupWatch.sessionEnded(endedByItself = false)
        assertNull(RockstarStartupWatch.takeOutcome())
    }
}
