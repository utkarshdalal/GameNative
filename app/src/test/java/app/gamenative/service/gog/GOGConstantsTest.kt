package app.gamenative.service.gog

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.gamenative.PrefManager
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class GOGConstantsTest {
    @Before
    fun setUp() {
        // Create mock DataStore that returns empty preferences
        val mockDataStore = Mockito.mock(DataStore::class.java) as DataStore<Preferences>
        Mockito.`when`(mockDataStore.data).thenReturn(flowOf(emptyPreferences()))

        // Use reflection to set dataStore without calling init()
        val dataStoreField = PrefManager::class.java.getDeclaredField("dataStore")
        dataStoreField.isAccessible = true
        dataStoreField.set(PrefManager, mockDataStore)

        // Mock context for GOGConstants
        val context = Mockito.mock(Context::class.java)
        val filesDir = File("/tmp/internal")
        filesDir.mkdirs()
        Mockito.`when`(context.filesDir).thenReturn(filesDir)
        Mockito.`when`(context.applicationContext).thenReturn(context)

        PrefManager.init(context)
        GOGConstants.init(context)
    }

    @Test
    fun testGetGameInstallPath_internal() {
        // Can't easily test external without mocking. So we'll just generate a path
        File("/tmp/external").mkdirs()
        val path = GOGConstants.getGameInstallPath("The Witcher 3: Wild Hunt")
        assertTrue(path.contains("The Witcher 3 Wild Hunt"))
        assertFalse(path.contains(":"))
        // Path should be valid
        assertTrue(path.isNotEmpty())
    }

    @Test
    fun testGetGameInstallPath_pathStructure() {
        val path = GOGConstants.getGameInstallPath("Another Game 2026")
        assertTrue(path.contains("Another Game 2026"))
        assertTrue(path.contains("GOG"))
        assertTrue(path.contains("games"))
        assertTrue(path.contains("common"))
    }

    @Test
    fun testSanitization() {
        val path = GOGConstants.getGameInstallPath("Game@With^Special*Chars")
        assertTrue(path.contains("GameWithSpecialChars"))
        assertFalse(path.contains("@"))
        assertFalse(path.contains("^"))
        assertFalse(path.contains("*"))
    }

    @Test
    fun testSanitizationColon() {
        val path = GOGConstants.getGameInstallPath("Game:With:Colons")
        assertTrue(path.contains("GameWithColons"))
        assertFalse(path.contains(":"))
    }

    @Test
    fun testSanitizationHashAndSymbols() {
        val path = GOGConstants.getGameInstallPath("Game#2026!@#$%")
        assertTrue(path.contains("Game2026"))
        assertFalse(path.contains("#"))
        assertFalse(path.contains("!"))
        assertFalse(path.contains("$"))
        assertFalse(path.contains("%"))
    }
}
