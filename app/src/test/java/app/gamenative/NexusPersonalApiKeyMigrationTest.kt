package app.gamenative

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusPersonalApiKeyMigrationTest {
    @Test
    fun removalDeletesRetiredKeyAndPreservesOtherPreferences() {
        val unrelatedKey = stringPreferencesKey("unrelated_preference")
        val preferences = mutablePreferencesOf(
            RETIRED_NEXUS_PERSONAL_API_KEY to byteArrayOf(1, 2, 3),
            unrelatedKey to "keep",
        )

        preferences.removeRetiredNexusPersonalApiKey()

        assertNull(preferences[RETIRED_NEXUS_PERSONAL_API_KEY])
        assertEquals("keep", preferences[unrelatedKey])
    }

    @Test
    fun presenceCheckFindsAndRemovesEmptyRetiredValue() {
        val preferences = mutablePreferencesOf(
            RETIRED_NEXUS_PERSONAL_API_KEY to ByteArray(0),
        )

        assertTrue(preferences.hasRetiredNexusPersonalApiKey())
        preferences.removeRetiredNexusPersonalApiKey()
        assertFalse(preferences.hasRetiredNexusPersonalApiKey())
    }
}
