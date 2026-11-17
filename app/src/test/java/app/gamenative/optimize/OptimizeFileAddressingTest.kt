package app.gamenative.optimize

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptimizeFileAddressingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var roots: PathRoots

    @Before
    fun setUp() {
        home = tmp.newFolder("home")
        roots = PathRoots(null, null, home)
    }

    private fun lever(path: String, vararg alternatives: String) = Lever.EngineFile(
        path, "ini", "default", false,
        listOf(FileOp("set", "ScalabilityGroups", "sg.ShadowQuality", "1")),
        pathAlternatives = alternatives.toList(),
    )

    private fun write(rel: String): File = File(home, rel).apply {
        parentFile!!.mkdirs()
        writeText("[ScalabilityGroups]\r\nsg.ShadowQuality=3\r\n")
    }

    @Test
    fun aMissingFileIsNothingToWriteNotAFailure() {
        val count = OptimizeApplier.OpCount()
        val written = OptimizeApplier.applyFile(
            0, lever("{localAppDataPath}/*/Saved/Config/*/GameUserSettings.ini"), roots, tmp.newFolder("rung"), true, count,
        )
        assertNull(written)
        assertEquals(1, count.missing)
        assertEquals(1, count.skipped)
        assertEquals(0, count.applied)
    }

    @Test
    fun theFirstAlternativeThatLeadsToTheFileIsWritten() {
        val file = write("Documents/My Games/Borderlands 3/Saved/Config/WindowsNoEditor/GameUserSettings.ini")
        val count = OptimizeApplier.OpCount()
        val written = OptimizeApplier.applyFile(
            0,
            lever(
                "{localAppDataPath}/*/Saved/Config/*/GameUserSettings.ini",
                "{documentsPath}/My Games/*/Saved/Config/*/GameUserSettings.ini",
            ),
            roots, tmp.newFolder("rung"), true, count,
        )
        assertEquals(file.absolutePath, written!!.getString("path"))
        assertEquals(0, count.missing)
        assertTrue(file.readText().contains("sg.ShadowQuality=1"))
    }

    @Test
    fun alternativesAreParsedFromThePayload() {
        val lever = Lever.parse(
            JSONObject(
                """{"lever_type":"engine_file","payload":{"path":"{localAppDataPath}/a.ini","format":"ini",
                |"path_alternatives":["{documentsPath}/a.ini",""],"ops":[{"op":"set","section":"S","key":"k","value":"1"}]}}""".trimMargin(),
            ),
        ) as Lever.EngineFile
        assertEquals(listOf("{localAppDataPath}/a.ini", "{documentsPath}/a.ini"), lever.candidates)
    }

    private fun rung(levers: String) = Rung.parse(JSONObject("""{"rung_id":"cfg.x.balanced.all.s3","version":"v","levers":$levers}"""))!!

    @Test
    fun aRungWithEveryFileMissingIsReportedAsSuch() {
        val file = """{"lever_type":"engine_file","payload":{"path":"{documentsPath}/a.ini","format":"ini","ops":[{"op":"set","section":"S","key":"k","value":"1"}]}}"""
        val reg = """{"lever_type":"registry","payload":{"hive":"HKCU","key":"Software\\A","values":[{"name":"v","type":"dword","data":"1"}]}}"""
        assertTrue(OptimizeLadders.allFilesMissing(rung("[$file,$file]"), 2))
        assertFalse(OptimizeLadders.allFilesMissing(rung("[$file,$file]"), 1))
        assertFalse(OptimizeLadders.allFilesMissing(rung("[$file,$reg]"), 1))
        assertFalse(OptimizeLadders.allFilesMissing(rung("[$reg]"), 0))
    }

    private fun card(kind: String) = OptimizeCard(kind, 1L, "Game", OptimizeProfile.BALANCED, rungId = "cfg.x.balanced.all.s3", fpsGainPct = 20)

    @Test
    fun anAfterCardTakesItsChoicesFromTheNextAnswer() {
        val helped = card(OptimizeCard.HELPED)
        assertEquals(OptimizeCard.EXHAUSTED, OptimizeCards.afterNextAnswer(helped, NextRungResponse.STATUS_EXHAUSTED)!!.kind)
        val nothing = OptimizeCards.afterNextAnswer(helped, NextRungResponse.STATUS_NO_RULE)!!
        assertEquals(OptimizeCard.HELPED, nothing.kind)
        assertTrue(nothing.nothingNext)
        assertNull(OptimizeCards.afterNextAnswer(nothing, NextRungResponse.STATUS_NOT_SETTINGS))
        assertNull(OptimizeCards.afterNextAnswer(helped, NextRungResponse.STATUS_RUNG))
        assertTrue(OptimizeCards.afterNextAnswer(card(OptimizeCard.NO_CHANGE), NextRungResponse.STATUS_NO_RULE)!!.nothingNext)
        assertNull(OptimizeCards.afterNextAnswer(card(OptimizeCard.FIRST), NextRungResponse.STATUS_EXHAUSTED))
        // kept with the card
        assertEquals(nothing, OptimizeCard.fromJson(nothing.toJson()))
    }
}
