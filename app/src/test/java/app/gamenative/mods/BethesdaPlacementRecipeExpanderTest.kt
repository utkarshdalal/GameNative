package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BethesdaPlacementRecipeExpanderTest {
    private lateinit var tempDir: File
    private lateinit var extracted: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("bethesda_recipe_expander").toFile()
        extracted = File(tempDir, "extracted").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun expand_convertsBethesdaDataSymlinksToOverwriteCopy() {
        val install = install()
        val recipe = recipe(
            sourceSubpath = "",
            targetRelativePath = "Data",
            mode = ModPlacementMode.SYMLINK,
        )

        val expanded = BethesdaPlacementRecipeExpander.expand(
            gameName = "The Elder Scrolls V: Skyrim Special Edition",
            install = install,
            recipes = listOf(recipe),
        )

        assertEquals(listOf(ModPlacementMode.OVERWRITE_COPY.name), expanded.map { it.mode })
    }

    @Test
    fun expand_addsPluginSidecarsForDirectPluginFileRecipes() {
        File(extracted, "alternate start - live another life.esp").writeText("plugin")
        File(extracted, "alternate start - live another life.bsa").writeText("bsa")
        File(extracted, "alternate start - live another life - textures.bsa").writeText("textures")
        File(extracted, "alternate start - live another life.ini").writeText("ini")
        File(extracted, "unrelated.bsa").writeText("other")
        val install = install()
        val recipe = recipe(
            sourceSubpath = "alternate start - live another life.esp",
            targetRelativePath = "Data",
            mode = ModPlacementMode.OVERWRITE_COPY,
        )

        val expanded = BethesdaPlacementRecipeExpander.expand(
            gameName = "Skyrim Special Edition",
            install = install,
            recipes = listOf(recipe),
        )

        assertEquals(
            listOf(
                "alternate start - live another life.esp",
                "alternate start - live another life - textures.bsa",
                "alternate start - live another life.bsa",
                "alternate start - live another life.ini",
            ),
            expanded.map { it.sourceSubpath },
        )
        assertTrue(expanded.all { it.mode == ModPlacementMode.OVERWRITE_COPY.name })
    }

    @Test
    fun expand_doesNotDuplicateSidecarsAlreadySelectedInMultiSourceRecipe() {
        File(extracted, "Book Covers Skyrim.esp").writeText("plugin")
        File(extracted, "Book Covers Skyrim.bsa").writeText("bsa")
        File(extracted, "Book Covers Skyrim - Textures.bsa").writeText("textures")
        val sources = ModPlacementSources.encode(
            listOf(
                "Book Covers Skyrim - Textures.bsa",
                "Book Covers Skyrim.bsa",
                "Book Covers Skyrim.esp",
            ),
        )
        val install = install()
        val recipe = recipe(
            sourceSubpath = sources,
            targetRelativePath = "Data",
            mode = ModPlacementMode.OVERWRITE_COPY,
        )

        val expanded = BethesdaPlacementRecipeExpander.expand(
            gameName = "Skyrim Special Edition",
            install = install,
            recipes = listOf(recipe),
        )

        assertEquals(listOf(sources), expanded.map { it.sourceSubpath })
    }

    @Test
    fun expand_leavesNonBethesdaGamesUnchanged() {
        val install = install()
        val recipe = recipe(
            sourceSubpath = "",
            targetRelativePath = "Data",
            mode = ModPlacementMode.SYMLINK,
        )

        val expanded = BethesdaPlacementRecipeExpander.expand(
            gameName = "Command & Conquer Generals Zero Hour",
            install = install,
            recipes = listOf(recipe),
        )

        assertEquals(listOf(recipe), expanded)
    }

    private fun install() = ModInstall(
        installId = "install",
        appId = "STEAM_489830",
        nexusGameDomain = "skyrimspecialedition",
        nexusModId = 1,
        nexusFileId = 2,
        modName = "Mod",
        fileName = "mod.7z",
        archivePath = File(tempDir, "mod.7z").absolutePath,
        extractedPath = extracted.absolutePath,
    )

    private fun recipe(
        sourceSubpath: String,
        targetRelativePath: String,
        mode: ModPlacementMode,
    ) = ModPlacementRecipe(
        installId = "install",
        sourceSubpath = sourceSubpath,
        targetRoot = ModTargetRoot.GAME_DIR.name,
        targetRelativePath = targetRelativePath,
        mode = mode.name,
    )
}
