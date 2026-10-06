package app.gamenative.utils

import java.util.TreeMap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfProcStatTest {

    private val statTail =
        "S 1 1234 1234 0 -1 4194560 2000 0 0 0 150 50 0 0 20 0 7 0 12345 10000000 500 " +
            "18446744073709551615 1 1 0 0 0 0 0 4096 0 0 0 0 17 3 0\n"

    @Test
    fun parsesProcStatLine() {
        val fields = ProcStatParser.parse("1234 (wineserver) $statTail")
        assertNotNull(fields)
        assertEquals("wineserver", fields!!.comm)
        assertEquals('S', fields.state)
        assertEquals(150L, fields.utime)
        assertEquals(50L, fields.stime)
        assertEquals(7, fields.threads)
        assertEquals(3, fields.processor)
    }

    @Test
    fun parsesCommWithSpacesAndParens() {
        val fields = ProcStatParser.parse("77 (Game (x) Thread) $statTail")
        assertEquals("Game (x) Thread", fields!!.comm)
        assertEquals(150L, fields.utime)
    }

    @Test
    fun rejectsMalformedStat() {
        assertNull(ProcStatParser.parse(""))
        assertNull(ProcStatParser.parse("12 wineserver S 1 2 3"))
        assertNull(ProcStatParser.parse("12 (wineserver) S 1 2 3"))
        assertNull(ProcStatParser.parse("12 (wineserver) S 1 1 1 0 -1 0 0 0 0 0 abc 50 0 0 20 0 7 0 1"))
    }

    @Test
    fun cpuPercentFromTickDelta() {
        assertEquals(100, ProcStatParser.cpuPercent(200L, 2.0, 100L))
        assertEquals(16, ProcStatParser.cpuPercent(80L, 5.0, 100L))
        assertEquals(400, ProcStatParser.cpuPercent(2000L, 5.0, 100L))
        assertEquals(0, ProcStatParser.cpuPercent(-5L, 5.0, 100L))
        assertEquals(0, ProcStatParser.cpuPercent(100L, 0.0, 100L))
        assertEquals(0, ProcStatParser.cpuPercent(100L, 1.0, 0L))
    }

    @Test
    fun parsesContextSwitches() {
        val status = sequenceOf(
            "Name:\twineserver",
            "Threads:\t1",
            "voluntary_ctxt_switches:\t12345",
            "nonvoluntary_ctxt_switches:\t67",
        )
        assertArrayEquals(longArrayOf(12345L, 67L), ProcStatParser.parseCtxtSwitches(status))
        assertNull(ProcStatParser.parseCtxtSwitches(sequenceOf("voluntary_ctxt_switches:\t1")))
    }

    private fun cmdline(vararg args: String) = args.joinToString("\u0000", postfix = "\u0000")

    @Test
    fun namesGameLaunchedThroughLinkerAndWine() {
        val name = ProcStatParser.processName(
            cmdline(
                "/system/bin/linker64",
                "/data/user/0/app.gamenative/files/imagefs/proton/bin/wine",
                "C:\\Games\\Hustle\\YourOnlyMoveIsHUSTLE.exe",
                "-windowed",
            ),
            "linker64",
        )
        assertEquals("YourOnlyMoveIsHUSTLE.exe", name)
    }

    @Test
    fun namesWineserverLaunchedThroughLinker() {
        val name = ProcStatParser.processName(
            cmdline("/system/bin/linker64", "/data/user/0/app.gamenative/files/imagefs/proton/bin/wineserver", "-p", "-f"),
            "linker64",
        )
        assertEquals("wineserver", name)
    }

    @Test
    fun namesSteamExeWithBackslashPath() {
        val name = ProcStatParser.processName(
            cmdline("/system/bin/linker", "/opt/wine/bin/wine64", "C:\\Program Files (x86)\\Steam\\steam.exe", "-silent"),
            "linker",
        )
        assertEquals("steam.exe", name)
    }

    @Test
    fun namesExplorerWithDesktopArgument() {
        val name = ProcStatParser.processName(
            cmdline("C:\\windows\\system32\\explorer.exe", "/desktop=shell,1280x720"),
            "explorer.exe",
        )
        assertEquals("explorer.exe", name)
        assertEquals(
            "explorer.exe",
            ProcStatParser.processName(
                cmdline("/system/bin/linker64", "/imagefs/bin/wine-preloader", "/imagefs/bin/wine", "explorer.exe", "/desktop=shell"),
                "linker64",
            ),
        )
    }

    @Test
    fun namesPlainAndroidProcess() {
        assertEquals("app.gamenative:pulse", ProcStatParser.processName(cmdline("app.gamenative:pulse"), "app.gamenative:p"))
        assertEquals("pulseaudio", ProcStatParser.processName(cmdline("/data/app/lib/pulseaudio", "-n"), "pulseaudio"))
    }

    @Test
    fun fallsBackToCommOnlyWhenCmdlineIsEmpty() {
        assertEquals("kworker", ProcStatParser.processName("", "kworker"))
        assertEquals("linker64", ProcStatParser.processName(cmdline("/system/bin/linker64"), "linker64"))
    }

    @Test
    fun helperProcessSaturationReplacesNoSaturatedResource() {
        val windows = (1..6).map { i ->
            PerfProcessWindow(
                t = i * 5,
                durationMs = 5000L,
                processes = listOf(
                    PerfProcess("wineserver", 50, 80, 12),
                    PerfProcess("YourOnlyMoveIsHUSTLE.exe", 100, 17, 400),
                    PerfProcess("steam.exe", 60, 5, 90),
                ),
            )
        }
        val verdict = PerfVerdicts.compute(run(windows))
        val signals = (0 until verdict.getJSONArray("signals").length()).map { verdict.getJSONArray("signals").getString(it) }
        val notes = (0 until verdict.getJSONArray("notes").length()).map { verdict.getJSONArray("notes").getString(it) }
        assertTrue(signals.contains("helperProcessBound"))
        assertFalse(signals.contains("notSaturated"))
        assertTrue(
            notes.contains(
                "wineserver saturated (80% CPU) while the game used 17%: an IPC or helper-process bottleneck, not the game's own work",
            ),
        )
        assertFalse(notes.any { it.contains("no saturated resource") })
        val avg = verdict.getJSONArray("processCpuAvg")
        assertEquals("YourOnlyMoveIsHUSTLE.exe", avg.getJSONObject(0).getString("name"))
        assertEquals(17, avg.getJSONObject(0).getInt("cpu"))
        assertEquals("wineserver", avg.getJSONObject(1).getString("name"))
        assertEquals(80, avg.getJSONObject(1).getInt("cpu"))
    }

    @Test
    fun noHelperSignalWhenGameIsBusy() {
        val windows = (1..6).map { i ->
            PerfProcessWindow(
                t = i * 5,
                durationMs = 5000L,
                processes = listOf(
                    PerfProcess("YourOnlyMoveIsHUSTLE.exe", 100, 90, 400),
                    PerfProcess("wineserver", 50, 45, 12),
                ),
            )
        }
        val signals = PerfVerdicts.compute(run(windows, gameCpu = 90)).getJSONArray("signals")
        assertFalse((0 until signals.length()).any { signals.getString(it) == "helperProcessBound" })
    }

    @Test
    fun noSaturatedResourceStillReportedWithoutHelperLoad() {
        val signals = PerfVerdicts.compute(run(emptyList())).getJSONArray("signals")
        assertEquals("notSaturated", signals.getString(0))
    }

    private fun run(windows: List<PerfProcessWindow>, gameCpu: Int = 17): PerfRun {
        val samples = (1..30).map { t ->
            PerfSample(
                t = t,
                fps = 20f,
                frameP50Ms = null,
                frameP99Ms = null,
                frameMaxMs = null,
                cpuTotal = 30,
                iowait = 0,
                cores = null,
                clusterCurMhz = null,
                clusterMaxMhz = null,
                gpuBusy = 30,
                gpuMhz = null,
                thermalStatus = 0,
                thermalHeadroom = null,
                cpuTempC = null,
                batteryTempC = null,
                skinTempC = null,
                availMb = 4000,
                lowMemory = false,
                pssMb = null,
                gameRssMb = null,
                game = PerfGame(100, "YourOnlyMoveIsHUSTLE.exe", gameCpu, 20, 0, emptyList()),
                wineserverCpu = null,
                procs = emptyList(),
                capActive = false,
            )
        }
        return PerfRun(
            intervalMs = 1000L,
            runLengthSec = 30,
            refreshRateHz = 60f,
            clusters = emptyList(),
            samples = samples,
            histogramMs = TreeMap(),
            totalFrames = 0L,
            vsyncMultipleFrames = 0L,
            thermalTransitions = emptyList(),
            powerControl = null,
            installPath = null,
            installLocation = null,
            totalMemMb = null,
            processWindows = windows,
        )
    }
}
