package app.gamenative.steam

import app.gamenative.steam.SteamCollectionParser.RawEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCollectionParserTest {
    private fun entry(id: String, json: String, deleted: Boolean = false) =
        RawEntry(key = "user-collections.$id", value = json, isDeleted = deleted)

    @Test
    fun parsesStaticCollectionWithAddedApps() {
        val result = SteamCollectionParser.parse(
            listOf(entry("abc", """{"id":"abc","name":"Favorites","added":[440,570]}"""))
        )
        assertEquals(1, result.collections.size)
        val c = result.collections.first()
        assertEquals("abc", c.id)
        assertEquals("Favorites", c.name)
        assertEquals(setOf(440, 570), c.appIds)
        assertEquals(0, result.skippedDynamicCount)
    }

    @Test
    fun skipsDeletedEntries() {
        val result = SteamCollectionParser.parse(
            listOf(entry("abc", """{"id":"abc","name":"X","added":[1]}""", deleted = true))
        )
        assertEquals(0, result.collections.size)
    }

    @Test
    fun skipsDynamicCollectionsAndCountsThem() {
        val result = SteamCollectionParser.parse(
            listOf(entry("dyn", """{"id":"dyn","name":"Smart","filterSpec":{"x":1}}"""))
        )
        assertEquals(0, result.collections.size)
        assertEquals(1, result.skippedDynamicCount)
    }

    @Test
    fun ignoresNonCollectionKeys() {
        val result = SteamCollectionParser.parse(
            listOf(RawEntry(key = "other.key", value = "{}", isDeleted = false))
        )
        assertEquals(0, result.collections.size)
        assertEquals(0, result.skippedDynamicCount)
    }

    @Test
    fun treatsStaticWithEmptyAddedAsStatic() {
        val result = SteamCollectionParser.parse(
            listOf(entry("e", """{"id":"e","name":"Empty","added":[]}"""))
        )
        assertEquals(1, result.collections.size)
        assertEquals(emptySet<Int>(), result.collections.first().appIds)
    }

    @Test
    fun toleratesMalformedJson() {
        val result = SteamCollectionParser.parse(
            listOf(entry("bad", "not json"))
        )
        assertEquals(0, result.collections.size)
    }

    @Test
    fun fallsBackToKeyAndIdWhenIdNameAreJsonNull() {
        // optString returns the literal "null" for a JSON-null value; it must be treated as absent.
        val result = SteamCollectionParser.parse(
            listOf(entry("xyz", """{"id":null,"name":null,"added":[1]}"""))
        )
        assertEquals(1, result.collections.size)
        val c = result.collections.first()
        assertEquals("xyz", c.id) // from the entry key, not the literal "null"
        assertEquals("xyz", c.name) // falls back to id
    }

    @Test
    fun nonArrayAddedIsDroppedAndNotCountedDynamic() {
        // "added" present but not an array (optJSONArray -> null) and no filterSpec: drop, don't count.
        val result = SteamCollectionParser.parse(
            listOf(entry("x", """{"id":"x","name":"X","added":"440"}"""))
        )
        assertEquals(0, result.collections.size)
        assertEquals(0, result.skippedDynamicCount)
    }

    @Test
    fun nonIntegerAddedElementDropsWholeCollection() {
        // A non-coercible element makes getInt throw; the whole entry is skipped (all-or-nothing).
        val result = SteamCollectionParser.parse(
            listOf(entry("x", """{"id":"x","name":"X","added":[440,null]}"""))
        )
        assertEquals(0, result.collections.size)
    }
}
