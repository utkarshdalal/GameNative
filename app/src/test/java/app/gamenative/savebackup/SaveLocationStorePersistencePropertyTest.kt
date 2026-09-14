package app.gamenative.savebackup

import app.gamenative.enums.PathType
import app.gamenative.utils.FakeDataStore
import com.pholser.junit.quickcheck.From
import com.pholser.junit.quickcheck.Property
import com.pholser.junit.quickcheck.generator.Size
import com.pholser.junit.quickcheck.generator.java.lang.Encoded
import com.pholser.junit.quickcheck.generator.java.lang.Encoded.InCharset
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.runner.RunWith

/**
 * Property test for [DataStoreSaveLocationStore] single-valued persistence — game-save-backup
 * Task 3.2 (Property 1).
 *
 * Runs under the existing JUnit 4 setup via junit-quickcheck's [JUnitQuickcheck] runner (no JUnit
 * Platform migration) and does NOT depend on Robolectric or `PrefManager.init`. The store is
 * constructed directly against an in-memory [FakeDataStore], so `put`/`get` (both `suspend`) are
 * driven with [runBlocking] inside each `@Property` method — junit-quickcheck properties are plain
 * functions, so the suspend calls are wrapped rather than declared `suspend`.
 *
 * Every `@Property` runs a minimum of 100 iterations.
 */
@RunWith(JUnitQuickcheck::class)
class SaveLocationStorePersistencePropertyTest {

    /**
     * The supported PathType set is the input space for persisted locations. All members round-trip
     * cleanly through the DTO (serialized by enum name), so we generate only from this set.
     */
    private val supportedPathTypes: List<PathType> = SaveLocation.SUPPORTED_PATH_TYPES.toList()

    private fun pickPathType(selector: Int): PathType {
        val idx = Math.floorMod(selector, supportedPathTypes.size)
        return supportedPathTypes[idx]
    }

    /** Build a SaveLocation from a PathType selector and a raw subpath (normalized by the model). */
    private fun locationFrom(pathTypeSelector: Int, rawSubpath: String): SaveLocation =
        SaveLocation(pickPathType(pathTypeSelector), rawSubpath)

    // Feature: game-save-backup, Property 1: SaveLocation persistence is single-valued per game —
    // for any game key and any non-empty sequence of persisted values, get returns exactly the most
    // recently persisted value and no other.
    @Property(trials = 200)
    fun getReturnsMostRecentlyPersistedValue(
        appId: String,
        pathTypeSelectors:
        @Size(min = 0, max = 12)
        List<Int>,
        @From(Encoded::class) @InCharset("UTF-8") subpathSeed: String,
    ) = runBlocking {
        val store = DataStoreSaveLocationStore(FakeDataStore())

        // The property is stated over a NON-EMPTY sequence of persisted values, so guarantee at
        // least one selector regardless of what the generator produced (the @Size lower bound is
        // not reliably honored for this Kotlin parameter position).
        val selectors = if (pathTypeSelectors.isEmpty()) listOf(0) else pathTypeSelectors

        // Build a non-empty sequence of locations from the selectors. The subpath for the i-th
        // value is derived from the shared seed + index so successive puts carry distinct,
        // arbitrary subpaths (and empty subpaths are naturally exercised when the seed is empty).
        val sequence = selectors.mapIndexed { i, sel ->
            val rawSubpath = if (subpathSeed.isEmpty()) "" else "$subpathSeed/$i"
            locationFrom(sel, rawSubpath)
        }

        // Persist every value in order for this key.
        sequence.forEach { store.put(appId, it) }

        // get returns exactly the most recently persisted value.
        val expected = sequence.last()
        assertEquals(expected, store.get(appId))
    }

    // Feature: game-save-backup, Property 1: SaveLocation persistence is single-valued per game —
    // interleaving puts across multiple distinct keys does not cross-contaminate; each key retains
    // its own most-recent value.
    @Property(trials = 100)
    fun distinctKeysRetainTheirOwnMostRecentValue(
        keySeed: String,
        pathTypeSelectors:
        @Size(min = 0, max = 10)
        List<Int>,
    ) = runBlocking {
        val store = DataStoreSaveLocationStore(FakeDataStore())

        // Derive several distinct keys from the seed (guarding against an empty seed).
        val base = if (keySeed.isEmpty()) "key" else keySeed
        val keys = listOf("STEAM_$base", "GOG_$base", "EPIC_$base")

        // Ensure enough puts to exercise interleaving across all keys regardless of the generator.
        val selectors = if (pathTypeSelectors.size < keys.size) {
            List(keys.size) { it }
        } else {
            pathTypeSelectors
        }

        // Track the last value put for each key. Interleave puts by cycling keys as we walk the
        // selector sequence, so writes to different keys are genuinely intermixed.
        val expected = HashMap<String, SaveLocation>()
        selectors.forEachIndexed { i, sel ->
            val key = keys[i % keys.size]
            val location = locationFrom(sel, "sub/$i")
            store.put(key, location)
            expected[key] = location
        }

        // Every key that received at least one put retains exactly its own most-recent value.
        for (key in keys) {
            if (expected.containsKey(key)) {
                assertEquals("key $key should hold its most-recent value", expected[key], store.get(key))
            } else {
                assertNull("key $key was never written and must be unset", store.get(key))
            }
        }
    }
}
