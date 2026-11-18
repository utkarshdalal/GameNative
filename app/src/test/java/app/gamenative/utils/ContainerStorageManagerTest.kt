package app.gamenative.utils

import app.gamenative.data.GameSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerStorageManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun normalizeContainerId_stripsDuplicateSuffix() {
        assertEquals("STEAM_123", ContainerStorageManager.normalizeContainerId("STEAM_123(1)"))
        assertEquals("CUSTOM_GAME_42", ContainerStorageManager.normalizeContainerId("CUSTOM_GAME_42(99)"))
        assertEquals("GOG_7", ContainerStorageManager.normalizeContainerId("GOG_7"))
    }

    @Test
    fun detectGameSource_onlyRecognizesKnownPrefixes() {
        assertEquals(GameSource.STEAM, ContainerStorageManager.detectGameSource("STEAM_123"))
        assertEquals(GameSource.CUSTOM_GAME, ContainerStorageManager.detectGameSource("CUSTOM_GAME_42"))
        assertEquals(GameSource.GOG, ContainerStorageManager.detectGameSource("GOG_7"))
        assertEquals(GameSource.EPIC, ContainerStorageManager.detectGameSource("EPIC_8"))
        assertEquals(GameSource.AMAZON, ContainerStorageManager.detectGameSource("AMAZON_9"))
        assertNull(ContainerStorageManager.detectGameSource("12345"))
    }

    @Test
    fun extractGameId_returnsTrailingNumericId() {
        assertEquals(123, ContainerStorageManager.extractGameId("STEAM_123"))
        assertEquals(42, ContainerStorageManager.extractGameId("CUSTOM_GAME_42"))
        assertNull(ContainerStorageManager.extractGameId("CUSTOM_GAME_42_test"))
        assertNull(ContainerStorageManager.extractGameId("BROKEN"))
    }

    @Test
    fun getContainerDirectorySize_skipsHardlinkedFiles() {
        val container = tempFolder.newFolder("container")
        val shared = tempFolder.newFolder("shared")
        File(container, "private.bin").writeBytes(ByteArray(1024))
        val sharedFile = File(shared, "shared.bin").apply { writeBytes(ByteArray(4096)) }
        Files.createLink(File(container, "linked.bin").toPath(), sharedFile.toPath())

        assertEquals(1024L, ContainerStorageManager.getContainerDirectorySize(container.toPath()))
    }

    @Test
    fun getContainerDirectorySize_countsInTreeHardlinksOnce() {
        val container = tempFolder.newFolder("container")
        File(container, "private.bin").writeBytes(ByteArray(1024))
        val original = File(container, "shared.bin").apply { writeBytes(ByteArray(4096)) }
        Files.createLink(File(container, "linked.bin").toPath(), original.toPath())

        assertEquals(5120L, ContainerStorageManager.getContainerDirectorySize(container.toPath()))
    }

    @Test
    fun getContainerDirectorySize_sumsFilesWithoutHardlinks() {
        val container = tempFolder.newFolder("container")
        File(container, "a.bin").writeBytes(ByteArray(1024))
        File(container, "sub").mkdirs()
        File(container, "sub/b.bin").writeBytes(ByteArray(2048))

        assertEquals(3072L, ContainerStorageManager.getContainerDirectorySize(container.toPath()))
    }

    @Test
    fun getContainerDirectorySize_skipsSymlinks() {
        val container = tempFolder.newFolder("container")
        val game = tempFolder.newFolder("game")
        File(container, "user.reg").writeBytes(ByteArray(1024))
        File(game, "game.exe").writeBytes(ByteArray(8192))
        Files.createSymbolicLink(File(container, "a_drive").toPath(), game.toPath())

        assertEquals(1024L, ContainerStorageManager.getContainerDirectorySize(container.toPath()))
    }

    @Test
    fun attachGameSizeToFirstRowPerInstallPath_keepsSizeOnOneRowPerPath() {
        val installDir = tempFolder.newFolder("Cyberpunk")
        val otherDir = tempFolder.newFolder("Witcher")
        val entries = listOf(
            entry("GOG_123(1)", installDir.absolutePath, gameSize = 50_000L, containerSize = 100L),
            entry("GOG_123", installDir.absolutePath, gameSize = 50_000L, containerSize = 200L),
            entry("GOG_456", installDir.absolutePath, gameSize = 50_000L, containerSize = 300L),
            entry("GOG_789", otherDir.absolutePath, gameSize = 7_000L, containerSize = 400L),
            entry("STEAM_1", installPath = null, gameSize = 9_000L, containerSize = 500L),
        )

        val result = ContainerStorageManager.attachGameSizeToFirstRowPerInstallPath(entries)

        assertEquals(listOf(null, 50_000L, null, 7_000L, 9_000L), result.map { it.gameInstallSizeBytes })
        assertEquals(entries.map { it.containerSizeBytes }, result.map { it.containerSizeBytes })
        assertEquals(50_000L + 7_000L + 9_000L, result.mapNotNull { it.gameInstallSizeBytes }.sum())
    }

    @Test
    fun attachGameSizeToFirstRowPerInstallPath_fallsBackToFirstRowWhenAllAreDuplicates() {
        val installDir = tempFolder.newFolder("Cyberpunk")
        val entries = listOf(
            entry("GOG_123(2)", installDir.absolutePath, gameSize = 50_000L, containerSize = 100L),
            entry("GOG_123(1)", installDir.absolutePath, gameSize = 50_000L, containerSize = 200L),
        )

        val result = ContainerStorageManager.attachGameSizeToFirstRowPerInstallPath(entries)

        assertEquals(listOf(50_000L, null), result.map { it.gameInstallSizeBytes })
    }

    private fun entry(containerId: String, installPath: String?, gameSize: Long, containerSize: Long) =
        ContainerStorageManager.Entry(
            containerId = containerId,
            displayName = containerId,
            gameSource = GameSource.GOG,
            containerSizeBytes = containerSize,
            gameInstallSizeBytes = gameSize,
            status = ContainerStorageManager.Status.READY,
            installPath = installPath,
        )
}
