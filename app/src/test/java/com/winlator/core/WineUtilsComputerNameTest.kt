package com.winlator.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.utils.WineComputerNameUtils
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WineUtilsComputerNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun skipOnWindowsHosts() {
        // WineRegistryEditor swaps its edited copy in with File.renameTo, which does not replace an existing file
        // on Windows (it does on Android and Linux). The tests need the real replace behaviour.
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    }

    private fun templateSystemReg(name: String) = """
        WINE REGISTRY Version 2
        ;; All keys relative to \\Machine

        [System\\CurrentControlSet\\Control\\ComputerName\\ComputerName] 1753725033
        #time=1dc0000000000000
        "ComputerName"="$name"

        [System\\CurrentControlSet\\Control\\ComputerName\\ActiveComputerName] 1753725033
        #time=1dc0000000000000
        "ComputerName"="$name"

        [System\\CurrentControlSet\\Services\\Tcpip\\Parameters] 1753725033
        #time=1dc0000000000000
        "Hostname"="$name"
        "NV Hostname"="$name"
    """.trimIndent() + "\n"

    private fun container(root: File): Container {
        val container = mockk<Container>(relaxed = true)
        every { container.rootDir } returns root
        return container
    }

    private fun systemRegIn(root: File) = File(root, ".wine/system.reg")

    private fun computerNameOf(systemReg: File): String? = WineRegistryEditor(systemReg).use {
        it.getStringValue("System\\CurrentControlSet\\Control\\ComputerName\\ComputerName", "ComputerName", null)
    }

    @Test
    fun writesTheStableNameIntoTheContainersSystemReg() {
        val root = createTempDirectory(prefix = "computer-name").toFile()
        val systemReg = systemRegIn(root).apply {
            parentFile!!.mkdirs()
            writeText(templateSystemReg("LOCALHOST"))
        }

        WineUtils.applyComputerName(context, container(root))

        assertEquals(WineComputerNameUtils.defaultName(context), computerNameOf(systemReg))
    }

    @Test
    fun theNameIsWrittenAgainAfterAWineSwitchReplacedSystemReg() {
        val root = createTempDirectory(prefix = "computer-name").toFile()
        val systemReg = systemRegIn(root).apply { parentFile!!.mkdirs() }
        val container = container(root)
        val expected = WineComputerNameUtils.defaultName(context)

        // New container: the template's name is replaced.
        systemReg.writeText(templateSystemReg("LOCALHOST"))
        WineUtils.applyComputerName(context, container)
        assertEquals(expected, computerNameOf(systemReg))

        // Wine switch: the new Wine's template replaces system.reg, bringing back a build-host id.
        systemReg.writeText(templateSystemReg("buildhost-1234"))
        assertEquals("buildhost-1234", computerNameOf(systemReg))
        WineUtils.applyComputerName(context, container)
        assertEquals(expected, computerNameOf(systemReg))
    }

    @Test
    fun onlyComputerNameIsWritten() {
        val root = createTempDirectory(prefix = "computer-name").toFile()
        val systemReg = systemRegIn(root).apply {
            parentFile!!.mkdirs()
            writeText(templateSystemReg("LOCALHOST"))
        }

        WineUtils.applyComputerName(context, container(root))

        // ActiveComputerName and the Tcpip hostnames are recreated by wineboot on every boot, so they are left alone.
        val text = systemReg.readText()
        assertTrue(text.contains("\"ComputerName\"=\"LOCALHOST\""))
        assertTrue(text.contains("\"Hostname\"=\"LOCALHOST\""))
        assertTrue(text.contains("\"NV Hostname\"=\"LOCALHOST\""))
        assertFalse(WineComputerNameUtils.defaultName(context) == "LOCALHOST")
    }
}
