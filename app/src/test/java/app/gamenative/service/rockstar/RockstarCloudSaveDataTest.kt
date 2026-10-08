package app.gamenative.service.rockstar

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RockstarCloudSaveDataTest {
    @get:Rule val temporary = TemporaryFolder()

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        fun bits(count: Int, value: Long = 0) = apply { for (i in count - 1 downTo 0) bits.add((value ushr i) and 1L == 1L) }
        fun bytes(data: ByteArray) = apply { data.forEach { bits(8, it.toLong() and 0xff) } }
        fun text(value: String) = bytes(value.toByteArray())
        fun zeros(count: Int) = bytes(ByteArray(count))
        fun toByteArray(): ByteArray = ByteArray((bits.size + 7) / 8) { i ->
            (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits.getOrElse(i * 8 + b) { false }) 1 else 0) }.toByte()
        }
    }

    private val gta5Json = """{"Autosave":1,"Slot":15,"LastMission":"Franklin and Lamar","CompletionPct":1.59420,"PosixTime":1790535331,"PlayerCharacterIndex":1}"""
    private val profileJson = """{"SaveType":1,"CompletionPct":0.00,"PosixTime":1790253588,"AutoSave":false,"SlotNumber":-1,"DisplayName":"A {b} \"c\""}"""
    private val thirdJson = """{"Slot":3,"Nested":{"a":[1,2]}}"""

    private fun BitWriter.record(name: String, json: String) = apply {
        bits(32, 1).zeros(6).text(name).zeros(390 - name.length)
        text("f>m.F").bytes(byteArrayOf(-1, -1, -1, -1)).text(json).zeros(128)
    }

    private fun layout(): ByteArray = BitWriter()
        .bits(2)
        .bits(32, 1).bits(32, 0).bits(32, 1).bits(32, 3)
        .record("SGTA50015", gta5Json)
        .record("ProfileSettings", profileJson)
        .bits(3, 5)
        .record("SaveGame1.lanoire", thirdJson)
        .zeros(64)
        .toByteArray()

    @Test fun recoversEveryRecordAcrossBitAlignments() {
        val metadata = RockstarCloudSaveData.parse(layout())
        assertEquals(
            mapOf("SGTA50015" to gta5Json, "ProfileSettings" to profileJson, "SaveGame1.lanoire" to thirdJson),
            metadata,
        )
    }

    @Test fun readsFromFile() {
        val file = File(temporary.root, "cloudsavedata.dat").apply { writeBytes(layout()) }
        assertEquals(gta5Json, RockstarCloudSaveData.readMetadata(file)["SGTA50015"])
    }

    @Test fun skipsJsonWithoutPrecedingName() {
        val data = BitWriter().bits(5).zeros(8).bytes(byteArrayOf(-1, -1)).text(gta5Json).zeros(8).toByteArray()
        assertTrue(RockstarCloudSaveData.parse(data).isEmpty())
    }

    @Test fun garbageInputGivesEmptyMap() {
        assertTrue(RockstarCloudSaveData.parse(ByteArray(0)).isEmpty())
        assertTrue(RockstarCloudSaveData.parse(ByteArray(2506)).isEmpty())
        assertTrue(RockstarCloudSaveData.parse(Random(42).nextBytes(4096)).isEmpty())
        val truncated = BitWriter().bits(2).zeros(4).text("SGTA50015").zeros(8).text(gta5Json.take(40)).toByteArray()
        assertTrue(RockstarCloudSaveData.parse(truncated).isEmpty())
        assertTrue(RockstarCloudSaveData.readMetadata(File(temporary.root, "missing.dat")).isEmpty())
        assertTrue(RockstarCloudSaveData.readMetadata(temporary.newFolder("dir")).isEmpty())
    }

    private fun single(name: String, json: String): ByteArray =
        BitWriter().bits(3).zeros(4).record(name, json).zeros(16).toByteArray()

    @Test fun acceptsUtf8Strings() {
        val json = """{"Slot":2,"LastMission":"Négociation – 救援","PosixTime":1790535331}"""
        assertEquals(json, RockstarCloudSaveData.parse(single("SGTA50002", json))["SGTA50002"])
    }

    @Test fun acceptsWhitespaceBetweenTokens() {
        val json = "{\n\t\"Slot\": 4,\r\n\t\"LastMission\": \"Prologue\"\n}"
        assertEquals(json, RockstarCloudSaveData.parse(single("SGTA50004", json))["SGTA50004"])
    }

    @Test fun rejectsRawControlByteInString() {
        val json = "{\"Slot\":5,\"LastMission\":\"Pro\u0001logue\"}"
        assertTrue(RockstarCloudSaveData.parse(single("SGTA50005", json)).isEmpty())
    }

    @Test fun pathologicalInputFinishesQuickly() {
        val started = System.nanoTime()
        val result = RockstarCloudSaveData.parse(ByteArray(100 * 1024) { '{'.code.toByte() })
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.isEmpty())
        assertTrue("took ${elapsedMs}ms", elapsedMs < 2000)
    }
}
