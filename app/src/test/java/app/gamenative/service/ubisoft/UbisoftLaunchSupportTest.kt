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
        val json = UbisoftLaunchSupport.sessionJson(creds, "7656", 220240, "persona", "QUJD", null, "en-US")
        assertEquals("ticket", json.getString("ticket"))
        assertEquals("sid", json.getString("sessionId"))
        assertEquals(1_800_000_000L, json.getLong("expiresAt"))
        assertEquals("uid", json.getString("userId"))
        assertEquals("pid", json.getString("profileId"))
        assertEquals("player@example.com", json.getString("email"))
        assertEquals("7656", json.getString("steamId"))
        assertEquals(220240, json.getInt("steamAppId"))
        assertEquals("QUJD", json.getString("encryptedAppTicket"))
        assertEquals("persona", json.getString("steamPersona"))
        assertEquals("en-US", json.getString("language"))
        assertFalse(json.toString().contains("long-lived"))
        assertFalse(json.has("link_consent"))
    }

    @Test fun optionalFieldsAreLeftOut() {
        val json = UbisoftLaunchSupport.sessionJson(creds, "7656", 1, null, null, null)
        assertFalse(json.has("encryptedAppTicket"))
        assertFalse(json.has("steamPersona"))
    }

    @Test fun aConsentSurvivesOnlyForTheSameAccountUserAndGame() {
        val consent = UbisoftLaunchSupport.consentJson("uid", "7656", 220240, 46, 1000)
        assertTrue(UbisoftLaunchSupport.sessionJson(creds, "7656", 220240, null, null, consent).has("link_consent"))
        assertFalse(UbisoftLaunchSupport.sessionJson(creds, "9999", 220240, null, null, consent).has("link_consent"))
        assertFalse(UbisoftLaunchSupport.sessionJson(creds, "7656", 1, null, null, consent).has("link_consent"))
        assertFalse(UbisoftLaunchSupport.sessionJson(creds.copy(userId = "other"), "7656", 220240, null, null, consent).has("link_consent"))
        val kept = UbisoftLaunchSupport.sessionJson(creds, "7656", 220240, null, null, consent).getJSONObject("link_consent")
        assertEquals(46L, kept.getLong("productId"))
        assertEquals(1000L, kept.getLong("time"))
    }

    @Test fun linkNeededMapsToTheConfirmation() {
        val status = JSONObject().put("status", "needs_link").put("exitCode", 9).put("productId", 46)
            .put("userId", "uid").put("nameOnPlatform", "Player").put("maskedEmail", "p***@e***.com")
        val outcome = UbisoftLaunchSupport.mapStatus(status, 220240, creds) as UbisoftExitOutcome.NeedsLink
        assertEquals(46L, outcome.productId)
        assertEquals(220240, outcome.steamAppId)
        assertEquals("Player", outcome.nameOnPlatform)
        assertEquals("p***@e***.com", outcome.maskedEmail)
    }

    @Test fun aLinkWithoutAProductIsAnError() {
        val status = JSONObject().put("exitCode", 9)
        assertEquals(UbisoftExitOutcome.Failed(9), UbisoftLaunchSupport.mapStatus(status, 1, creds))
    }

    @Test fun exitCodesMap() {
        fun map(code: Int, status: String = "error") =
            UbisoftLaunchSupport.mapStatus(JSONObject().put("status", status).put("exitCode", code), 1, creds)
        assertEquals(UbisoftExitOutcome.SessionRejected, map(3))
        assertEquals(UbisoftExitOutcome.Failed(8), map(8))
        assertEquals(UbisoftExitOutcome.Failed(10), map(10))
        assertEquals(UbisoftExitOutcome.Failed(2), map(2))
        assertNull(map(0))
        assertNull(map(3, status = "ok"))
        assertNull(map(1))
        assertNull(map(0, status = "exited"))
        // a refused account is reported, not answered with another sign-in
        assertEquals(UbisoftExitOutcome.Failed(3), map(3, status = "banned"))
    }

    @Test fun emailIsMaskedLikeTheStubMasksIt() {
        assertEquals("p***@e***.com", UbisoftLaunchSupport.maskEmail("player@example.com"))
        assertEquals("***", UbisoftLaunchSupport.maskEmail("nobody"))
        assertEquals("", UbisoftLaunchSupport.maskEmail(""))
    }

    @Test fun detectsLoaderFilesOnlyInTheRootOrOneLevelDown() {
        val game = temporary.newFolder()
        assertFalse(UbisoftGameDetection.needsStandIn(game))
        File(game, "bin").mkdirs()
        File(game, "bin/readme.txt").writeText("x")
        assertFalse(UbisoftGameDetection.needsStandIn(game))
        File(game, "bin/upc_r1_loader.dll").writeBytes(byteArrayOf(77, 90))
        assertTrue(UbisoftGameDetection.needsStandIn(game))
        assertEquals(File(game, "bin"), UbisoftGameDetection.loaderDir(game))

        val deep = temporary.newFolder()
        File(deep, "a/b").mkdirs()
        File(deep, "a/b/uplay_r1_loader.dll").writeText("x")
        assertFalse(UbisoftGameDetection.needsStandIn(deep))

        val root = temporary.newFolder()
        File(root, "UPC_R2_LOADER64.DLL").writeText("x")
        assertTrue(UbisoftGameDetection.needsStandIn(root))

        val own = temporary.newFolder()
        File(own, UbisoftHelperDeployment.DIRECTORY).mkdirs()
        File(own, "${UbisoftHelperDeployment.DIRECTORY}/uplay_r1_loader.dll").writeText("x")
        assertFalse(UbisoftGameDetection.needsStandIn(own))
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
        file.writeText("farcry3_d3d11.exe\n")
        assertEquals("farcry3_d3d11.exe", UbisoftHelperDeployment.gameExecutable(game))
        file.writeText("C:\\evil.exe")
        assertNull(UbisoftHelperDeployment.gameExecutable(game))
        file.writeText("..\\up.exe")
        assertNull(UbisoftHelperDeployment.gameExecutable(game))
    }
}
