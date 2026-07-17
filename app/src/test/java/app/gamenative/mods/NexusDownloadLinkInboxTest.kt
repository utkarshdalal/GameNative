package app.gamenative.mods

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusDownloadLinkInboxTest {
    @Test
    fun pendingDownload_ttlBoundaryIsStale() {
        val now = 4_000_000_000L
        val ttl = NexusDownloadLinkInbox.PENDING_DOWNLOAD_TTL_SECONDS

        assertFalse(pendingDownload("ttl-fresh").copy(createdAtEpochSeconds = now - ttl + 1).isPastPendingTtl(now))
        assertTrue(pendingDownload("ttl-stale").copy(createdAtEpochSeconds = now - ttl).isPastPendingTtl(now))
    }

    @Test
    fun acceptedSideEffect_runsOnlyWhenExpectationIsRegistered() {
        val pending = pendingDownload(appId = "accepted-side-effect", requestId = "first")
        var acceptedCount = 0

        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending) { acceptedCount++ })
            assertFalse(
                NexusDownloadLinkInbox.expect(pending.copy(requestId = "second")) { acceptedCount++ },
            )
            assertEquals(1, acceptedCount)
        } finally {
            NexusDownloadLinkInbox.cancelExpected(pending.appId, pending.reference, pending.requestId)
        }
    }

    @Test
    fun acceptedSideEffect_runsBeforeInMemoryRegistration() = runBlocking {
        val pending = pendingDownload(appId = "persistence-order", modId = 58278L, fileId = 12346L)
        val callbackUrl = callbackUrl(modId = 58278L, fileId = 12346L)
        var callbackDuringAcceptance: NexusModReference? = null

        try {
            assertTrue(
                NexusDownloadLinkInbox.expect(pending) {
                    callbackDuringAcceptance = NexusDownloadLinkInbox.submit(callbackUrl)
                },
            )
            assertNull(callbackDuringAcceptance)

            assertNotNull(NexusDownloadLinkInbox.submit(callbackUrl))
            val delivered = withTimeout(1_000L) {
                NexusDownloadLinkInbox.callbacksFor(pending.appId).first()
            }
            assertEquals(pending, delivered.pending)
        } finally {
            NexusDownloadLinkInbox.cancelExpected(pending.appId, pending.reference)
        }
    }

    @Test
    fun blankAuthorizationDoesNotConsumeExpectedDownload() = runBlocking {
        val pending = pendingDownload(appId = "blank-authorization", modId = 58279L, fileId = 12347L)
        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending))

            assertNull(
                NexusDownloadLinkInbox.submit(
                    "nxm://newvegas/mods/58279/files/12347?key=&expires=4000000000",
                ),
            )
            assertNotNull(NexusDownloadLinkInbox.submit(callbackUrl(modId = 58279L, fileId = 12347L)))
            val delivered = withTimeout(1_000L) {
                NexusDownloadLinkInbox.callbacksFor(pending.appId).first()
            }
            assertEquals(pending, delivered.pending)
        } finally {
            NexusDownloadLinkInbox.cancelExpected(pending.appId, pending.reference)
        }
    }

    @Test
    fun failedAcceptedSideEffect_doesNotRegisterExpectation() {
        val pending = pendingDownload(appId = "failed-persistence", modId = 58280L, fileId = 12348L)

        val failure = runCatching {
            NexusDownloadLinkInbox.expect(pending) { error("Persistence failed") }
        }.exceptionOrNull()

        assertEquals("Persistence failed", failure?.message)
        assertNull(NexusDownloadLinkInbox.submit(callbackUrl(modId = 58280L, fileId = 12348L)))
    }

    private fun callbackUrl(modId: Long, fileId: Long): String =
        "nxm://newvegas/mods/$modId/files/$fileId?key=signed-grant&expires=4000000000&user_id=99"

    private fun pendingDownload(
        appId: String,
        modId: Long = 58277L,
        fileId: Long = 12345L,
        requestId: String? = null,
    ): PendingNexusWebsiteDownload =
        PendingNexusWebsiteDownload(
            appId = appId,
            reference = NexusModReference("newvegas", modId, fileId),
            modInfo = NexusModInfo(modId, "Test mod", "", "1.0"),
            file = NexusModFile(
                fileId = fileId,
                name = "Test file",
                version = "1.0",
                fileName = "test.zip",
                sizeBytes = 1L,
                uploadedTimestamp = 1L,
            ),
            nexusUserId = 99L,
            requestId = requestId,
        )
}
