package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModArchiveInstallAssessorTest {
    @Test
    fun assess_highConfidence_forNormalBethesdaDataMod() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            modName = "Texture replacer",
            fileName = "textures.7z",
            entries = listOf(
                ModArchiveEntry("textures/armor/foo.dds", directory = false, sizeBytes = 1),
                ModArchiveEntry("meshes/armor/foo.nif", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.HIGH, assessment.confidence)
        assertTrue(assessment.allowsAutomaticPlacement)
    }

    @Test
    fun assess_reviewPlacement_forFomodInstaller() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            modName = "Installer mod",
            fileName = "installer.7z",
            entries = listOf(
                ModArchiveEntry("fomod/ModuleConfig.xml", directory = false, sizeBytes = 1),
                ModArchiveEntry("00 Main/meshes/foo.nif", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.REVIEW_PLACEMENT, assessment.confidence)
        assertFalse(assessment.allowsAutomaticPlacement)
    }

    @Test
    fun assess_reviewPlacement_forMultipleOptionFolders() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            modName = "Optional texture pack",
            fileName = "options.zip",
            entries = listOf(
                ModArchiveEntry("00 Main/textures/foo.dds", directory = false, sizeBytes = 1),
                ModArchiveEntry("01 Optional/textures/foo.dds", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.REVIEW_PLACEMENT, assessment.confidence)
        assertFalse(assessment.allowsAutomaticPlacement)
    }

    @Test
    fun assess_manualRootReview_forScriptExtenderRootFiles() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            modName = "Skyrim Script Extender",
            fileName = "skse64.7z",
            entries = listOf(
                ModArchiveEntry("skse64_loader.exe", directory = false, sizeBytes = 1),
                ModArchiveEntry("skse64_steam_loader.dll", directory = false, sizeBytes = 1),
                ModArchiveEntry("Data/Scripts/foo.pex", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW, assessment.confidence)
        assertFalse(assessment.allowsAutomaticPlacement)
    }

    @Test
    fun assess_manualRootReview_forKnownEngineFixesPartTwo() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            gameDomain = "skyrimspecialedition",
            modId = 17230,
            fileId = 2,
            modName = "SSE Engine Fixes",
            fileName = "Part 2 Engine Fixes.7z",
            entries = listOf(
                ModArchiveEntry("Data/SKSE/Plugins/EngineFixes.dll", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW, assessment.confidence)
        assertFalse(assessment.allowsAutomaticPlacement)
    }

    @Test
    fun assess_manualRootReview_forUnknownRootDll() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            modName = "Runtime hook",
            fileName = "hook.zip",
            entries = listOf(
                ModArchiveEntry("hook.dll", directory = false, sizeBytes = 1),
                ModArchiveEntry("Data/Scripts/foo.pex", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW, assessment.confidence)
    }

    @Test
    fun assess_postInstallReview_forBodySlideMod() {
        val assessment = ModArchiveInstallAssessor.assess(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            modName = "Armor BodySlide files",
            fileName = "bodyslide.7z",
            entries = listOf(
                ModArchiveEntry("CalienteTools/BodySlide/SliderSets/foo.osp", directory = false, sizeBytes = 1),
                ModArchiveEntry("meshes/armor/foo.nif", directory = false, sizeBytes = 1),
            ),
        )

        assertEquals(ModArchiveInstallConfidence.POST_INSTALL_REVIEW, assessment.confidence)
        assertTrue(assessment.allowsAutomaticPlacement)
    }
}
