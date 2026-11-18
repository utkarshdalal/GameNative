package app.gamenative.service.epic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpicInstallStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dlc versions round trip`() {
        val installPath = tmp.newFolder("game").absolutePath
        val state = EpicInstallState(
            buildVersion = "1.2.3-Windows",
            language = "english",
            prereqIds = listOf("prereq"),
            dlcVersions = mapOf("DlcOne" to "1.0.0-Windows", "DlcTwo" to "2.1.0-Windows"),
        )

        EpicInstallState.write(installPath, state)

        assertEquals(state, EpicInstallState.read(installPath))
    }

    @Test
    fun `file without dlc versions loads with an empty map`() {
        val installDir = tmp.newFolder("legacy")
        File(installDir, ".gamenative_epic_install.json").writeText(
            """{"buildVersion":"1.0.0-Windows","language":"english","prereqIds":[],"prereqName":"","prereqPath":""}""",
        )

        val state = EpicInstallState.read(installDir.absolutePath)

        assertNotNull(state)
        assertEquals("1.0.0-Windows", state!!.buildVersion)
        assertTrue(state.dlcVersions.isEmpty())
    }
}
