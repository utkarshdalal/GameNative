package app.gamenative.service.rockstar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RockstarCloudSyncPlannerTest {

    private val a = RockstarLocalState("aaaa", 10)
    private val b = RockstarLocalState("bbbb", 10)
    private val synced = RockstarSyncedState(version = 3, md5 = "aaaa", size = 10, serverModified = null)

    private fun decide(local: RockstarLocalState?, remote: RockstarRemoteState?, state: RockstarSyncedState?) =
        RockstarCloudSyncPlanner.decide(local, remote, state)

    @Test
    fun nothingAnywhereIsNone() {
        assertEquals(RockstarCloudAction.NONE, decide(null, null, null))
        assertEquals(RockstarCloudAction.NONE, decide(null, null, synced))
    }

    @Test
    fun remoteMatchingUploadMd5IsNone() {
        val local = RockstarLocalState("plain", 10, uploadMd5 = "blob")
        assertEquals(RockstarCloudAction.NONE, decide(local, RockstarRemoteState(1, "BLOB"), null))
        assertEquals(RockstarCloudAction.NONE, decide(local, RockstarRemoteState(1, "plain"), null))
        assertEquals(RockstarCloudAction.CONFLICT, decide(local, RockstarRemoteState(1, "other"), null))
    }

    @Test
    fun cloudOnlyDownloads() {
        assertEquals(RockstarCloudAction.DOWNLOAD, decide(null, RockstarRemoteState(1, "aaaa"), null))
        assertEquals(RockstarCloudAction.DOWNLOAD, decide(null, RockstarRemoteState(3, "aaaa"), synced))
    }

    @Test
    fun localOnlyUploads() {
        assertEquals(RockstarCloudAction.UPLOAD, decide(a, null, null))
        assertEquals(RockstarCloudAction.UPLOAD, decide(a, null, synced))
    }

    @Test
    fun identicalContentIsNoneEvenWithoutState() {
        assertEquals(RockstarCloudAction.NONE, decide(a, RockstarRemoteState(7, "AAAA"), null))
        assertEquals(RockstarCloudAction.NONE, decide(a, RockstarRemoteState(9, "aaaa"), synced))
    }

    @Test
    fun localUnchangedAndServerNewerDownloads() {
        assertEquals(RockstarCloudAction.DOWNLOAD, decide(a, RockstarRemoteState(4, "cccc"), synced))
        assertEquals(RockstarCloudAction.DOWNLOAD, decide(a, RockstarRemoteState(4, null), synced))
    }

    @Test
    fun localChangedAndServerUnchangedUploads() {
        assertEquals(RockstarCloudAction.UPLOAD, decide(b, RockstarRemoteState(3, "aaaa"), synced))
        assertEquals(RockstarCloudAction.UPLOAD, decide(RockstarLocalState("aaaa", 11), RockstarRemoteState(3, null), synced))
    }

    @Test
    fun bothChangedConflicts() {
        assertEquals(RockstarCloudAction.CONFLICT, decide(b, RockstarRemoteState(4, "cccc"), synced))
    }

    @Test
    fun bothUnchangedIsNone() {
        assertEquals(RockstarCloudAction.NONE, decide(a, RockstarRemoteState(3, null), synced))
    }

    @Test
    fun noStateAndDifferentContentConflicts() {
        assertEquals(RockstarCloudAction.CONFLICT, decide(a, RockstarRemoteState(2, "cccc"), null))
        assertEquals(RockstarCloudAction.CONFLICT, decide(a, RockstarRemoteState(2, null), null))
    }

    @Test
    fun planPullAppliesPreferenceOnlyToConflicts() {
        val names = listOf("S0", "S1", "S2", "S3")
        val local = mapOf("S0" to b, "S1" to a, "S3" to a)
        val remote = mapOf("S0" to RockstarRemoteState(4, "cccc"), "S1" to RockstarRemoteState(4, "cccc"), "S2" to RockstarRemoteState(1, "dddd"))
        val state = mapOf("S0" to synced, "S1" to synced)

        val none = RockstarCloudSyncPlanner.planPull(names, local, remote, state, RockstarCloudPreference.NONE)
        assertTrue(none.conflict)
        assertEquals(setOf("S0"), none.conflicts)
        assertEquals(setOf("S1", "S2"), none.download)
        assertEquals(setOf("S3"), none.upload)

        val remotePref = RockstarCloudSyncPlanner.planPull(names, local, remote, state, RockstarCloudPreference.REMOTE)
        assertFalse(remotePref.conflict)
        assertEquals(setOf("S0", "S1", "S2"), remotePref.download)
        assertTrue(remotePref.forcedLocal.isEmpty())

        val localPref = RockstarCloudSyncPlanner.planPull(names, local, remote, state, RockstarCloudPreference.LOCAL)
        assertFalse(localPref.conflict)
        assertEquals(setOf("S1", "S2"), localPref.download)
        assertEquals(setOf("S0", "S3"), localPref.upload)
        assertEquals(setOf("S0"), localPref.forcedLocal)
    }

    @Test
    fun planPushUploadsOnlyChangedOrNewLocalFiles() {
        val names = listOf("S0", "S1", "S2", "S3")
        val local = mapOf("S0" to a, "S1" to b, "S2" to a)
        val state = mapOf("S0" to synced, "S1" to synced, "S3" to synced)
        assertEquals(setOf("S1", "S2"), RockstarCloudSyncPlanner.planPush(names, local, state))
    }

    @Test
    fun planPushIgnoresNamesOutsideTheList() {
        val local = mapOf("cloudsavedata.dat" to a, "S0" to b)
        assertEquals(setOf("S0"), RockstarCloudSyncPlanner.planPush(listOf("S0"), local, emptyMap()))
    }

    @Test
    fun timestampsRoundTripInTheManifestShape() {
        val millis = 1_700_000_000_123L
        assertEquals("2023-11-14T22:13:20Z", RockstarCloudSyncPlanner.formatTimestamp(null, millis))
        assertEquals("2023-11-14T22:13:20.123Z", RockstarCloudSyncPlanner.formatTimestamp("2020-01-01T00:00:00.000Z", millis))
        assertEquals("2023-11-14T22:13:20.1230000", RockstarCloudSyncPlanner.formatTimestamp("2020-01-01T00:00:00.0000000", millis))
        assertEquals("2023-11-14T22:13:20", RockstarCloudSyncPlanner.formatTimestamp("2020-01-01T00:00:00", millis))
        assertEquals("1700000000", RockstarCloudSyncPlanner.formatTimestamp("1600000000", millis))
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", RockstarCloudSyncPlanner.formatTimestamp("Wed, 01 Jan 2020 00:00:00 GMT", millis))

        assertEquals(1_700_000_000_000L, RockstarCloudSyncPlanner.parseTimestamp("2023-11-14T22:13:20Z"))
        assertEquals(millis, RockstarCloudSyncPlanner.parseTimestamp("2023-11-14T22:13:20.1230000"))
        assertEquals(1_700_000_000_000L, RockstarCloudSyncPlanner.parseTimestamp("2023-11-15T00:13:20+02:00"))
        assertEquals(1_700_000_000_000L, RockstarCloudSyncPlanner.parseTimestamp("1700000000"))
        assertEquals(1_700_000_000_000L, RockstarCloudSyncPlanner.parseTimestamp("Tue, 14 Nov 2023 22:13:20 GMT"))
        assertNull(RockstarCloudSyncPlanner.parseTimestamp("not a date"))
        assertNull(RockstarCloudSyncPlanner.parseTimestamp(null))
    }
}
