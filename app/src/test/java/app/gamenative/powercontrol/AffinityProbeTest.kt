package app.gamenative.powercontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffinityProbeTest {

    private fun proc(vararg args: String, pid: Int = 100) =
        AffinityProbe.parseProc(pid, args.joinToString("\u0000", postfix = "\u0000").toByteArray())!!

    @Test
    fun `wine process runs its windows path`() {
        val p = proc("C:\\Games\\NFS\\speed.exe")
        assertEquals("speed.exe", p.exe)
        assertEquals("C:\\Games\\NFS\\speed.exe", p.path)
    }

    @Test
    fun `arguments in the same argument are cut off`() {
        assertEquals("game.exe", proc("C:\\Program Files\\Game\\game.exe -windowed -dx9").exe)
    }

    @Test
    fun `first exe wins over the ones it is given`() {
        assertEquals("start.exe", proc("C:\\windows\\system32\\start.exe", "/unix", "C:\\Games\\speed.exe").exe)
    }

    @Test
    fun `exe inside a directory name is not taken for the executable`() {
        assertEquals("game.exe", proc("C:\\my.exe.games\\game.exe").exe)
    }

    @Test
    fun `native process runs its first argument`() {
        assertEquals("wineserver", proc("/data/app/imagefs/usr/bin/wineserver", "--foreground").exe)
    }

    @Test
    fun `empty cmdline is no process`() {
        assertNull(AffinityProbe.parseProc(1, ByteArray(0)))
    }

    @Test
    fun `wine infrastructure is background`() {
        assertTrue(AffinityProbe.isBackgroundProcess(proc("C:\\windows\\system32\\services.exe"), "speed.exe", null))
        assertTrue(AffinityProbe.isBackgroundProcess(proc("C:\\windows\\system32\\rundll32.exe"), "speed.exe", null))
        assertTrue(AffinityProbe.isBackgroundProcess(proc("winhandler.exe"), "speed.exe", null))
        assertTrue(AffinityProbe.isBackgroundProcess(proc("/usr/bin/wineserver"), "speed.exe", null))
        assertTrue(AffinityProbe.isBackgroundProcess(proc("/system/bin/linker64", "/lib/libsteambootstrap.so"), null, null))
    }

    @Test
    fun `game and other exes are not background`() {
        assertFalse(AffinityProbe.isBackgroundProcess(proc("C:\\Games\\NFS\\speed.exe"), "SPEED.EXE", null))
        assertFalse(AffinityProbe.isBackgroundProcess(proc("C:\\windows\\system32\\explorer.exe", pid = 7), null, 7))
        assertFalse(AffinityProbe.isBackgroundProcess(proc("D:\\Build\\Windows\\realgame.exe"), "launcher.exe", null))
        assertFalse(AffinityProbe.isBackgroundProcess(proc("C:\\Program Files (x86)\\Steam\\steam.exe"), "speed.exe", null))
    }
}
