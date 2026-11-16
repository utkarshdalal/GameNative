package app.gamenative.service.ubisoft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbisoftCloudSyncPlannerTest {
    private fun local(key: String, stamp: String? = null, marker: Boolean = false) =
        UbisoftLocalSlot(contentKey = key, size = 10, stampedHash = stamp, uploadHash = key, wantsUpload = marker)

    private fun remote(hash: String) = UbisoftRemoteSlot(hash, 10)

    private val synced = UbisoftSyncedSlot(contentKey = "aaaa", remoteHash = "h1", size = 10)

    private fun decide(
        l: UbisoftLocalSlot?,
        r: UbisoftRemoteSlot?,
        s: UbisoftSyncedSlot?,
        deleted: Boolean = false,
        recordExists: Boolean = true,
    ) = UbisoftCloudSyncPlanner.decide(l, r, s, deleted, recordExists)

    @Test fun nothingAnywhereIsNone() {
        assertEquals(UbisoftCloudAction.NONE, decide(null, null, null))
        assertEquals(UbisoftCloudAction.NONE, decide(null, null, synced))
    }

    @Test fun missingSaveWithoutADeleteFileIsRestoredFromTheCloud() {
        assertEquals(UbisoftCloudAction.DOWNLOAD, decide(null, remote("h1"), null))
        assertEquals(UbisoftCloudAction.DOWNLOAD, decide(null, remote("H1"), synced))
        assertEquals(UbisoftCloudAction.DOWNLOAD, decide(null, remote("h2"), synced))
    }

    @Test fun localOnlyUploadsWhenTheCloudNeverHadIt() {
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("aaaa"), null, null))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb"), null, synced, recordExists = false))
    }

    @Test fun slotRemovedFromTheCloudIsKeptOrConflicts() {
        assertEquals(UbisoftCloudAction.NONE, decide(local("aaaa"), null, synced))
        assertEquals(UbisoftCloudAction.CONFLICT, decide(local("bbbb"), null, synced))
        val locals = mapOf(1 to local("bbbb"))
        val state = mapOf(1 to synced)
        val remotePref = UbisoftCloudSyncPlanner.planPull(locals, emptyMap(), state, UbisoftCloudPreference.REMOTE)
        assertTrue(remotePref.download.isEmpty())
        assertTrue(remotePref.upload.isEmpty())
        assertEquals(setOf(1), remotePref.keptRemoved)
        assertEquals(setOf(1), UbisoftCloudSyncPlanner.planPull(locals, emptyMap(), state, UbisoftCloudPreference.LOCAL).upload)
        assertEquals(setOf(1), UbisoftCloudSyncPlanner.planPull(locals, emptyMap(), state, UbisoftCloudPreference.NONE).conflicts)
        assertEquals(setOf(1), UbisoftCloudSyncPlanner.planPull(locals, emptyMap(), state, UbisoftCloudPreference.NONE, recordExists = false).upload)
    }

    @Test fun stampWithoutStateFollowsTheUploadMarker() {
        assertEquals(UbisoftCloudAction.NONE, decide(local("cccc", stamp = "h1"), remote("H1"), null))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("cccc", stamp = "h1", marker = true), remote("h1"), null))
    }

    @Test fun stampCopiedForwardIsNotInSyncWhenStateExists() {
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb", stamp = "h1"), remote("h1"), synced))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb", stamp = "h1", marker = true), remote("h1"), synced))
        assertEquals(UbisoftCloudAction.CONFLICT, decide(local("cccc", stamp = "h9"), remote("h9"), synced))
        assertEquals(UbisoftCloudAction.NONE, decide(local("aaaa", stamp = "h1", marker = true), remote("h1"), synced))
    }

    @Test fun deleteFileWithoutASaveIsNeverDownloaded() {
        assertEquals(UbisoftCloudAction.NONE, decide(null, remote("h2"), synced, deleted = true))
        assertEquals(UbisoftCloudAction.NONE, decide(null, remote("h1"), null, deleted = true))
        val plan = UbisoftCloudSyncPlanner.planPull(emptyMap(), mapOf(1 to remote("h1"), 2 to remote("h2")), emptyMap(), UbisoftCloudPreference.REMOTE, setOf(1))
        assertEquals(setOf(2), plan.download)
        assertTrue(plan.upload.isEmpty())
    }

    @Test fun syncedContentAndHashIsInSync() {
        assertEquals(UbisoftCloudAction.NONE, decide(local("aaaa"), remote("h1"), synced))
    }

    @Test fun noStateAndDifferentContentConflicts() {
        assertEquals(UbisoftCloudAction.CONFLICT, decide(local("aaaa"), remote("h1"), null))
        assertEquals(UbisoftCloudAction.CONFLICT, decide(local("aaaa", stamp = "h0"), remote("h1"), null))
    }

    @Test fun keptLocalPairSettlesUntilEitherSideChanges() {
        val kept = UbisoftSyncedSlot(contentKey = "bbbb", remoteHash = "h2", size = 10)
        assertEquals(UbisoftCloudAction.NONE, decide(local("bbbb"), remote("h2"), kept))
        assertEquals(UbisoftCloudAction.DOWNLOAD, decide(local("bbbb"), remote("h3"), kept))
        assertEquals(UbisoftCloudAction.CONFLICT, decide(local("cccc"), remote("h3"), kept))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("cccc"), remote("h2"), kept))
    }

    @Test fun remoteChangedOnlyDownloads() {
        assertEquals(UbisoftCloudAction.DOWNLOAD, decide(local("aaaa"), remote("h2"), synced))
        assertEquals(UbisoftCloudAction.DOWNLOAD, decide(local("aaaa", stamp = "h1"), remote("h2"), synced))
    }

    @Test fun localChangedOnlyUploads() {
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb"), remote("h1"), synced))
    }

    @Test fun bothChangedConflicts() {
        assertEquals(UbisoftCloudAction.CONFLICT, decide(local("bbbb"), remote("h2"), synced))
    }

    @Test fun recordNamingTheLocalBytesRetriesTheUpload() {
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb"), remote("BBBB"), null))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb"), remote("bbbb"), synced))
        assertEquals(UbisoftCloudAction.NONE, decide(local("bbbb", stamp = "bbbb"), remote("bbbb"), null))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb", stamp = "h0"), remote("bbbb"), null))
        assertEquals(UbisoftCloudAction.UPLOAD, decide(local("bbbb", stamp = "h1"), remote("bbbb"), synced))
    }

    @Test fun planPullAppliesPreferenceOnlyToConflicts() {
        val locals = mapOf(1 to local("bbbb"), 2 to local("aaaa"), 4 to local("aaaa"))
        val remotes = mapOf(1 to remote("h2"), 2 to remote("h2"), 3 to remote("h3"))
        val state = mapOf(1 to synced, 2 to synced)

        val none = UbisoftCloudSyncPlanner.planPull(locals, remotes, state, UbisoftCloudPreference.NONE)
        assertTrue(none.conflict)
        assertEquals(setOf(1), none.conflicts)
        assertEquals(setOf(2, 3), none.download)
        assertEquals(setOf(4), none.upload)

        val remotePref = UbisoftCloudSyncPlanner.planPull(locals, remotes, state, UbisoftCloudPreference.REMOTE)
        assertFalse(remotePref.conflict)
        assertEquals(setOf(1, 2, 3), remotePref.download)
        assertEquals(setOf(4), remotePref.upload)

        val localPref = UbisoftCloudSyncPlanner.planPull(locals, remotes, state, UbisoftCloudPreference.LOCAL)
        assertFalse(localPref.conflict)
        assertEquals(setOf(2, 3), localPref.download)
        assertEquals(setOf(1, 4), localPref.upload)
        assertEquals(setOf(1), localPref.forcedLocal)
        assertTrue(none.forcedLocal.isEmpty())
    }

    @Test fun planPushTakesChangedOrUnknownSlots() {
        val locals = mapOf(1 to local("aaaa"), 2 to local("bbbb"), 3 to local("cccc"))
        val state = mapOf(1 to synced, 2 to synced, 5 to synced)
        assertEquals(setOf(2, 3), UbisoftCloudSyncPlanner.planPush(locals, state))
        assertTrue(UbisoftCloudSyncPlanner.planPush(emptyMap(), state).isEmpty())
    }
}
