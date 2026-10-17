package app.gamenative.data

import app.gamenative.enums.PathType
import app.gamenative.utils.SteamUtils
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CloudKeyPlaceholderTest {

    @Before
    fun setUp() {
        mockkObject(SteamUtils)
        every { SteamUtils.getSteamId64() } returns 76561198000000001L
        every { SteamUtils.getSteam3AccountId() } returns 39735273L
    }

    @After
    fun tearDown() {
        unmockkObject(SteamUtils)
    }

    @Test
    fun lowercasePlaceholderIsSubstituted() {
        val pattern = SaveFilePattern(
            root = PathType.WinAppDataLocal,
            path = "Sifu/Saved/SaveGames/{64bitsteamid}",
            pattern = "*.sav",
        )
        assertEquals("Sifu/Saved/SaveGames/76561198000000001", pattern.substitutedPath)
        assertEquals("%WinAppDataLocal%Sifu/Saved/SaveGames/76561198000000001", pattern.prefix)
        assertEquals("a/39735273", SteamUtils.substituteSteamIdPlaceholders("a/{steam3accountid}"))
    }

    @Test
    fun gsePlaceholdersAreCaseInsensitive() {
        assertEquals(
            "Sifu/{::64BitSteamID::}/{::Steam3AccountID::}",
            SteamUtils.gseSteamIdPlaceholders("Sifu/{64bitsteamid}/{STEAM3ACCOUNTID}"),
        )
    }

    @Test
    fun backslashSavePathProducesForwardSlashCloudKeys() {
        val pattern = SaveFilePattern(
            root = PathType.WinMyDocuments,
            path = "Square Enix\\Just Cause 3\\Saves\\{64BitSteamID}\\",
            pattern = "*.*",
        )
        assertEquals("%WinMyDocuments%Square Enix/Just Cause 3/Saves/76561198000000001/", pattern.prefix)

        val file = UserFileInfo(
            root = PathType.WinMyDocuments,
            path = pattern.substitutedPath,
            filename = "save.dat",
            timestamp = 0L,
            sha = ByteArray(0),
            cloudRoot = pattern.uploadRoot,
            cloudPath = pattern.uploadPath,
        )
        assertEquals("%WinMyDocuments%Square Enix/Just Cause 3/Saves/76561198000000001", file.prefix)
        assertEquals("%WinMyDocuments%Square Enix/Just Cause 3/Saves/76561198000000001/save.dat", file.prefixPath)
        assertEquals("Square Enix/Just Cause 3/Saves/76561198000000001/", file.substitutedPath)
    }
}
