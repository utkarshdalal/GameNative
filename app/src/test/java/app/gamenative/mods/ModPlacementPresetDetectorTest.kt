package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModPlacementPresetDetectorTest {
    @Test
    fun detect_skyrimDataPreset_fromNestedDataFolder() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            entries = listOf(
                ModArchiveEntry("CBBE/Data/meshes/body.nif", directory = false, sizeBytes = 1),
                ModArchiveEntry("CBBE/Data/textures/body.dds", directory = false, sizeBytes = 1),
            ),
        )

        val preset = presets.single { it.id == "bethesda-data" }
        assertEquals("CBBE/Data", preset.drafts.single().sourceSubpath)
        assertEquals("Data", preset.drafts.single().targetRelativePath)
        assertEquals(ModPlacementMode.OVERWRITE_COPY.name, preset.drafts.single().mode)
    }

    @Test
    fun detect_bethesdaDataPreset_fromLooseTopLevelContent() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "Fallout 4",
            entries = listOf(
                ModArchiveEntry("meshes/weapon.nif", directory = false, sizeBytes = 1),
                ModArchiveEntry("textures/weapon.dds", directory = false, sizeBytes = 1),
                ModArchiveEntry("Plugin.esp", directory = false, sizeBytes = 1),
            ),
        )

        val sources = ModPlacementSources.decode(presets.single { it.id == "bethesda-data" }.drafts.single().sourceSubpath)
        assertEquals(setOf("meshes", "textures", "Plugin.esp"), sources.toSet())
        assertTrue(presets.single { it.id == "bethesda-data" }.drafts.single().includeSourceDirectory)
    }

    @Test
    fun detect_bethesdaDataPreset_includesLooseBethesdaArchivesAndIgnoresFomodInfoMetadata() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            entries = listOf(
                ModArchiveEntry("AMatterOfTime.bsa", directory = false, sizeBytes = 1),
                ModArchiveEntry("AMatterOfTime.esp", directory = false, sizeBytes = 1),
                ModArchiveEntry("AMatterOfTime_Readme.txt", directory = false, sizeBytes = 1),
                ModArchiveEntry("fomod/info.xml", directory = false, sizeBytes = 1),
            ),
        )

        val sources = ModPlacementSources.decode(presets.single { it.id == "bethesda-data" }.drafts.single().sourceSubpath)
        assertEquals(setOf("AMatterOfTime.bsa", "AMatterOfTime.esp"), sources.toSet())
        assertTrue(!presets.single { it.id == "bethesda-data" }.drafts.single().includeSourceDirectory)
    }

    @Test
    fun detect_bethesdaDataPreset_fromWrappedContent() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            entries = listOf(
                ModArchiveEntry("Main/meshes/armor.nif", directory = false, sizeBytes = 1),
                ModArchiveEntry("Main/textures/armor.dds", directory = false, sizeBytes = 1),
                ModArchiveEntry("Main/CoolArmor.esp", directory = false, sizeBytes = 1),
            ),
        )

        val preset = presets.single { it.id == "bethesda-data" }
        assertEquals("Main", preset.drafts.single().sourceSubpath)
        assertEquals("Data", preset.drafts.single().targetRelativePath)
        assertTrue(!preset.drafts.single().includeSourceDirectory)
    }

    @Test
    fun detect_bethesdaDataPreset_keepsSkseUnderData() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            entries = listOf(
                ModArchiveEntry("SKSE/Plugins/cool.dll", directory = false, sizeBytes = 1),
            ),
        )

        val preset = presets.single { it.id == "bethesda-data" }
        assertEquals("SKSE", preset.drafts.single().sourceSubpath)
        assertEquals("Data", preset.drafts.single().targetRelativePath)
        assertTrue(preset.drafts.single().includeSourceDirectory)
    }

    @Test
    fun detect_bethesdaPreset_requiresContentSignal() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            entries = listOf(
                ModArchiveEntry("00 Required (Slim)/meshes/body.nif", directory = false, sizeBytes = 1),
                ModArchiveEntry("fomod/ModuleConfig.xml", directory = false, sizeBytes = 1),
            ),
        )

        assertTrue(presets.none { it.id == "bethesda-data" })
    }

    @Test
    fun detect_bethesdaPreset_requiresBethesdaGame() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "Unity Game",
            entries = listOf(
                ModArchiveEntry("Data/Managed/foo.dll", directory = false, sizeBytes = 1),
            ),
        )

        assertTrue(presets.none { it.id == "bethesda-data" })
    }

    @Test
    fun detect_bepInExPreset() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "Unity Game",
            entries = listOf(
                ModArchiveEntry("CoolMod/BepInEx/plugins/cool.dll", directory = false, sizeBytes = 1),
            ),
        )

        val preset = presets.single { it.id == "bepinex" }
        assertEquals("CoolMod/BepInEx", preset.drafts.single().sourceSubpath)
        assertEquals("BepInEx", preset.drafts.single().targetRelativePath)
    }

    @Test
    fun detect_unrealPreset_fromLoosePakFiles() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "Unreal Game",
            entries = listOf(
                ModArchiveEntry("mod_P.pak", directory = false, sizeBytes = 1),
                ModArchiveEntry("mod_P.ucas", directory = false, sizeBytes = 1),
            ),
        )

        val preset = presets.single { it.id == "unreal-paks" }
        assertEquals("Content/Paks", preset.drafts.single().targetRelativePath)
        assertTrue(ModPlacementSources.decode(preset.drafts.single().sourceSubpath).contains("mod_P.pak"))
    }

    @Test
    fun detect_redmodPreset_requiresCyberpunkGame() {
        val presets = ModPlacementPresetDetector.detect(
            gameName = "Unity Game",
            entries = listOf(
                ModArchiveEntry("mods/example.archive", directory = false, sizeBytes = 1),
            ),
        )

        assertTrue(presets.none { it.id == "redmod" })
    }
}
