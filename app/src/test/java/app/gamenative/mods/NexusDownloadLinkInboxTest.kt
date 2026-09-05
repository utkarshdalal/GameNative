package app.gamenative.mods

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    @Test
    fun clearAll_removesExpectationsAndDrainsBufferedGrantsWithoutClosingCollectors() = runBlocking {
        val pending = pendingDownload(appId = "clear-all", modId = 58281L, fileId = 12349L)
        val callback = callbackUrl(modId = 58281L, fileId = 12349L)
        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending))
            assertNotNull(NexusDownloadLinkInbox.submit(callback))

            NexusDownloadLinkInbox.clearAll()

            assertNull(
                withTimeoutOrNull(50L) {
                    NexusDownloadLinkInbox.callbacksFor(pending.appId).first()
                },
            )
            assertTrue(NexusDownloadLinkInbox.expect(pending.copy(requestId = "after-reconnect")))
            assertNotNull(NexusDownloadLinkInbox.submit(callbackUrl(modId = 58281L, fileId = 12349L, key = "fresh-grant")))
            val delivered = withTimeout(1_000L) {
                NexusDownloadLinkInbox.callbacksFor(pending.appId).first()
            }
            assertEquals("after-reconnect", delivered.pending.requestId)
        } finally {
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun browserFirst_routesOnlyToExactlyOneActiveDialog() = runBlocking {
        val registration = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        val callback = callbackUrl(modId = 60001L, fileId = 70001L, key = "browser-first")
        try {
            val result = NexusDownloadLinkInbox.submitIntent(callback)

            assertTrue(result is NexusNxmSubmission.BrowserFirst)
            val delivered = withTimeout(1_000L) {
                NexusDownloadLinkInbox.browserFirstCallbacksFor("STEAM_22380").first()
            }
            assertEquals(60001L, delivered.reference.modId)
            assertEquals(70001L, delivered.reference.fileId)
        } finally {
            registration.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun browserFirst_withoutActiveDialog_isNotQueuedForLaterDialog() = runBlocking {
        val callback = callbackUrl(modId = 60002L, fileId = 70002L, key = "no-target")

        assertEquals(NexusNxmSubmission.NoActiveTarget, NexusDownloadLinkInbox.submitIntent(callback))

        val registration = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        try {
            assertNull(
                withTimeoutOrNull(50L) {
                    NexusDownloadLinkInbox.browserFirstCallbacksFor("STEAM_22380").first()
                },
            )
        } finally {
            registration.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun browserFirst_withMultipleActiveDialogs_isRejectedAsAmbiguous() = runBlocking {
        val first = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        val second = NexusDownloadLinkInbox.registerReceiver("GOG_1454587428")
        try {
            val result = NexusDownloadLinkInbox.submitIntent(
                callbackUrl(modId = 60003L, fileId = 70003L, key = "ambiguous"),
            )

            assertEquals(NexusNxmSubmission.AmbiguousTarget, result)
            assertNull(
                withTimeoutOrNull(50L) {
                    NexusDownloadLinkInbox.browserFirstCallbacksFor("STEAM_22380").first()
                },
            )
            assertNull(
                withTimeoutOrNull(50L) {
                    NexusDownloadLinkInbox.browserFirstCallbacksFor("GOG_1454587428").first()
                },
            )
        } finally {
            first.unregister()
            second.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun exactExpectedDownload_winsOverMultipleActiveDialogs() = runBlocking {
        val pending = pendingDownload(appId = "STEAM_22380", modId = 60004L, fileId = 70004L)
        val first = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        val second = NexusDownloadLinkInbox.registerReceiver("GOG_1454587428")
        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending))
            val result = NexusDownloadLinkInbox.submitIntent(
                callbackUrl(modId = 60004L, fileId = 70004L, key = "expected-wins"),
            )

            assertTrue(result is NexusNxmSubmission.Expected)
            assertEquals("STEAM_22380", (result as NexusNxmSubmission.Expected).appId)
            assertEquals(
                pending,
                withTimeout(1_000L) {
                    NexusDownloadLinkInbox.callbacksFor("STEAM_22380").first()
                }.pending,
            )
        } finally {
            first.unregister()
            second.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun exactExpectedDownload_preservesCompatibilityWhenUserIdIsOmitted() = runBlocking {
        val pending = pendingDownload(appId = "STEAM_22380", modId = 60007L, fileId = 70007L)
        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending))
            val result = NexusDownloadLinkInbox.submitIntent(
                "nxm://newvegas/mods/60007/files/70007?key=expected-no-user&expires=4000000000",
            )

            assertTrue(result is NexusNxmSubmission.Expected)
            assertEquals(
                pending,
                withTimeout(1_000L) {
                    NexusDownloadLinkInbox.callbacksFor("STEAM_22380").first()
                }.pending,
            )
        } finally {
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun duplicateExpectedGrant_cannotFallThroughAsBrowserFirst() = runBlocking {
        val pending = pendingDownload(appId = "STEAM_22380", modId = 60005L, fileId = 70005L)
        val callback = callbackUrl(modId = 60005L, fileId = 70005L, key = "one-use")
        val registration = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending))
            assertTrue(NexusDownloadLinkInbox.submitIntent(callback) is NexusNxmSubmission.Expected)
            withTimeout(1_000L) { NexusDownloadLinkInbox.callbacksFor("STEAM_22380").first() }

            assertEquals(NexusNxmSubmission.Replayed, NexusDownloadLinkInbox.submitIntent(callback))
            assertNull(
                withTimeoutOrNull(50L) {
                    NexusDownloadLinkInbox.browserFirstCallbacksFor("STEAM_22380").first()
                },
            )
        } finally {
            registration.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun expiredAndMalformedBrowserFirstGrants_areDistinguished() {
        val registration = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        try {
            assertEquals(
                NexusNxmSubmission.Expired,
                NexusDownloadLinkInbox.submitIntent(
                    "nxm://newvegas/mods/60006/files/70006?key=expired&expires=1&user_id=99",
                ),
            )
            assertEquals(
                NexusNxmSubmission.Malformed,
                NexusDownloadLinkInbox.submitIntent(
                    "nxm://newvegas/mods/60006/files/70006?key=missing-user&expires=4000000000",
                ),
            )
        } finally {
            registration.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun expiredExpectedGrant_doesNotConsumeExpectation() = runBlocking {
        val pending = pendingDownload(appId = "STEAM_22380", modId = 60010L, fileId = 70010L)
        try {
            assertTrue(NexusDownloadLinkInbox.expect(pending))

            assertEquals(
                NexusNxmSubmission.Expired,
                NexusDownloadLinkInbox.submitIntent(
                    "nxm://newvegas/mods/60010/files/70010?key=expired-expected&expires=1&user_id=99",
                ),
            )
            assertTrue(
                NexusDownloadLinkInbox.submitIntent(
                    callbackUrl(modId = 60010L, fileId = 70010L, key = "valid-after-expired"),
                ) is NexusNxmSubmission.Expected,
            )
            val delivered = withTimeout(1_000L) {
                NexusDownloadLinkInbox.callbacksFor(pending.appId).first()
            }
            assertEquals(pending, delivered.pending)
            assertEquals("valid-after-expired", delivered.reference.downloadAuthorization?.key)
        } finally {
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun browserFirst_duplicateRegistrationsForSameGame_areNotAmbiguous() = runBlocking {
        val first = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        val second = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        try {
            val result = NexusDownloadLinkInbox.submitIntent(
                callbackUrl(modId = 60008L, fileId = 70008L, key = "same-game-overlap"),
            )

            assertTrue(result is NexusNxmSubmission.BrowserFirst)
            assertEquals(
                60008L,
                withTimeout(1_000L) {
                    NexusDownloadLinkInbox.browserFirstCallbacksFor("STEAM_22380").first()
                }.reference.modId,
            )
        } finally {
            first.unregister()
            second.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    @Test
    fun browserFirst_grantBufferedForClosedDialog_isDiscarded() = runBlocking {
        val first = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        assertTrue(
            NexusDownloadLinkInbox.submitIntent(
                callbackUrl(modId = 60009L, fileId = 70009L, key = "closed-dialog"),
            ) is NexusNxmSubmission.BrowserFirst,
        )
        first.unregister()

        val reopened = NexusDownloadLinkInbox.registerReceiver("STEAM_22380")
        try {
            assertNull(
                withTimeoutOrNull(50L) {
                    NexusDownloadLinkInbox.browserFirstCallbacksFor("STEAM_22380").first()
                },
            )
        } finally {
            reopened.unregister()
            NexusDownloadLinkInbox.clearAll()
        }
    }

    private fun callbackUrl(modId: Long, fileId: Long, key: String = "signed-grant"): String =
        "nxm://newvegas/mods/$modId/files/$fileId?key=$key&expires=4000000000&user_id=99"

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
