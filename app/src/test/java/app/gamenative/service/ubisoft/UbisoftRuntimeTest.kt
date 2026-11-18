package app.gamenative.service.ubisoft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UbisoftRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractsEveryRuntimeFileIntoABuildCache() {
        val files = temporary.newFolder()
        assertNull(UbisoftRuntime.cachedRuntime(files))
        val runtime = UbisoftRuntime.extract(ByteArraySource(UbisoftNsisFixture.runtimeInstaller("4242")), files)
        assertEquals("runtime-4242", runtime.name)
        assertEquals(runtime, UbisoftRuntime.cachedRuntime(files))
        for ((name, machine) in UbisoftRuntime.FILES) {
            assertEquals(machine, PeImage.parse(File(runtime, name).readBytes()).machine)
        }
        // the root-level copy wins over the one in a sub folder
        assertEquals("uplay_r1.dll", PeImage.parse(File(runtime, "uplay_r1.dll").readBytes()).exportName)
        assertFalse(File(files, "ubisoft/runtime-4242.partial").exists())
    }

    @Test fun aSecondExtractionReusesTheCache() {
        val files = temporary.newFolder()
        UbisoftRuntime.extract(ByteArraySource(UbisoftNsisFixture.runtimeInstaller("7")), files)
        val source = ByteArraySource(UbisoftNsisFixture.runtimeInstaller("7"))
        UbisoftRuntime.extract(source, files)
        assertTrue(source.reads < UbisoftRuntime.FILES.size)
    }

    @Test fun aFileForTheWrongMachineFailsAndPublishesNothing() {
        val files = temporary.newFolder()
        val bad = mapOf("uplay_r1.dll" to UbisoftNsisFixture.pe(PeImage.MACHINE_X64, "uplay_r1.dll"))
        assertThrows(IllegalStateException::class.java) {
            UbisoftRuntime.extract(ByteArraySource(UbisoftNsisFixture.runtimeInstaller(override = bad)), files)
        }
        assertNull(UbisoftRuntime.cachedRuntime(files))
    }

    @Test fun aMissingFileFails() {
        val installer = UbisoftNsisFixture.Builder().outPath()
            .file("uplay_r1.dll", UbisoftNsisFixture.pe(PeImage.MACHINE_X86, "uplay_r1.dll")).build()
        assertThrows(IllegalStateException::class.java) {
            UbisoftRuntime.extract(ByteArraySource(installer), temporary.newFolder())
        }
    }

    @Test fun verifyChecksExportNamesAndDllFlag() {
        UbisoftRuntime.verify("uplay_r2.dll", UbisoftNsisFixture.pe(PeImage.MACHINE_X86, "UPLAY_R2.dll"))
        UbisoftRuntime.verify("UbisoftGameLauncher.exe", UbisoftNsisFixture.pe(PeImage.MACHINE_X86, null))
        assertThrows(IllegalStateException::class.java) {
            UbisoftRuntime.verify("uplay_r2.dll", UbisoftNsisFixture.pe(PeImage.MACHINE_X86, "uplay_r1.dll"))
        }
        assertThrows(IllegalStateException::class.java) {
            UbisoftRuntime.verify("UbisoftGameLauncher64.exe", UbisoftNsisFixture.pe(PeImage.MACHINE_X86, null))
        }
        assertThrows(IllegalStateException::class.java) {
            UbisoftRuntime.verify("uplay_r1.dll", "not a pe".toByteArray())
        }
    }

    @Test fun installsIntoThePrefixLauncherFolder() {
        val files = temporary.newFolder()
        val runtime = UbisoftRuntime.extract(ByteArraySource(UbisoftNsisFixture.runtimeInstaller()), files)
        val driveC = temporary.newFolder()
        val target = UbisoftRuntime.installInto(runtime, driveC)
        assertEquals(File(driveC, "Program Files (x86)/Ubisoft/Ubisoft Game Launcher"), target)
        assertTrue(UbisoftRuntime.FILES.keys.all { File(target, it).isFile })
    }
}
