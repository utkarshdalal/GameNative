package app.gamenative.html5.shim

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// stat files are 4 untagged bytes. without the schema type an earlier online seed cached, a float
// stat decodes as its own bit pattern -- and gets labelled "int", so the next setStat writes int
// bits over a float stat and that syncs to Steam.
class Html5StatTypeCacheTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun statsDirWith(vararg stats: Pair<String, ByteArray>): File {
        val dir = tempFolder.newFolder("stats")
        stats.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        return dir
    }

    private fun leFloat(v: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    @Test
    fun floatStatDecodesAsAFloatWhenTheTypeWasCached() {
        val dir = statsDirWith("winratio" to leFloat(0.75f), "numwins" to leInt(7))

        val (values, types) = Html5AchievementSeed.readStatFiles(
            dir,
            mapOf("winratio" to "float", "numwins" to "int"),
        )

        assertEquals(0.75f, values["winratio"])
        assertEquals("float", types["winratio"])
        assertEquals(7, values["numwins"])
        assertEquals("int", types["numwins"])
    }

    @Test
    fun withoutAHintAFloatStillReadsAsItsBitPattern() {
        // the unavoidable floor: no cache entry (never seeded online on this device) means no
        // type, so the old int reading stands. 0.75f -> 0x3F400000.
        val dir = statsDirWith("winratio" to leFloat(0.75f))

        val (values, types) = Html5AchievementSeed.readStatFiles(dir)

        assertEquals(0x3F400000, values["winratio"])
        assertEquals("int", types["winratio"])
    }

    @Test
    fun hintsMatchTheLowercaseStatFileNames() {
        // schema names arrive in original case; GoldbergSaveFiles lowercases the FILE name.
        val dir = statsDirWith("winratio" to leFloat(0.5f))

        val (values, _) = Html5AchievementSeed.readStatFiles(dir, mapOf("WinRatio" to "float"))

        assertEquals(0.5f, values["winratio"])
    }
}
