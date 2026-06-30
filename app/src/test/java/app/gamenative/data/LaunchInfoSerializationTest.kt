package app.gamenative.data

import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.EnumSet

class LaunchInfoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTrip_preservesArguments() {
        val info = LaunchInfo(
            executable = "hl2.exe",
            arguments = "-game portal",
            workingDir = "",
            description = "Play Portal",
            type = "default",
            configOS = EnumSet.of(OS.windows),
            configArch = OSArch.Arch64,
        )

        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<LaunchInfo>(encoded)

        assertEquals("-game portal", decoded.arguments)
        assertEquals("hl2.exe", decoded.executable)
        assertEquals("Play Portal", decoded.description)
    }

    @Test
    fun deserialize_defaultsArgumentsToEmpty_whenFieldMissing() {
        // configOS is serialized as an int bitmask (windows = 0x01 = 1), configArch as a string name
        val jsonString = """
            {
                "executable": "game.exe",
                "workingDir": "",
                "description": "",
                "type": "default",
                "configOS": 1,
                "configArch": "Arch64"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<LaunchInfo>(jsonString)

        assertEquals("", decoded.arguments)
        assertEquals("game.exe", decoded.executable)
    }

    @Test
    fun roundTrip_handlesEmptyArguments() {
        val info = LaunchInfo(
            executable = "game.exe",
            arguments = "",
            workingDir = "bin",
            description = "",
            type = "none",
            configOS = EnumSet.of(OS.windows),
            configArch = OSArch.Arch32,
        )

        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<LaunchInfo>(encoded)

        assertEquals("", decoded.arguments)
    }

    @Test
    fun roundTrip_preservesUriExecutable() {
        val info = LaunchInfo(
            executable = "link2ea://launchgame/Origin.OFR.50.0002694",
            arguments = "",
            workingDir = "",
            description = "Play Game",
            type = "default",
            configOS = EnumSet.of(OS.windows),
            configArch = OSArch.Arch64,
        )

        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<LaunchInfo>(encoded)

        assertEquals("link2ea://launchgame/Origin.OFR.50.0002694", decoded.executable)
    }
}
