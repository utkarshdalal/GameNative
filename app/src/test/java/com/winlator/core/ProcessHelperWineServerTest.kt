package com.winlator.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessHelperWineServerTest {
    @Test
    fun `a running server counts`() {
        assertTrue(ProcessHelper.isWineServerCmdline(arrayOf("wineserver", "/data/proton/bin/wineserver")))
        assertTrue(ProcessHelper.isWineServerCmdline(arrayOf("/data/proton/bin/wineserver")))
        assertTrue(ProcessHelper.isWineServerCmdline(arrayOf("/data/proton/bin/wineserver", "-d0")))
    }

    @Test
    fun `a kill or wait client does not count`() {
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("/system/bin/linker64", "/data/proton/bin/wineserver", "-k")))
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("wineserver", "-w")))
    }

    @Test
    fun `other wine processes do not count`() {
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("wine", "C:\\windows\\system32\\services.exe")))
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("/system/bin/linker64", "/data/proton/bin/wine", "explorer", "-k")))
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf()))
    }
}
