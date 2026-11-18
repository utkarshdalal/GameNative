package app.gamenative.ui.component.dialog

import app.gamenative.mods.NexusDownloadAuthorization
import app.gamenative.mods.NexusAuthState
import app.gamenative.mods.NexusConnectionState
import app.gamenative.mods.NexusModFile
import app.gamenative.mods.NexusModInfo
import app.gamenative.mods.NexusModReference
import app.gamenative.mods.NexusOAuthAccount
import app.gamenative.mods.NexusUserInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusModsDialogBrowserFirstTest {
    @Test
    fun nexusUserInfo_isAlwaysDerivedFromTheCurrentAuthAccount() {
        val accountA = NexusAuthState(
            connection = NexusConnectionState.CONNECTED,
            account = NexusOAuthAccount("41", "Account A"),
        ).currentNexusUserInfo()
        val expiredSession = NexusAuthState(
            connection = NexusConnectionState.DISCONNECTED,
        ).currentNexusUserInfo()
        val accountB = NexusAuthState(
            connection = NexusConnectionState.CONNECTED,
            account = NexusOAuthAccount(
                id = "42",
                name = "Account B",
                membershipRoles = listOf("premium"),
            ),
        ).currentNexusUserInfo()

        assertEquals(41L, accountA?.userId)
        assertFalse(accountA?.isPremium == true)
        assertNull(expiredSession)
        assertEquals(42L, accountB?.userId)
        assertEquals("Account B", accountB?.name)
        assertTrue(accountB?.isPremium == true)
    }

    @Test
    fun currentDownloadUser_readsAccountAtExecutionAfterSwitch() = runBlocking {
        var currentUser = NexusUserInfo("Account A", 41L, false)
        val liveAccountProvider: suspend () -> NexusUserInfo = { currentUser }

        currentUser = NexusUserInfo("Account B", 42L, false)

        assertEquals(
            currentUser,
            currentNexusUserForDownload(reference(userId = 42L), liveAccountProvider),
        )
    }

    @Test
    fun currentDownloadUser_rejectsGrantFromPreviousAccount() = runBlocking {
        val currentUser = NexusUserInfo("Account B", 42L, false)

        assertNull(
            currentNexusUserForDownload(
                reference = reference(userId = 41L),
                getCurrentUser = { currentUser },
            ),
        )
    }

    @Test
    fun resolveBrowserFirst_wrongAccountStopsBeforeMetadataLookup() = runBlocking {
        var metadataCalls = 0

        val result = resolveBrowserFirstNexusDownload(
            reference = reference(userId = 99L),
            getCurrentUser = { NexusUserInfo("Other account", 100L, false) },
            getModInfo = { _, _ ->
                metadataCalls++
                modInfo()
            },
            getModFiles = { _, _ ->
                metadataCalls++
                listOf(file())
            },
            nowEpochSeconds = { 100L },
        )

        assertEquals(BrowserFirstNexusResolution.WrongAccount, result)
        assertEquals(0, metadataCalls)
    }

    @Test
    fun resolveBrowserFirst_returnsOnlyTheExactlyAuthorizedFile() = runBlocking {
        val expectedFile = file(fileId = 34L)

        val result = resolveBrowserFirstNexusDownload(
            reference = reference(userId = 99L),
            getCurrentUser = { NexusUserInfo("Same account", 99L, false) },
            getModInfo = { domain, modId ->
                assertEquals("newvegas", domain)
                assertEquals(12L, modId)
                modInfo()
            },
            getModFiles = { _, _ -> listOf(file(fileId = 33L), expectedFile, file(fileId = 35L)) },
            nowEpochSeconds = { 100L },
        )

        assertTrue(result is BrowserFirstNexusResolution.Resolved)
        val resolved = result as BrowserFirstNexusResolution.Resolved
        assertEquals(expectedFile, resolved.file)
        assertEquals(34L, resolved.file.fileId)
    }

    @Test
    fun resolveBrowserFirst_missingExactFileDoesNotResolve() = runBlocking {
        val result = resolveBrowserFirstNexusDownload(
            reference = reference(userId = 99L),
            getCurrentUser = { NexusUserInfo("Same account", 99L, false) },
            getModInfo = { _, _ -> modInfo() },
            getModFiles = { _, _ -> listOf(file(fileId = 35L)) },
            nowEpochSeconds = { 100L },
        )

        assertEquals(BrowserFirstNexusResolution.MissingFile, result)
    }

    @Test
    fun resolveBrowserFirst_rechecksExpiryAfterMetadataLookup() = runBlocking {
        val clockValues = ArrayDeque(listOf(100L, 200L))

        val result = resolveBrowserFirstNexusDownload(
            reference = reference(userId = 99L, expires = 200L),
            getCurrentUser = { NexusUserInfo("Same account", 99L, false) },
            getModInfo = { _, _ -> modInfo() },
            getModFiles = { _, _ -> listOf(file()) },
            nowEpochSeconds = { clockValues.removeFirst() },
        )

        assertEquals(BrowserFirstNexusResolution.Expired, result)
    }

    @Test
    fun resolveBrowserFirst_rechecksAccountAfterMetadataLookup() = runBlocking {
        val users = ArrayDeque(
            listOf(
                NexusUserInfo("Original account", 99L, false),
                NexusUserInfo("Replacement account", 100L, false),
            ),
        )

        val result = resolveBrowserFirstNexusDownload(
            reference = reference(userId = 99L),
            getCurrentUser = { users.removeFirst() },
            getModInfo = { _, _ -> modInfo() },
            getModFiles = { _, _ -> listOf(file()) },
            nowEpochSeconds = { 100L },
        )

        assertEquals(BrowserFirstNexusResolution.WrongAccount, result)
    }

    private fun reference(userId: Long, expires: Long = 400L): NexusModReference =
        NexusModReference(
            gameDomain = "newvegas",
            modId = 12L,
            fileId = 34L,
            downloadAuthorization = NexusDownloadAuthorization(
                key = "signed-grant",
                expires = expires,
                userId = userId,
            ),
        )

    private fun modInfo(): NexusModInfo = NexusModInfo(
        modId = 12L,
        name = "Test mod",
        summary = "Summary",
        version = "1.0",
    )

    private fun file(fileId: Long = 34L): NexusModFile = NexusModFile(
        fileId = fileId,
        name = "Test file $fileId",
        version = "1.0",
        fileName = "test-$fileId.zip",
        sizeBytes = 1L,
        uploadedTimestamp = 1L,
    )
}
