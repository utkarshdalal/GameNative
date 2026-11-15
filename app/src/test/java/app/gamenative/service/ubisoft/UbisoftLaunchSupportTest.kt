package app.gamenative.service.ubisoft

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UbisoftLaunchSupportTest {
    @get:Rule val temporary = TemporaryFolder()

    private val creds = UbisoftCredentials(
        ticket = "ticket", sessionId = "sid", expiresAt = 1_800_000_000_000L, userId = "uid", profileId = "pid",
        nameOnPlatform = "Player", email = "player@example.com", country = "US", rememberMeTicket = "long-lived",
    )

    @Test fun sessionFileCarriesTheSessionButNotRememberMe() {
        val json = UbisoftLaunchSupport.sessionJson(creds, "7656", 1234, "persona", "QUJD", "en-US")
        assertEquals("ticket", json.getString("ticket"))
        assertEquals("sid", json.getString("sessionId"))
        assertEquals(1_800_000_000L, json.getLong("expiresAt"))
        assertEquals("uid", json.getString("userId"))
        assertEquals("pid", json.getString("profileId"))
        assertEquals("player@example.com", json.getString("email"))
        assertEquals("7656", json.getString("steamId"))
        assertEquals(1234, json.getInt("steamAppId"))
        assertEquals("QUJD", json.getString("encryptedAppTicket"))
        assertEquals("persona", json.getString("steamPersona"))
        assertEquals("en-US", json.getString("language"))
        assertFalse(json.toString().contains("long-lived"))
    }

    @Test fun optionalFieldsAreLeftOut() {
        val json = UbisoftLaunchSupport.sessionJson(creds, "7656", 1, null, null)
        assertFalse(json.has("encryptedAppTicket"))
        assertFalse(json.has("steamPersona"))
    }

    @Test fun exitCodesMap() {
        fun map(code: Int, status: String = "error") =
            UbisoftLaunchSupport.mapStatus(JSONObject().put("status", status).put("exitCode", code))
        assertEquals(UbisoftExitOutcome.SessionRejected, map(3))
        assertEquals(UbisoftExitOutcome.Failed(8), map(8))
        assertEquals(UbisoftExitOutcome.Failed(10), map(10))
        assertEquals(UbisoftExitOutcome.Failed(2), map(2))
        assertNull(map(0))
        assertNull(map(1))
        // a game that exits with one of the stub's own codes is still a normal run
        assertNull(map(3, status = "exited"))
        assertNull(map(8, status = "exited"))
        // a missing or expired session file is not a refusal and keeps the account
        assertEquals(UbisoftExitOutcome.SessionExpired, map(3, status = "session_expired"))
        assertEquals(UbisoftExitOutcome.SessionExpired, map(3, status = "no_session"))
        assertEquals(UbisoftExitOutcome.SessionRejected, map(3, status = "session_rejected"))
        // a refused account is reported, not answered with another sign-in
        assertEquals(UbisoftExitOutcome.Failed(3), map(3, status = "banned"))
    }

    @Test fun detectsLoaderFilesOnlyInTheRootOrOneLevelDown() {
        val game = temporary.newFolder()
        assertFalse(UbisoftLaunchSupport.isUbisoftTitle(game))
        File(game, "bin").mkdirs()
        File(game, "bin/readme.txt").writeText("x")
        assertFalse(UbisoftLaunchSupport.isUbisoftTitle(game))
        File(game, "bin/upc_r1_loader.dll").writeBytes(byteArrayOf(77, 90))
        assertTrue(UbisoftLaunchSupport.isUbisoftTitle(game))

        val deep = temporary.newFolder()
        File(deep, "a/b").mkdirs()
        File(deep, "a/b/uplay_r1_loader.dll").writeText("x")
        assertFalse(UbisoftLaunchSupport.isUbisoftTitle(deep))

        val root = temporary.newFolder()
        File(root, "UPC_R2_LOADER64.DLL").writeText("x")
        assertTrue(UbisoftLaunchSupport.isUbisoftTitle(root))

        val own = temporary.newFolder()
        File(own, UbisoftHelperDeployment.DIRECTORY).mkdirs()
        File(own, "${UbisoftHelperDeployment.DIRECTORY}/uplay_r1_loader.dll").writeText("x")
        assertFalse(UbisoftLaunchSupport.isUbisoftTitle(own))

        val rockstar = temporary.newFolder()
        File(rockstar, "uplay_r1_loader.dll").writeText("x")
        File(rockstar, "socialclub.dll").writeText("x")
        assertFalse(UbisoftLaunchSupport.isUbisoftTitle(rockstar))
    }

    @Test fun stubArgumentsQuoteTheOriginalExe() {
        assertEquals("\"C:\\g\\bin\\run.exe\" -steam_mode", UbisoftHelperDeployment.stubArguments("C:\\g\\bin\\run.exe", " -steam_mode "))
        assertEquals("\"C:\\g\\run.exe\"", UbisoftHelperDeployment.stubArguments("C:\\g\\run.exe", ""))
        assertEquals(
            "\"--workdir=C:\\g\\bin\" \"C:\\g\\bin\\run.exe\" -a",
            UbisoftHelperDeployment.stubArguments("C:\\g\\bin\\run.exe", "-a", "C:\\g\\bin\\"),
        )
    }

    @Test fun gameExecutableIsValidated() {
        val game = temporary.newFolder()
        File(game, UbisoftHelperDeployment.DIRECTORY).mkdirs()
        val file = File(game, "${UbisoftHelperDeployment.DIRECTORY}/game-executable.txt")
        assertNull(UbisoftHelperDeployment.gameExecutable(game))
        file.writeText("game.exe\n")
        assertEquals("game.exe", UbisoftHelperDeployment.gameExecutable(game))
        file.writeText("C:\\evil.exe")
        assertNull(UbisoftHelperDeployment.gameExecutable(game))
        file.writeText("..\\up.exe")
        assertNull(UbisoftHelperDeployment.gameExecutable(game))
    }
}
