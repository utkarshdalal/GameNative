package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ModConflictAnalyzerTest {
    private lateinit var tempDir: File
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("mod_conflicts").toFile()
        gameDir = File(tempDir, "game").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun analyze_reportsWinningModByPriority() = runBlocking {
        val low = install("low", "Low Priority", "low")
        val high = install("high", "High Priority", "high")
        File(low.extractedPath, "Data/config.ini").apply {
            parentFile?.mkdirs()
            writeText("low")
        }
        File(high.extractedPath, "Data/config.ini").apply {
            parentFile?.mkdirs()
            writeText("high")
        }

        val reports = ModConflictAnalyzer.analyze(
            installs = listOf(low, high),
            recipesByInstallId = mapOf(
                low.installId to listOf(recipe(low.installId)),
                high.installId to listOf(recipe(high.installId)),
            ),
            prioritiesByInstallId = mapOf(low.installId to 10, high.installId to 20),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertEquals(1, reports.size)
        val report = reports.single()
        assertTrue(report.targetPath.endsWith("Data${File.separator}config.ini"))
        assertEquals("high", report.winnerInstallId)
        assertEquals(listOf("high", "low"), report.participants.map { it.installId })
        assertEquals(true, report.participants.first().wins)
    }

    private fun install(id: String, name: String, folder: String): ModInstall {
        val extracted = File(tempDir, folder).apply { mkdirs() }
        return ModInstall(
            installId = id,
            appId = "APP",
            nexusGameDomain = "game",
            nexusModId = id.hashCode().toLong(),
            nexusFileId = id.hashCode().toLong(),
            modName = name,
            fileName = "$id.zip",
            archivePath = File(tempDir, "$id.zip").absolutePath,
            extractedPath = extracted.absolutePath,
        )
    }

    private fun recipe(installId: String): ModPlacementRecipe =
        ModPlacementRecipe(
            installId = installId,
            sourceSubpath = "Data",
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data",
            mode = ModPlacementMode.OVERWRITE_COPY.name,
        )
}
