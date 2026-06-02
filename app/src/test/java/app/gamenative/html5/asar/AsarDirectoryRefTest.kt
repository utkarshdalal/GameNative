package app.gamenative.html5.asar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// delegation tests for AsarDirectoryRef adapter over AsarArchive.
// pure-jvm; no robolectric needed (AsarArchive + AsarTestFixtures are stdlib-only).
class AsarDirectoryRefTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun fixture() = AsarTestFixtures.writeFixture(
        tempFolder.newFile("a.asar"),
        linkedMapOf(
            "package.json" to """{"name":"x"}""".toByteArray(),
            "main.js" to "".toByteArray(),
            "resources/sub/leaf.txt" to "leaf".toByteArray(),
        ),
    )

    @Test
    fun exists_trueForFile() {
        AsarArchive.open(fixture()).use { a ->
            assertTrue(AsarDirectoryRef(a).exists("package.json"))
        }
    }

    @Test
    fun exists_trueForDirectory() {
        AsarArchive.open(fixture()).use { a ->
            assertTrue(AsarDirectoryRef(a).exists("resources/sub"))
        }
    }

    @Test
    fun exists_falseForMissing() {
        AsarArchive.open(fixture()).use { a ->
            assertFalse(AsarDirectoryRef(a).exists("nope.js"))
        }
    }

    @Test
    fun listFiles_rootReturnsTopLevel() {
        AsarArchive.open(fixture()).use { a ->
            val entries = AsarDirectoryRef(a).listFiles("").toSet()
            assertTrue("expected package.json in $entries", entries.contains("package.json"))
            assertTrue("expected main.js in $entries", entries.contains("main.js"))
            assertTrue("expected resources dir in $entries", entries.contains("resources"))
        }
    }

    @Test
    fun listFiles_subdirReturnsChildren() {
        AsarArchive.open(fixture()).use { a ->
            val entries = AsarDirectoryRef(a).listFiles("resources").toSet()
            assertEquals(setOf("sub"), entries)
        }
    }
}
