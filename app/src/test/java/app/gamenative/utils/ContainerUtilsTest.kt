package app.gamenative.utils

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerUtilsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        mockkObject(CustomGameScanner)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun resolveGameName_customGame_usesMatchedNameOverFolderName() {
        val folder = tempFolder.newFolder("hollow_knight_gog")
        every { CustomGameScanner.getFolderPathFromAppId("CUSTOM_GAME_42") } returns folder.absolutePath

        assertEquals("hollow_knight_gog", ContainerUtils.resolveGameName("CUSTOM_GAME_42"))

        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight")

        assertEquals("Hollow Knight", ContainerUtils.resolveGameName("CUSTOM_GAME_42"))
    }
}
