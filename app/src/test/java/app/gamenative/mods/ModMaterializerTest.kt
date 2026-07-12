package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class ModMaterializerTest {
    private lateinit var tempDir: File
    private lateinit var extracted: File
    private lateinit var gameDir: File
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("nexus_materializer_test").toFile()
        extracted = File(tempDir, "extracted").apply { mkdirs() }
        gameDir = File(tempDir, "game").apply { mkdirs() }
        backupDir = File(tempDir, "backups").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun overwriteCopy_backsUpExistingFileAndRestoreUsesManifest() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertEquals("modded", target.readText())
        assertEquals(1, result.backedUp)
        assertTrue(File(result.manifests.single().backupPath).isFile)

        val skipped = ModMaterializer.restoreBackups(result.manifests)

        assertTrue(skipped.isEmpty())
        assertEquals("original", target.readText())
    }

    @Test
    fun overwriteCopy_createsMissingBackupRootBeforeCheckingSpace() = runBlocking {
        backupDir.deleteRecursively()
        File(extracted, "config.ini").writeText("modded")
        val target = File(gameDir, "config.ini").apply { writeText("original") }

        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(recipe(mode = ModPlacementMode.OVERWRITE_COPY)),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertTrue(result.errors.isEmpty())
        assertEquals("modded", target.readText())
        assertTrue(File(result.manifests.single().backupPath).isFile)
    }

    @Test
    fun copyMode_doesNotOverwriteExistingFile() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(recipe(mode = ModPlacementMode.COPY)),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = false,
        )

        assertEquals("original", target.readText())
        assertEquals(0, result.created)
        assertEquals(1, result.skipped)
    }

    @Test
    fun scanConflicts_overwriteCopyIgnoresSameExistingFileAndNewFilesInExistingDirectory() = runBlocking {
        File(extracted, "Data/same.ini").apply {
            parentFile?.mkdirs()
            writeText("same")
        }
        File(extracted, "Data/new.ini").writeText("new")
        File(gameDir, "Data/same.ini").apply {
            parentFile?.mkdirs()
            writeText("same")
        }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(sourceSubpath = "Data", targetRelativePath = "Data")

        val conflicts = ModMaterializer.scanConflicts(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun apply_overwriteCopyWithoutAllowOverwriteMergesNewFilesIntoExistingDirectory() = runBlocking {
        File(extracted, "Data/new.ini").apply {
            parentFile?.mkdirs()
            writeText("new")
        }
        File(gameDir, "Data").mkdirs()
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(sourceSubpath = "Data", targetRelativePath = "Data")

        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = false,
        )

        assertEquals(1, result.created)
        assertTrue(result.errors.isEmpty())
        assertEquals("new", File(gameDir, "Data/new.ini").readText())
    }

    @Test
    fun apply_overwriteCopyAllowsLaterRecipeToOverlayEarlierRecipeWithoutExternalOverwrite() = runBlocking {
        File(extracted, "00 Required Slim/Data/body.nif").apply {
            parentFile?.mkdirs()
            writeText("slim")
        }
        File(extracted, "02 Vanilla/Data/body.nif").apply {
            parentFile?.mkdirs()
            writeText("vanilla")
        }
        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(
                recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(
                    sourceSubpath = "00 Required Slim/Data",
                    targetRelativePath = "Data",
                ),
                recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(
                    sourceSubpath = "02 Vanilla/Data",
                    targetRelativePath = "Data",
                ),
            ),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = false,
        )

        assertTrue(result.errors.isEmpty())
        assertEquals("vanilla", File(gameDir, "Data/body.nif").readText())
        assertEquals(0, result.backedUp)
    }

    @Test
    fun apply_overwriteCopyWithoutAllowOverwriteRejectsDifferentExistingFile() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        File(gameDir, "config.ini").writeText("original")
        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(recipe(mode = ModPlacementMode.OVERWRITE_COPY)),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = false,
        )

        assertTrue(result.errors.keys.any { it.endsWith("config.ini") })
        assertEquals("original", File(gameDir, "config.ini").readText())
    }

    @Test
    fun repairMissingTargets_copiesOnlyAbsentFilesAndLeavesConflicts() = runBlocking {
        File(extracted, "Data/Plugin.esp").apply {
            parentFile?.mkdirs()
            writeText("updated plugin")
        }
        File(extracted, "Data/Plugin.bsa").writeText("archive")
        File(gameDir, "Data/Plugin.esp").apply {
            parentFile?.mkdirs()
            writeText("existing plugin")
        }
        val result = ModMaterializer.repairMissingTargets(
            install = install().copy(status = ModInstallStatus.APPLIED.name),
            recipes = listOf(
                recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(
                    sourceSubpath = "Data",
                    targetRelativePath = "Data",
                ),
            ),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.created)
        assertEquals("existing plugin", File(gameDir, "Data/Plugin.esp").readText())
        assertEquals("archive", File(gameDir, "Data/Plugin.bsa").readText())
    }

    @Test
    fun copyMode_supportsMultipleSourceFoldersInOneRecipe() = runBlocking {
        File(extracted, "FolderA/a.txt").apply {
            parentFile?.mkdirs()
            writeText("a")
        }
        File(extracted, "FolderB/b.txt").apply {
            parentFile?.mkdirs()
            writeText("b")
        }
        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(
                recipe(mode = ModPlacementMode.COPY).copy(
                    sourceSubpath = ModPlacementSources.encode(listOf("FolderA", "FolderB")),
                    targetRelativePath = "Mods",
                    includeSourceDirectory = true,
                ),
            ),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = false,
        )

        assertEquals(2, result.created)
        assertEquals("a", File(gameDir, "Mods/FolderA/a.txt").readText())
        assertEquals("b", File(gameDir, "Mods/FolderB/b.txt").readText())
    }

    @Test
    fun apply_resolvesSourcePathsCaseInsensitivelyUnderWrapperFolders() = runBlocking {
        File(extracted, "Wrapper/Data/Textures/Mountain.dds").apply {
            parentFile?.mkdirs()
            writeText("texture")
        }
        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(
                recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(
                    sourceSubpath = "data/textures/mountain.dds",
                    targetRelativePath = "Data/textures",
                ),
            ),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertTrue(result.errors.isEmpty())
        assertEquals("texture", File(gameDir, "Data/textures/Mountain.dds").readText())
    }

    @Test
    fun removeAppliedFiles_deletesUnchangedCopiedFiles() = runBlocking {
        val source = File(extracted, "mods/plugin.dll").apply {
            parentFile?.mkdirs()
            writeText("mod")
        }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.COPY)

        ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = false,
        )
        assertTrue(File(gameDir, "mods/plugin.dll").isFile)

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertTrue(skipped.isEmpty())
        assertFalse(File(gameDir, source.relativeTo(extracted).path).exists())
    }

    @Test
    fun removeAppliedFiles_afterOverwriteRestore_removesNewFilesAndKeepsRestoredFiles() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        File(extracted, "new.txt").writeText("new")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        ModMaterializer.restoreBackups(result.manifests)

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            restoredOverwriteTargets = result.manifests.map { it.targetPath }.toSet(),
        )

        assertTrue(skipped.isEmpty())
        assertEquals("original", target.readText())
        assertFalse(File(gameDir, "new.txt").exists())
    }

    @Test
    fun overwriteCopy_sameExistingFileGetsManifestAndIsNotDeletedOnRemove() = runBlocking {
        File(extracted, "config.ini").writeText("same")
        val target = File(gameDir, "config.ini").apply { writeText("same") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        ModMaterializer.restoreBackups(result.manifests)

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            restoredOverwriteTargets = result.manifests.map { it.targetPath }.toSet(),
        )

        assertTrue(skipped.isEmpty())
        assertEquals("same", target.readText())
        assertEquals(1, result.manifests.size)
        assertEquals("", result.manifests.single().backupPath)
        assertEquals(0, result.backedUp)
        assertTrue(backupDir.walkTopDown().none { it.isFile })
    }

    @Test
    fun overwriteCopy_reapplyOfOwnNewFileDoesNotProtectItFromRemove() = runBlocking {
        File(extracted, "new.txt").writeText("new")
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val first = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        assertTrue(first.manifests.isEmpty())
        assertEquals("new", File(gameDir, "new.txt").readText())

        val second = ModMaterializer.apply(
            install = install.copy(status = ModInstallStatus.APPLIED.name),
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        assertTrue(second.manifests.isEmpty())

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertTrue(skipped.isEmpty())
        assertFalse(File(gameDir, "new.txt").exists())
    }

    @Test
    fun overwriteCopy_partialApplyFailureCanBeRolledBack() = runBlocking {
        File(extracted, "new.txt").writeText("new")
        val install = install()
        val recipes = listOf(
            recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(sourceSubpath = "new.txt"),
            recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(sourceSubpath = "missing.txt"),
        )

        val result = ModMaterializer.apply(
            install = install,
            recipes = recipes,
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        assertTrue(result.errors.isNotEmpty())
        assertEquals("new", File(gameDir, "new.txt").readText())

        val restoreSkipped = ModMaterializer.restoreBackups(result.manifests)
        val restoredTargets = result.manifests.map { it.targetPath }.filterNot { it in restoreSkipped }.toSet()
        ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = recipes,
            gameRootDir = gameDir,
            winePrefix = "",
            restoredOverwriteTargets = restoredTargets,
        )

        assertFalse(File(gameDir, "new.txt").exists())
    }

    @Test
    fun filterUnapprovedConflicts_ignoresPreviouslyApprovedInstalledAndOriginalFiles() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        val conflict = ModPlacementConflict(
            sourcePath = File(extracted, "config.ini").absolutePath,
            targetPath = target.absolutePath,
            directory = false,
        )

        assertTrue(ModMaterializer.filterUnapprovedConflicts(listOf(conflict), result.manifests).isEmpty())

        ModMaterializer.restoreBackups(result.manifests)

        assertEquals("original", target.readText())
        assertTrue(ModMaterializer.filterUnapprovedConflicts(listOf(conflict), result.manifests).isEmpty())
    }

    @Test
    fun removeAppliedFiles_overwriteCopyDeletesEmptyModDirectories() = runBlocking {
        File(extracted, "Data/ModFolder/file.txt").apply {
            parentFile?.mkdirs()
            writeText("new")
        }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertTrue(skipped.isEmpty())
        assertFalse(File(gameDir, "Data/ModFolder/file.txt").exists())
        assertFalse(File(gameDir, "Data/ModFolder").exists())
    }

    @Test
    fun removeAppliedFiles_overwriteCopyReportsChangedNewFiles() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        File(extracted, "new.txt").writeText("new")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        File(gameDir, "new.txt").writeText("user changed")
        ModMaterializer.restoreBackups(result.manifests)

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            restoredOverwriteTargets = result.manifests.map { it.targetPath }.toSet(),
        )

        assertEquals("original", target.readText())
        assertTrue(skipped.any { it.endsWith("new.txt") })
        assertEquals("user changed", File(gameDir, "new.txt").readText())
    }

    @Test
    fun overwriteCopy_reapplyPreservesOriginalBackup() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)

        val first = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        val backup = File(first.manifests.single().backupPath)
        assertEquals("original", backup.readText())

        val second = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertEquals(0, second.backedUp)
        assertEquals("original", backup.readText())
        assertEquals("modded", target.readText())

        val skipped = ModMaterializer.restoreBackups(first.manifests + second.manifests)

        assertTrue(skipped.isEmpty())
        assertEquals("original", target.readText())
    }

    @Test
    fun overwriteCopy_directoryDoesNotCreateOwnershipSentinel() = runBlocking {
        File(extracted, "Data/plugin.esp").apply {
            parentFile?.mkdirs()
            writeText("plugin")
        }

        ModMaterializer.apply(
            install = install(),
            recipes = listOf(recipe(mode = ModPlacementMode.OVERWRITE_COPY)),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertTrue(File(gameDir, "Data/plugin.esp").isFile)
        assertFalse(File(gameDir, "Data/.gamenative_mod_install").exists())
    }

    @Test
    fun removeAppliedFiles_overwriteCopyRemovesLegacyOwnershipSentinel() = runBlocking {
        File(extracted, "Data/plugin.esp").apply {
            parentFile?.mkdirs()
            writeText("plugin")
        }
        File(gameDir, "Data/plugin.esp").apply {
            parentFile?.mkdirs()
            writeText("plugin")
        }
        File(gameDir, "Data/.gamenative_mod_install").writeText("install")

        val skipped = ModMaterializer.removeAppliedFiles(
            install = install(),
            recipes = listOf(recipe(mode = ModPlacementMode.OVERWRITE_COPY)),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertTrue(skipped.isEmpty())
        assertFalse(File(gameDir, "Data/plugin.esp").exists())
        assertFalse(File(gameDir, "Data/.gamenative_mod_install").exists())
    }

    @Test
    fun overwriteCopy_replacesTargetSymlinkWithoutChangingLinkDestination() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        val linkedFile = File(tempDir, "linked-original.ini").apply { writeText("linked original") }
        val target = File(gameDir, "config.ini")
        val created = runCatching {
            Files.createSymbolicLink(target.toPath(), linkedFile.toPath())
        }.isSuccess
        assumeTrue("Symlink creation is not available in this test environment", created)

        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(recipe(mode = ModPlacementMode.OVERWRITE_COPY)),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertTrue(result.errors.isEmpty())
        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertEquals("modded", target.readText())
        assertEquals("linked original", linkedFile.readText())
    }

    @Test
    fun overwriteCopy_replacesNestedSymlinkDirectoryWithRealDirectory() = runBlocking {
        File(extracted, "Data/BashTags/Mod.txt").apply {
            parentFile?.mkdirs()
            writeText("modded")
        }
        val targetData = File(gameDir, "Data").apply { mkdirs() }
        val linkedDir = File(tempDir, "linked-bash-tags").apply {
            mkdirs()
            File(this, "Old.txt").writeText("linked original")
        }
        val targetDir = File(targetData, "BashTags")
        val created = runCatching {
            Files.createSymbolicLink(targetDir.toPath(), linkedDir.toPath())
        }.isSuccess
        assumeTrue("Directory symlink creation is not available in this test environment", created)

        val result = ModMaterializer.apply(
            install = install(),
            recipes = listOf(
                recipe(mode = ModPlacementMode.OVERWRITE_COPY).copy(
                    sourceSubpath = "Data",
                    targetRelativePath = "Data",
                ),
            ),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )

        assertTrue(result.errors.isEmpty())
        assertFalse(Files.isSymbolicLink(targetDir.toPath()))
        assertEquals("modded", File(targetDir, "Mod.txt").readText())
        assertEquals("linked original", File(linkedDir, "Old.txt").readText())
    }

    @Test
    fun restoreBackups_replacesMatchingSymlinkWithoutChangingLinkDestination() = runBlocking {
        File(extracted, "config.ini").writeText("modded")
        val target = File(gameDir, "config.ini").apply { writeText("original") }
        val install = install()
        val recipe = recipe(mode = ModPlacementMode.OVERWRITE_COPY)
        val result = ModMaterializer.apply(
            install = install,
            recipes = listOf(recipe),
            gameRootDir = gameDir,
            winePrefix = "",
            backupRoot = backupDir,
            allowOverwrite = true,
        )
        target.delete()
        val linkedFile = File(tempDir, "linked-modded.ini").apply { writeText("modded") }
        val created = runCatching {
            Files.createSymbolicLink(target.toPath(), linkedFile.toPath())
        }.isSuccess
        assumeTrue("Symlink creation is not available in this test environment", created)

        val skipped = ModMaterializer.restoreBackups(result.manifests)

        assertTrue(skipped.isEmpty())
        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertEquals("original", target.readText())
        assertEquals("modded", linkedFile.readText())
    }

    private fun install() = ModInstall(
        installId = "install",
        appId = "STEAM_1",
        nexusGameDomain = "game",
        nexusModId = 1,
        nexusFileId = 2,
        modName = "Mod",
        fileName = "mod.zip",
        archivePath = File(tempDir, "mod.zip").absolutePath,
        extractedPath = extracted.absolutePath,
    )

    private fun recipe(mode: ModPlacementMode) = ModPlacementRecipe(
        installId = "install",
        sourceSubpath = "",
        targetRoot = ModTargetRoot.GAME_DIR.name,
        targetRelativePath = "",
        mode = mode.name,
    )
}
