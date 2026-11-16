package app.gamenative.service.ubisoft

import app.gamenative.service.ubisoft.UbisoftCloudSavesManager.PullResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UbisoftCloudSavesManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeTransport : UbisoftCloudTransport {
        var record: UbisoftCloudEntity? = null
        val objects = HashMap<String, ByteArray>()
        var failUpload = false
        var failUploadsLeft = 0
        var owned = listOf(UbisoftOwnedProduct(productId = 99, uplayId = 46, steamAppId = 1234))
        val calls = ArrayList<String>()
        var uploadedProductId: Long? = null

        override fun entity(uplayId: Long): UbisoftCloudEntity? = record.also { calls += "entity" }

        override fun download(uplayId: Long, filenames: List<String>): Map<String, ByteArray> {
            calls += "download"
            return filenames.mapNotNull { name -> objects[name]?.let { name to it } }.toMap()
        }

        override fun upload(uplayId: Long, productId: Long?, files: Map<String, ByteArray>) {
            calls += "upload"
            if (failUpload) throw UbisoftCloudException("upload refused", 500)
            if (failUploadsLeft > 0) {
                failUploadsLeft--
                throw UbisoftCloudException("upload refused", 500)
            }
            uploadedProductId = productId
            objects.putAll(files)
        }

        override fun writeEntity(
            uplayId: Long,
            previous: UbisoftCloudEntity?,
            slots: List<UbisoftCloudSlot>,
            timestampSeconds: Long,
            pcName: String,
        ): UbisoftCloudEntity {
            calls += "write"
            if (previous?.revision != record?.revision) throw UbisoftCloudException("revision refused", 409)
            return UbisoftCloudEntity("entity", (record?.revision ?: 0) + 1, null, slots, timestampSeconds.toString(), pcName)
                .also { record = it }
        }

        override fun ownedProducts(): List<UbisoftOwnedProduct> = owned.also { calls += "owned" }

        override fun close() = Unit
    }

    private lateinit var transport: FakeTransport
    private lateinit var target: UbisoftCloudSavesManager.Target
    private lateinit var env: UbisoftCloudSavesManager.Env
    private val backups = ArrayList<List<String>>()

    @After fun tearDown() {
        UbisoftCloudSavesManager.resetForTest()
    }

    @Before fun setUp() {
        // The upload tests below exercise the push path; the download-only tests switch it off again.
        UbisoftCloudSavesManager.uploadsEnabled = true
        val root = temporary.newFolder("container")
        target = UbisoftCloudSavesManager.Target(
            saveDir = File(root, ".wine/drive_c/users/xuser/Saved Games/Ubisoft/user/46"),
            stateDir = File(root, ".ubisoft_cloud"),
            userId = "user",
            uplayId = 46,
            steamAppId = 1234,
        )
        env = UbisoftCloudSavesManager.Env({ 1_700_000_000_000L }, "Test device", { _, files -> backups += files.map { it.name } })
        transport = FakeTransport()
    }

    private fun save(payload: String, stamp: String? = null): ByteArray {
        val header = ByteArray(40)
        header[0] = 0x24
        header[1] = 0x02
        stamp?.toByteArray(Charsets.ISO_8859_1)?.copyInto(header, 8)
        return header + payload.toByteArray()
    }

    private fun cloudSlot(slot: Int, bytes: ByteArray, hash: String = UbisoftSaveFile.contentKey(bytes)) =
        UbisoftCloudSlot(slot, slot.toString(), hash, bytes.size.toLong(), "2023-11-14T22:13:20Z")

    private fun cloud(revision: Int, vararg slots: Pair<UbisoftCloudSlot, ByteArray>) {
        transport.record = UbisoftCloudEntity("entity", revision, null, slots.map { it.first }, "1700000000", "PC")
        for ((slot, bytes) in slots) transport.objects[slot.filename] = bytes
    }

    private fun stamped(bytes: ByteArray) = UbisoftSaveFile.stamp(bytes, UbisoftSaveFile.newHash(bytes))

    private fun localFile(slot: Int) = File(target.saveDir, "$slot.save")

    private fun writeLocal(slot: Int, bytes: ByteArray) {
        target.saveDir.mkdirs()
        localFile(slot).writeBytes(bytes)
    }

    private fun synced(revision: Int?, vararg slots: Pair<Int, ByteArray>, remoteHash: (ByteArray) -> String = { UbisoftSaveFile.contentKey(it) }) {
        UbisoftCloudSavesManager.saveState(
            target,
            UbisoftCloudSavesManager.State(
                "user",
                46,
                revision,
                slots.associate { (slot, bytes) ->
                    slot to UbisoftSyncedSlot(UbisoftSaveFile.contentKey(bytes), remoteHash(bytes), bytes.size.toLong())
                },
            ),
        )
    }

    private fun pull(preference: UbisoftCloudPreference = UbisoftCloudPreference.NONE): PullResult =
        runBlocking { UbisoftCloudSavesManager.pullCore(transport, target, env, preference) }

    private fun push(): Boolean = UbisoftCloudSavesManager.pushCore(transport, target, env)

    private fun state() = UbisoftCloudSavesManager.loadState(target)

    @Test fun pullDownloadsIntoAnEmptyFolder() {
        val bytes = save("cloud", stamp = "0123456789abcdef0123456789abcdef")
        cloud(3, cloudSlot(1, bytes, "0123456789abcdef0123456789abcdef") to bytes)

        assertEquals(PullResult.Synced, pull())

        assertArrayEquals(bytes, localFile(1).readBytes())
        assertTrue(backups.isEmpty())
        val state = assertNotNullState()
        assertEquals(3, state.revision)
        assertEquals("0123456789abcdef0123456789abcdef", state.slots.getValue(1).remoteHash)
        assertEquals(listOf("1.save"), target.saveDir.list()!!.toList())
    }

    @Test fun pullBacksUpAndReplacesAnOlderLocalSave() {
        val old = save("old")
        writeLocal(1, old)
        File(target.saveDir, "1.save.upload").writeText("")
        synced(2, 1 to old)
        val bytes = save("new", stamp = "0123456789abcdef0123456789abcdef")
        cloud(3, cloudSlot(1, bytes, "0123456789abcdef0123456789abcdef") to bytes)

        assertEquals(PullResult.Synced, pull())

        assertArrayEquals(bytes, localFile(1).readBytes())
        assertEquals(listOf(listOf("1.save")), backups)
        assertFalse(File(target.saveDir, "1.save.upload").exists())
    }

    @Test fun sizeMismatchChangesNothing() {
        val old = save("old")
        writeLocal(1, old)
        synced(2, 1 to old)
        val bytes = save("new")
        cloud(3, cloudSlot(1, bytes).copy(size = bytes.size + 1L) to bytes)
        val stateBefore = File(target.stateDir, "state.json").readText()

        assertTrue(runCatching { pull() }.isFailure)

        assertArrayEquals(old, localFile(1).readBytes())
        assertEquals(stateBefore, File(target.stateDir, "state.json").readText())
        assertTrue(backups.isEmpty())
    }

    @Test fun stampedHashMismatchChangesNothing() {
        val old = save("old")
        writeLocal(1, old)
        synced(2, 1 to old)
        val bytes = save("new", stamp = "ffffffffffffffffffffffffffffffff")
        cloud(3, cloudSlot(1, bytes, "0123456789abcdef0123456789abcdef") to bytes)

        assertTrue(runCatching { pull() }.isFailure)

        assertArrayEquals(old, localFile(1).readBytes())
        assertEquals(2, state()!!.revision)
    }

    @Test fun conflictReportsAndChangesNothing() {
        val mine = save("mine")
        writeLocal(1, mine)
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)

        val result = pull()

        assertTrue(result is PullResult.Conflict)
        assertEquals(1_700_000_000_000L, (result as PullResult.Conflict).remoteMillis)
        assertArrayEquals(mine, localFile(1).readBytes())
        assertNull(state())
        assertFalse("download" in transport.calls)
    }

    @Test fun remotePreferenceDownloadsTheConflict() {
        writeLocal(1, save("mine"))
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)

        assertEquals(PullResult.Synced, pull(UbisoftCloudPreference.REMOTE))

        assertArrayEquals(theirs, localFile(1).readBytes())
        assertFalse("write" in transport.calls)
    }

    @Test fun localPreferenceUploadsTheConflictAtLaunch() {
        val mine = save("mine")
        writeLocal(1, mine)
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)

        assertEquals(PullResult.Synced, pull(UbisoftCloudPreference.LOCAL))

        assertEquals(4, transport.record!!.revision)
        assertEquals(UbisoftSaveFile.newHash(mine), transport.record!!.slots.single().hash)
        assertArrayEquals(stamped(mine), localFile(1).readBytes())
        assertEquals(4, state()!!.revision)
    }

    @Test fun stampMatchingTheRecordWithoutStateIsAdoptedAsSynced() {
        val hash = "0123456789abcdef0123456789abcdef"
        val mine = save("based on the cloud copy", stamp = hash)
        writeLocal(1, mine)
        val theirs = save("cloud", stamp = hash)
        cloud(3, cloudSlot(1, theirs, hash) to theirs)

        assertEquals(PullResult.Synced, pull())

        assertArrayEquals(mine, localFile(1).readBytes())
        assertEquals(listOf("entity"), transport.calls)
        assertEquals(UbisoftSyncedSlot(UbisoftSaveFile.contentKey(mine), hash, mine.size.toLong()), state()!!.slots[1])
    }

    @Test fun stampMatchingTheRecordWithAnUploadMarkerUploadsAtLaunch() {
        val hash = "0123456789abcdef0123456789abcdef"
        val mine = save("played offline", stamp = hash)
        writeLocal(1, mine)
        File(target.saveDir, "1.save.upload").writeText("")
        val theirs = save("cloud", stamp = hash)
        cloud(3, cloudSlot(1, theirs, hash) to theirs)

        assertEquals(PullResult.Synced, pull())

        assertEquals(UbisoftSaveFile.newHash(mine), transport.record!!.slots.single().hash)
        assertFalse(File(target.saveDir, "1.save.upload").exists())
    }

    @Test fun deletedSlotIsNotDownloadedNorDroppedFromTheCloud() {
        val one = save("one")
        cloud(3, cloudSlot(1, one) to one)
        target.saveDir.mkdirs()
        File(target.saveDir, "1.save.delete").writeBytes(one)

        assertEquals(PullResult.Synced, pull())
        assertFalse(localFile(1).exists())
        assertFalse("download" in transport.calls)

        writeLocal(2, save("two"))
        assertTrue(push())
        assertEquals(listOf("1", "2"), transport.record!!.slots.map { it.filename })
        assertFalse(localFile(1).exists())
    }

    @Test fun firstPullWithNothingAnywhereLeavesStateForTheExitPush() {
        assertEquals(PullResult.Synced, pull())
        val state = assertNotNullState()
        assertNull(state.revision)
        assertTrue(state.slots.isEmpty())

        val mine = save("first save")
        writeLocal(1, mine)
        assertTrue(push())

        val record = transport.record!!
        assertEquals(1, record.revision)
        assertEquals("Test device", record.pcName)
        assertEquals(UbisoftSaveFile.stamp(mine, UbisoftSaveFile.newHash(mine)).toList(), transport.objects.getValue("1").toList())
        assertEquals(99L, transport.uploadedProductId)
    }

    @Test fun pushNeverRunsWithAnEmptyFolder() {
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)
        synced(3, 1 to theirs)
        target.saveDir.mkdirs()
        File(target.saveDir, "1.save.upload").writeText("")

        assertTrue(push())

        assertTrue(transport.calls.isEmpty())
    }

    @Test fun pushAbortsWhenTheRecordRevisionChanged() {
        val base = save("base")
        writeLocal(1, save("changed"))
        synced(3, 1 to base)
        cloud(4, cloudSlot(1, base) to base)

        assertFalse(push())

        assertEquals(listOf("entity"), transport.calls)
        assertEquals(4, transport.record!!.revision)
    }

    @Test fun pushKeepsUnrelatedCloudSlotsAndBacksUpTheReplacedCopy() {
        val one = save("one")
        val two = save("two")
        cloud(3, cloudSlot(1, one) to one, cloudSlot(2, two) to two)
        synced(3, 1 to one, 2 to two)
        val changed = save("one changed")
        writeLocal(1, changed)
        localFile(1).setLastModified(1_600_000_000_000L)
        writeLocal(2, two)

        assertTrue(push())

        val record = transport.record!!
        assertEquals(4, record.revision)
        assertEquals(listOf("1", "2"), record.slots.map { it.filename })
        assertEquals(UbisoftSaveFile.newHash(changed), record.slots[0].hash)
        assertEquals(cloudSlot(2, two), record.slots[1])
        assertArrayEquals(stamped(changed), transport.objects.getValue("1"))
        assertArrayEquals(two, transport.objects.getValue("2"))
        assertArrayEquals(stamped(changed), localFile(1).readBytes())
        assertEquals(1_600_000_000_000L, localFile(1).lastModified())
        assertArrayEquals(two, localFile(2).readBytes())
        val kept = File(target.stateDir, "remote_backup/46/1-${UbisoftSaveFile.contentKey(one)}.save")
        assertArrayEquals(one, kept.readBytes())
        assertTrue(transport.calls.indexOf("write") < transport.calls.indexOf("upload"))
        val state = state()!!
        assertEquals(4, state.revision)
        assertEquals(UbisoftSaveFile.newHash(changed), state.slots.getValue(1).remoteHash)
        assertEquals(UbisoftSaveFile.contentKey(two), state.slots.getValue(2).remoteHash)
    }

    @Test fun failedUploadLeavesTheSlotStateUnsavedAndTheNextPullRetries() {
        val one = save("one")
        cloud(3, cloudSlot(1, one) to one)
        synced(3, 1 to one)
        val changed = save("one changed")
        writeLocal(1, changed)
        File(target.saveDir, "1.save.upload").writeText("")
        transport.failUpload = true

        assertTrue(runCatching { push() }.isFailure)

        val afterFailure = state()!!
        assertEquals(4, afterFailure.revision)
        assertEquals(UbisoftSaveFile.contentKey(one), afterFailure.slots.getValue(1).contentKey)
        assertTrue(File(target.saveDir, "1.save.upload").exists())
        assertArrayEquals(changed, localFile(1).readBytes())
        assertArrayEquals(one, transport.objects.getValue("1"))

        transport.failUpload = false
        assertEquals(PullResult.Synced, pull())

        assertEquals(5, transport.record!!.revision)
        assertArrayEquals(stamped(changed), transport.objects.getValue("1"))
        assertArrayEquals(stamped(changed), localFile(1).readBytes())
        assertEquals(UbisoftSaveFile.contentKey(changed), state()!!.slots.getValue(1).contentKey)
        assertFalse(File(target.saveDir, "1.save.upload").exists())
    }

    private val uploadCalls = setOf("write", "upload", "owned")

    @Test fun downloadOnlyKeepLocalRecordsThePairAndLaterCloudChangesStillDownload() {
        UbisoftCloudSavesManager.uploadsEnabled = false
        val mine = save("mine")
        writeLocal(1, mine)
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)

        assertEquals(PullResult.Synced, pull(UbisoftCloudPreference.LOCAL))

        assertTrue(transport.calls.none { it in uploadCalls || it == "download" })
        assertArrayEquals(mine, localFile(1).readBytes())
        assertEquals(UbisoftSyncedSlot(UbisoftSaveFile.contentKey(mine), UbisoftSaveFile.contentKey(theirs), mine.size.toLong()), state()!!.slots[1])

        transport.calls.clear()
        assertEquals(PullResult.Synced, pull())
        assertEquals(listOf("entity"), transport.calls)

        val newer = save("newer")
        cloud(4, cloudSlot(1, newer) to newer)
        assertEquals(PullResult.Synced, pull())
        assertArrayEquals(newer, localFile(1).readBytes())
        assertTrue(transport.calls.none { it in uploadCalls })
    }

    @Test fun downloadOnlyKeepLocalThenBothSidesChangedPromptsAgain() {
        UbisoftCloudSavesManager.uploadsEnabled = false
        writeLocal(1, save("mine"))
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)
        assertEquals(PullResult.Synced, pull(UbisoftCloudPreference.LOCAL))

        writeLocal(1, save("mine, played more"))
        val newer = save("newer")
        cloud(4, cloudSlot(1, newer) to newer)

        assertTrue(pull() is PullResult.Conflict)
    }

    @Test fun downloadOnlyLocalOnlyChangeDoesNothing() {
        UbisoftCloudSavesManager.uploadsEnabled = false
        val one = save("one")
        cloud(3, cloudSlot(1, one) to one)
        synced(3, 1 to one)
        val changed = save("one changed")
        writeLocal(1, changed)
        File(target.saveDir, "1.save.upload").writeText("")
        val before = state()!!.slots[1]

        assertEquals(PullResult.Synced, pull())

        assertEquals(listOf("entity"), transport.calls)
        assertArrayEquals(changed, localFile(1).readBytes())
        assertEquals(before, state()!!.slots[1])
        assertTrue(File(target.saveDir, "1.save.upload").exists())
    }

    @Test fun downloadOnlyFirstSyncWithLocalFilesAndNoCloudDoesNothing() {
        UbisoftCloudSavesManager.uploadsEnabled = false
        val mine = save("mine")
        writeLocal(1, mine)

        assertEquals(PullResult.Synced, pull())

        assertEquals(listOf("entity"), transport.calls)
        assertNull(transport.record)
        assertArrayEquals(mine, localFile(1).readBytes())
        assertTrue(state()!!.slots.isEmpty())
    }

    @Test fun downloadOnlyStillPromptsAndKeepCloudDownloads() {
        UbisoftCloudSavesManager.uploadsEnabled = false
        writeLocal(1, save("mine"))
        File(target.saveDir, "1.save.upload").writeText("")
        val theirs = save("theirs")
        cloud(3, cloudSlot(1, theirs) to theirs)

        assertTrue(pull() is PullResult.Conflict)
        assertEquals(PullResult.Synced, pull(UbisoftCloudPreference.REMOTE))

        assertArrayEquals(theirs, localFile(1).readBytes())
        assertTrue(File(target.saveDir, "1.save.upload").exists())
        assertTrue(transport.calls.none { it in uploadCalls })
    }

    @Test fun downloadOnlyPushDoesNothingAndMakesNoTransportCall() {
        UbisoftCloudSavesManager.uploadsEnabled = false
        val one = save("one")
        cloud(3, cloudSlot(1, one) to one)
        synced(3, 1 to one)
        writeLocal(1, save("one changed"))
        transport.calls.clear()

        assertFalse(push())
        assertFalse(UbisoftCloudSavesManager.pushCore(transport, target, env, setOf(1)))

        assertTrue(transport.calls.isEmpty())
        assertArrayEquals(save("one changed"), localFile(1).readBytes())
    }

    @Test fun uploadIsRetriedOnceWithTheSameBytes() {
        val mine = save("mine")
        assertEquals(PullResult.Synced, pull())
        writeLocal(1, mine)
        transport.failUploadsLeft = 1

        assertTrue(push())

        assertEquals(2, transport.calls.count { it == "upload" })
        assertEquals(1, transport.calls.count { it == "write" })
        assertArrayEquals(stamped(mine), transport.objects.getValue("1"))
        assertEquals(UbisoftSaveFile.contentKey(mine), state()!!.slots.getValue(1).contentKey)
    }

    @Test fun exitPushSkipsASlotThatChangedInTheCloudAndPushesTheOthers() {
        val one = save("one")
        val two = save("two")
        val newer = save("one from another device")
        cloud(3, cloudSlot(1, newer) to newer, cloudSlot(2, two) to two)
        synced(3, 1 to one, 2 to two)
        writeLocal(1, save("one changed here"))
        writeLocal(2, save("two changed here"))

        assertTrue(push())

        val record = transport.record!!
        assertEquals(cloudSlot(1, newer), record.slots[0])
        assertArrayEquals(newer, transport.objects.getValue("1"))
        assertEquals(UbisoftSaveFile.newHash(save("two changed here")), record.slots[1].hash)
        assertEquals(UbisoftSyncedSlot(UbisoftSaveFile.contentKey(one), UbisoftSaveFile.contentKey(one), one.size.toLong()), state()!!.slots[1])
    }

    @Test fun exitPushDoesNotRecreateASlotTheCloudDropped() {
        val one = save("one")
        cloud(3)
        synced(3, 1 to one)
        writeLocal(1, save("one changed"))

        assertFalse(push())

        assertFalse("write" in transport.calls)
    }

    @Test fun missingCloudObjectKeepsTheOldStateSoTheExitPushStaysOff() {
        val one = save("one")
        writeLocal(1, one)
        synced(2, 1 to one)
        val newer = save("newer")
        transport.record = UbisoftCloudEntity("entity", 3, null, listOf(cloudSlot(1, newer)), "1700000000", "PC")

        assertEquals(PullResult.Synced, pull())
        assertEquals(UbisoftSaveFile.contentKey(one), state()!!.slots.getValue(1).remoteHash)
        assertEquals(3, state()!!.revision)

        writeLocal(1, save("one changed"))
        assertFalse(push())
        assertFalse("write" in transport.calls)
    }

    @Test fun wipedSaveFolderIsRestoredFromTheCloud() {
        val one = save("one")
        cloud(3, cloudSlot(1, one) to one)
        synced(3, 1 to one)

        assertEquals(PullResult.Synced, pull())

        assertArrayEquals(one, localFile(1).readBytes())
    }

    @Test fun conflictTimeIsTheNewestConflictingSlot() {
        val record = UbisoftCloudEntity(
            "entity",
            3,
            null,
            listOf(
                UbisoftCloudSlot(1, "1", "a", 1, "2023-11-14T22:13:20Z"),
                UbisoftCloudSlot(2, "2", "b", 1, "2023-11-14T22:13:21.500Z"),
                UbisoftCloudSlot(3, "3", "c", 1, "2030-01-01T00:00:00Z"),
                UbisoftCloudSlot(4, "4", "d", 1, "not a date"),
            ),
            "1600000000",
            "PC",
        )
        assertEquals(1_700_000_001_500L, UbisoftCloudSavesManager.conflictRemoteMillis(record, setOf(1, 2)))
        assertEquals(1_700_000_000_000L, UbisoftCloudSavesManager.conflictRemoteMillis(record, setOf(1)))
        assertEquals(1_600_000_000_000L, UbisoftCloudSavesManager.conflictRemoteMillis(record, setOf(4)))
        assertEquals(1_700_000_000_000L, UbisoftCloudSavesManager.parseTime("2023-11-15T00:13:20+02:00"))
        assertEquals(1_700_000_000_000L, UbisoftCloudSavesManager.parseTime("2023-11-14T22:13:20"))
        assertNull(UbisoftCloudSavesManager.parseTime(null))
    }

    @Test fun rememberedChoiceIsPerAccountAndShortLived() {
        val kept = UbisoftCloudSavesManager.Remembered(UbisoftCloudPreference.LOCAL, at = 1_000, userId = "user")
        val pick = UbisoftCloudSavesManager::effectivePreference
        assertEquals(UbisoftCloudPreference.LOCAL, pick(UbisoftCloudPreference.NONE, kept, "user", 2_000))
        assertEquals(UbisoftCloudPreference.NONE, pick(UbisoftCloudPreference.NONE, kept, "other", 2_000))
        assertEquals(UbisoftCloudPreference.NONE, pick(UbisoftCloudPreference.NONE, kept, "user", 1_000 + 10 * 60 * 1000))
        assertEquals(UbisoftCloudPreference.NONE, pick(UbisoftCloudPreference.NONE, null, "user", 2_000))
        assertEquals(UbisoftCloudPreference.REMOTE, pick(UbisoftCloudPreference.REMOTE, kept, "other", 2_000))
    }

    @Test fun localOnlySlotUploadsAtLaunch() {
        val mine = save("mine")
        writeLocal(2, mine)

        assertEquals(PullResult.Synced, pull())

        assertEquals(listOf("2"), transport.record!!.slots.map { it.filename })
        assertEquals(1, state()!!.revision)
    }

    @Test fun stateForAnotherUserIsIgnoredAndRevisionSurvivesTheRoundTrip() {
        synced(null, 1 to save("x"))
        assertNull(state()!!.revision)
        synced(7, 1 to save("x"))
        assertEquals(7, state()!!.revision)
        val other = UbisoftCloudSavesManager.Target(target.saveDir, target.stateDir, "someone else", 46, 1234)
        assertNull(UbisoftCloudSavesManager.loadState(other))
    }

    @Test fun mergeReplacesByNameAndAppendsNewSlots() {
        val a = UbisoftCloudSlot(1, "1", "a", 1, null)
        val b = UbisoftCloudSlot(2, "2", "b", 1, null)
        val odd = UbisoftCloudSlot(9, "profile", "p", 1, null)
        val a2 = a.copy(hash = "a2")
        val c = UbisoftCloudSlot(3, "3", "c", 1, null)
        assertEquals(listOf(a2, b, odd, c), UbisoftCloudSavesManager.mergeSlots(listOf(a, b, odd), listOf(a2, c)))
    }

    private fun assertNotNullState(): UbisoftCloudSavesManager.State = state().also { assertNotNull(it) }!!
}
