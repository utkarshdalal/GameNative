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
    fun testGetGameInstallPath_pathStructure() {
        val path = GOGConstants.getGameInstallPath("Another Game 2026")
        assertEquals(path, "/tmp/internal/GOG/games/common/Another Game 2026")
    }

    @Test
    fun testSanitizationSpecialChars() {
        val path = GOGConstants.getGameInstallPath("G%ame@With^Special*Chars")
        assertEquals(path, "/tmp/internal/GOG/games/common/GameWithSpecialChars")
    }
}
