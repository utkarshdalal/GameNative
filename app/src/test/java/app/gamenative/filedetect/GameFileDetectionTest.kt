package app.gamenative.filedetect

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameFileDetectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `properties map a stored detection`() {
        val json = """{"rv":"abcdef012345","at":1700000000,"engines":["Unity","Unreal"],"anticheat":["EasyAntiCheat"],"sdks":["SteamworksNET","UnityBurst"],"launchers":[],"emulators":["DOSBOX"],"bitness":64}"""
        val props = GameFileDetection.propertiesFromJson(json)
        assertEquals("unity", props["engine"])
        assertEquals(listOf("EasyAntiCheat"), props["detected_anticheat"])
        assertEquals(listOf("SteamworksNET", "UnityBurst"), props["detected_sdks"])
        assertFalse(props.containsKey("detected_launchers"))
        assertEquals(listOf("DOSBOX"), props["detected_emulators"])
        assertEquals(64, props["exe_bitness"])
        assertEquals("abcdef012345", props["file_detection_rules"])
    }

    @Test
    fun `properties are empty for missing or broken metadata`() {
        assertTrue(GameFileDetection.propertiesFromJson("").isEmpty())
        assertTrue(GameFileDetection.propertiesFromJson("not json").isEmpty())
        val props = GameFileDetection.propertiesFromJson("""{"rv":"x","engines":[]}""")
        assertEquals(mapOf<String, Any>("file_detection_rules" to "x"), props)
    }

    @Test
    fun `detects a unity layout on disk`() {
        val root = tmp.newFolder("game")
        val exe = File(root, "Game.exe").also { it.writeBytes(pe(0x8664)) }
        File(root, "UnityPlayer.dll").writeBytes(ByteArray(16))
        File(root, "Game_Data/Managed").mkdirs()
        File(root, "Game_Data/globalgamemanagers.assets").writeBytes(ByteArray(16))
        File(root, "Game_Data/Managed/Assembly-CSharp.dll").writeBytes(ByteArray(16))
        File(root, "Game_Data/Plugins/x86_64").mkdirs()
        File(root, "Game_Data/Plugins/x86_64/Steamworks.NET.dll").writeBytes(ByteArray(16))

        val result = GameFileDetection.detectDirectory(root, exe, Fixtures.ruleSet)
        assertEquals(listOf("Unity"), result.detection.engines)
        assertTrue(result.detection.toString(), "SteamworksNET" in result.detection.sdks)
        assertEquals(64, result.bitness)
        assertEquals(5, result.fileCount)

        val json = GameFileDetection.toJson(result, "deadbeef0000")
        assertEquals("deadbeef0000", json.getString("rv"))
        assertEquals("Unity", json.getJSONArray("engines").getString(0))
        assertEquals(64, json.getInt("bitness"))
        assertTrue(json.getLong("at") > 1_600_000_000L)
        val props = GameFileDetection.propertiesFromJson(json.toString())
        assertEquals("unity", props["engine"])
        assertEquals(64, props["exe_bitness"])
    }

    @Test
    fun `bitness is read from the pe header`() {
        assertEquals(64, GameFileDetection.exeBitness(tmp.newFile("a.exe").also { it.writeBytes(pe(0x8664)) }))
        assertEquals(32, GameFileDetection.exeBitness(tmp.newFile("b.exe").also { it.writeBytes(pe(0x014c)) }))
        assertEquals(64, GameFileDetection.exeBitness(tmp.newFile("c.exe").also { it.writeBytes(pe(0xaa64)) }))
        assertNull(GameFileDetection.exeBitness(tmp.newFile("d.exe").also { it.writeBytes(ByteArray(128)) }))
        assertNull(GameFileDetection.exeBitness(tmp.newFile("e.exe").also { it.writeBytes(byteArrayOf(0x4d, 0x5a)) }))
        assertNull(GameFileDetection.exeBitness(File(tmp.root, "missing.exe")))
    }

    @Test
    fun `rules version is the first twelve hex digits of sha256`() {
        assertEquals("e3b0c44298fc", FileDetectionRules.versionOf(""))
        assertEquals(12, FileDetectionRules.versionOf(Fixtures.iniText).length)
        JSONObject().put("rv", FileDetectionRules.versionOf(Fixtures.iniText))
    }

    private fun pe(machine: Int): ByteArray {
        val peOffset = 0x80
        val buf = ByteBuffer.allocate(peOffset + 24).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 'M'.code.toByte())
        buf.put(1, 'Z'.code.toByte())
        buf.putInt(0x3c, peOffset)
        buf.putInt(peOffset, 0x00004550)
        buf.putShort(peOffset + 4, machine.toShort())
        return buf.array()
    }
}
