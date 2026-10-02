package app.gamenative.service.ea

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.gamenative.service.ea.EaCloudSavesManager.Md5Format
import app.gamenative.service.ea.EaCloudSavesManager.PullResult
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EaCloudSavesManagerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var gameDir: File
    private lateinit var saveDir: File
    private lateinit var container: Container

    private val target = EaCloudSaveTarget("offer", "1000_2000", listOf("%Documents%/Game/*"), emptyList())
    private val remote = HashMap<String, ByteArray>()
    private val events = ArrayList<String>()
    private var uploadedManifest: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EaCloudSavesManager.resetForTest()
        root = File(context.cacheDir, "ea-cloud-test-${System.nanoTime()}")
        gameDir = File(root, "game")
        File(gameDir, "__Installer").mkdirs()
        File(gameDir, "__Installer/installerdata.xml").writeText("<DiPManifest><contentIDs><contentID>71067</contentID></contentIDs></DiPManifest>")
        saveDir = File(root, ".wine/drive_c/users/${ImageFs.USER}/Documents/Game")
        saveDir.mkdirs()
        container = mockk()
        every { container.rootDir } returns root

        mockkObject(EaAuthManager, EaCloudSaveConfig, EaCloudSyncApi)
        every { EaAuthManager.isLoggedIn(any()) } returns true
        coEvery { EaCloudSaveConfig.resolve(any(), any()) } returns target
        coEvery { EaCloudSyncApi.acquire(any(), any(), any()) } answers {
            EaCloudLock(thirdArg(), "user", secondArg(), "lock-value", "https://example.invalid/manifest.xml")
        }
        coEvery { EaCloudSyncApi.release(any(), any()) } returns true
        coEvery { EaCloudSyncApi.authorizeDownloads(any(), any(), any()) } answers {
            thirdArg<List<String>>().associateWith { EaCloudAuthorizedUrl("https://example.invalid/get/$it", emptyList()) }
        }
        coEvery { EaCloudSyncApi.authorizeUploads(any(), any(), any()) } answers {
            thirdArg<List<EaCloudUpload>>().associate { it.resource to EaCloudAuthorizedUrl("https://example.invalid/put/${it.resource}", emptyList()) }
        }
        coEvery { EaCloudSyncApi.download(any(), any()) } answers {
            val href = firstArg<EaCloudAuthorizedUrl>().url.substringAfterLast('/')
            val bytes = remote[href] ?: throw EaCloudSyncException("EA cloud download HTTP 404")
            val temp = secondArg<File>()
            temp.parentFile?.mkdirs()
            temp.writeBytes(bytes)
            bytes.size.toLong() to md5(bytes)
        }
        coEvery { EaCloudSyncApi.uploadFile(any(), any()) } answers {
            events += "file:" + firstArg<EaCloudAuthorizedUrl>().url.substringAfterLast('/')
        }
        coEvery { EaCloudSyncApi.uploadBytes(any(), any()) } answers {
            uploadedManifest = String(secondArg<ByteArray>())
            events += "manifest"
        }
    }

    @After
    fun tearDown() {
        unmockkObject(EaAuthManager, EaCloudSaveConfig, EaCloudSyncApi)
        EaCloudSavesManager.resetForTest()
        root.deleteRecursively()
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(md5(bytes), Base64.NO_WRAP)

    private fun href(bytes: ByteArray): String = "${bytes.size}-${EaCrypto.hex(md5(bytes))}"

    private fun entry(name: String, bytes: ByteArray, md5: String? = base64(bytes)): EaCloudFile {
        remote[href(bytes)] = bytes
        return EaCloudFile(href(bytes), bytes.size.toLong(), md5, "%Documents%/Game/$name")
    }

    private fun manifests(vararg manifests: EaCloudManifest) {
        coEvery { EaCloudSyncApi.fetchManifest(any()) } returnsMany manifests.toList()
    }

    private fun cloud(vararg files: EaCloudFile) = EaCloudManifest(true, files.toList(), 1_700_000_000_000L)

    private val noCloud = EaCloudManifest(false, emptyList(), null)

    private fun pull(appId: Int, preference: EaCloudPreference = EaCloudPreference.NONE): PullResult =
        runBlocking { EaCloudSavesManager.syncBeforeLaunch(context, container, appId, gameDir, preference) }

    private fun push(appId: Int): Boolean = runBlocking { EaCloudSavesManager.syncAfterExit(context, container, appId) }

    private fun seedFormat(format: Md5Format) {
        File(root, ".ea_cloud").mkdirs()
        File(root, ".ea_cloud/state.json").writeText("{\"format\":\"${format.name}\",\"files\":{}}")
    }

    private fun backups(): List<File> = File(root, ".ea_cloud/backup").walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `first pull into empty local downloads and writes files`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray()), entry("sub\\b.sav", "bravo".toByteArray())))

        assertEquals(PullResult.Synced, pull(1))

        assertEquals("alpha", File(saveDir, "a.sav").readText())
        assertEquals("bravo", File(saveDir, "sub/b.sav").readText())
        assertTrue(EaCloudSavesManager.uploadAllowed(1))
        assertFalse(File(root, ".ea_cloud/tmp").exists())
        coVerify(exactly = 1) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `failed download leaves local untouched and returns Failed`() {
        File(saveDir, "a.sav").writeText("local")
        val missing = EaCloudFile("5-missing", 5, "x", "%Documents%/Game/b.sav")
        manifests(cloud(entry("a.sav", "alpha".toByteArray()), missing))

        val result = pull(2, EaCloudPreference.REMOTE)

        assertTrue(result is PullResult.Failed)
        assertEquals("local", File(saveDir, "a.sav").readText())
        assertFalse(File(saveDir, "b.sav").exists())
        assertTrue(backups().isEmpty())
        assertFalse(EaCloudSavesManager.hasSession(2))
        coVerify(exactly = 1) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `size mismatch fails the pull`() {
        val bytes = "alpha".toByteArray()
        remote[href(bytes)] = bytes
        manifests(cloud(EaCloudFile(href(bytes), 99, base64(bytes), "%Documents%/Game/a.sav")))

        assertTrue(pull(3) is PullResult.Failed)
        assertFalse(File(saveDir, "a.sav").exists())
    }

    @Test
    fun `overwrite creates a backup`() {
        File(saveDir, "a.sav").writeText("local")
        File(saveDir, "extra.sav").writeText("extra")
        manifests(cloud(entry("a.sav", "alpha".toByteArray())))

        assertEquals(PullResult.Synced, pull(4, EaCloudPreference.REMOTE))

        assertEquals("alpha", File(saveDir, "a.sav").readText())
        assertEquals("extra", File(saveDir, "extra.sav").readText())
        val saved = backups().associate { it.name to it.readText() }
        assertEquals(mapOf("a.sav" to "local"), saved)
        assertFalse(File(root, ".ea_cloud/apply.json").exists())
        assertTrue(backups().all { it.path.replace('\\', '/').endsWith("users/${ImageFs.USER}/Documents/Game/${it.name}") })
    }

    @Test
    fun `manifest missing with non-empty base fails without local change`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray())), noCloud)
        assertEquals(PullResult.Synced, pull(5))

        assertEquals(PullResult.Failed("manifest_missing"), pull(5))

        assertEquals("alpha", File(saveDir, "a.sav").readText())
        assertFalse(EaCloudSavesManager.hasSession(5))
        assertFalse(push(5))
        coVerify(exactly = 2) { EaCloudSyncApi.acquire(any(), any(), any()) }
        coVerify(exactly = 2) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `conflict returns Conflict without changes`() {
        val local = File(saveDir, "a.sav")
        local.writeText("local")
        manifests(cloud(entry("a.sav", "alpha".toByteArray())))

        val result = pull(6)

        assertEquals(PullResult.Conflict(local.lastModified(), 1_700_000_000_000L), result)
        assertEquals("local", local.readText())
        assertTrue(backups().isEmpty())
        assertFalse(EaCloudSavesManager.hasSession(6))
        coVerify(exactly = 0) { EaCloudSyncApi.download(any(), any()) }
        coVerify(exactly = 1) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `not signed in fails without network`() {
        every { EaAuthManager.isLoggedIn(any()) } returns false

        assertTrue(pull(7) is PullResult.Failed)
        coVerify(exactly = 0) { EaCloudSyncApi.acquire(any(), any(), any()) }
    }

    @Test
    fun `no cloud save target returns NoCloudSaves`() {
        coEvery { EaCloudSaveConfig.resolve(any(), any()) } returns null

        assertEquals(PullResult.NoCloudSaves, pull(8))
        assertFalse(EaCloudSavesManager.hasSession(8))
        coVerify(exactly = 0) { EaCloudSyncApi.acquire(any(), any(), any()) }
    }

    @Test
    fun `exit without session makes no api call`() {
        assertFalse(push(9))

        coVerify(exactly = 0) { EaCloudSyncApi.acquire(any(), any(), any()) }
        coVerify(exactly = 0) { EaCloudSyncApi.fetchManifest(any()) }
    }

    @Test
    fun `push uploads files before the manifest`() {
        val bytes = "local".toByteArray()
        File(saveDir, "a.sav").writeBytes(bytes)
        seedFormat(Md5Format.BASE64)
        manifests(noCloud, noCloud)
        assertEquals(PullResult.Synced, pull(10))

        assertTrue(push(10))

        assertEquals(listOf("file:${href(bytes)}", "manifest"), events)
        val files = EaCloudSyncApi.parseManifest(uploadedManifest!!)
        assertEquals(listOf(EaCloudFile(href(bytes), bytes.size.toLong(), base64(bytes), "%Documents%/Game/a.sav")), files)
        coVerify(exactly = 2) { EaCloudSyncApi.release(any(), any()) }
        assertFalse(EaCloudSavesManager.hasSession(10))
    }

    @Test
    fun `push after pull replaces changed entries and keeps the rest verbatim`() {
        val a = entry("A.sav", "alpha".toByteArray())
        val b = entry("b.sav", "bravo".toByteArray())
        val foreign = EaCloudFile("3-foreign", 3, "zzz", "%Unsupported%/x.bin")
        manifests(cloud(a, b, foreign), cloud(a, b, foreign), cloud())
        assertEquals(PullResult.Synced, pull(11))
        val changed = "changed".toByteArray()
        File(saveDir, "A.sav").writeBytes(changed)

        assertTrue(push(11))

        assertEquals(listOf("file:${href(changed)}", "manifest"), events)
        assertEquals(
            listOf(EaCloudFile(href(changed), changed.size.toLong(), base64(changed), "%Documents%/Game/A.sav"), b, foreign),
            EaCloudSyncApi.parseManifest(uploadedManifest!!),
        )
    }

    @Test
    fun `push skips the manifest when a file upload throws`() {
        File(saveDir, "a.sav").writeText("local")
        seedFormat(Md5Format.BASE64)
        manifests(noCloud, noCloud)
        assertEquals(PullResult.Synced, pull(12))
        coEvery { EaCloudSyncApi.uploadFile(any(), any()) } throws EaCloudSyncException("EA cloud upload HTTP 500")

        assertFalse(push(12))

        coVerify(exactly = 0) { EaCloudSyncApi.uploadBytes(any(), any()) }
        coVerify(exactly = 2) { EaCloudSyncApi.release(any(), any()) }
        assertFalse(EaCloudSavesManager.hasSession(12))
    }

    @Test
    fun `push is skipped when the cloud changed during the session`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray())), cloud(entry("a.sav", "other device".toByteArray())))
        assertEquals(PullResult.Synced, pull(13))
        File(saveDir, "a.sav").writeText("changed")

        assertFalse(push(13))

        coVerify(exactly = 0) { EaCloudSyncApi.uploadFile(any(), any()) }
        coVerify(exactly = 0) { EaCloudSyncApi.uploadBytes(any(), any()) }
        coVerify(exactly = 2) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `push without local changes uploads nothing`() {
        val a = entry("a.sav", "alpha".toByteArray())
        manifests(cloud(a), cloud(a))
        assertEquals(PullResult.Synced, pull(14))

        assertTrue(push(14))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `upload is not allowed when the md5 format is unrecognised`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray(), md5 = "not-a-known-form")))

        assertEquals(PullResult.Synced, pull(15))

        assertEquals("alpha", File(saveDir, "a.sav").readText())
        assertFalse(EaCloudSavesManager.uploadAllowed(15))
        File(saveDir, "a.sav").writeText("changed")
        assertFalse(push(15))
        coVerify(exactly = 1) { EaCloudSyncApi.acquire(any(), any(), any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `keep local after a conflict uploads with the format saved in state`() {
        val theirs = entry("a.sav", "other device".toByteArray(), md5 = null)
        manifests(cloud(entry("a.sav", "alpha".toByteArray(), md5 = EaCrypto.hex(md5("alpha".toByteArray())))), cloud(theirs), cloud(theirs))
        assertEquals(PullResult.Synced, pull(17))
        assertTrue(File(root, ".ea_cloud/state.json").isFile)
        val mine = "mine".toByteArray()
        File(saveDir, "a.sav").writeBytes(mine)
        assertTrue(pull(17) is PullResult.Conflict)

        assertEquals(PullResult.Synced, pull(17, EaCloudPreference.LOCAL))
        assertTrue(EaCloudSavesManager.uploadAllowed(17))
        assertTrue(push(17))

        assertEquals(
            listOf(EaCloudFile(href(mine), mine.size.toLong(), EaCrypto.hex(md5(mine)), "%Documents%/Game/a.sav")),
            EaCloudSyncApi.parseManifest(uploadedManifest!!),
        )
    }

    @Test
    fun `keep local without state uploads with the format read from the cloud md5 shape`() {
        val theirs = entry("a.sav", "alpha".toByteArray(), md5 = EaCloudSavesManager.encodeMd5(Md5Format.MAXIMA, md5("alpha".toByteArray())))
        manifests(cloud(theirs), cloud(theirs))
        val mine = "mine".toByteArray()
        File(saveDir, "a.sav").writeBytes(mine)

        assertEquals(PullResult.Synced, pull(18, EaCloudPreference.LOCAL))
        assertTrue(EaCloudSavesManager.uploadAllowed(18))
        assertTrue(push(18))

        assertEquals(
            EaCloudSavesManager.encodeMd5(Md5Format.MAXIMA, md5(mine)),
            EaCloudSyncApi.parseManifest(uploadedManifest!!).single().md5,
        )
    }

    @Test
    fun `keep local with mixed cloud md5 shapes does not allow upload`() {
        val a = entry("a.sav", "alpha".toByteArray())
        val b = entry("b.sav", "bravo".toByteArray(), md5 = EaCrypto.hex(md5("bravo".toByteArray())))
        manifests(cloud(a, b))
        File(saveDir, "a.sav").writeText("mine")
        File(saveDir, "b.sav").writeText("mine too")

        assertEquals(PullResult.Synced, pull(19, EaCloudPreference.LOCAL))

        assertFalse(EaCloudSavesManager.uploadAllowed(19))
        assertFalse(push(19))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `md5 shape recognition needs all present attrs to agree`() {
        val hex = "0cc175b9c0f1b6a831c399e269772661"
        val base64 = "DMF1ucDxtqgxw5niaXcmYQ=="
        assertEquals(Md5Format.HEX, EaCloudSavesManager.guessFormat(listOf(hex, null, hex)))
        assertEquals(Md5Format.HEX_UPPER, EaCloudSavesManager.guessFormat(listOf(hex.uppercase())))
        assertEquals(Md5Format.BASE64, EaCloudSavesManager.guessFormat(listOf(base64)))
        assertEquals(Md5Format.MAXIMA, EaCloudSavesManager.guessFormat(listOf("1234567890123456789=====")))
        assertEquals(Md5Format.MAXIMA, EaCloudSavesManager.guessFormat(listOf("129446785131422334087771068112855286028")))
        assertNull(EaCloudSavesManager.guessFormat(listOf(hex, base64)))
        assertNull(EaCloudSavesManager.guessFormat(listOf(hex, "garbage")))
        assertNull(EaCloudSavesManager.guessFormat(listOf("12345====")))
        assertNull(EaCloudSavesManager.guessFormat(listOf(null)))
        assertNull(EaCloudSavesManager.guessFormat(emptyList()))
    }

    @Test
    fun `an all digit 32 character md5 is ambiguous by shape`() {
        val digits = "12345678901234567890123456789012"
        val hex = "0cc175b9c0f1b6a831c399e269772661"
        assertNull(EaCloudSavesManager.guessFormat(listOf(digits)))
        assertNull(EaCloudSavesManager.guessFormat(listOf(digits, digits)))
        assertNull(EaCloudSavesManager.guessFormat(listOf(hex, digits)))
        assertNull(EaCloudSavesManager.guessFormat(listOf(hex.uppercase(), digits)))
        assertEquals(Md5Format.MAXIMA, EaCloudSavesManager.guessFormat(listOf("1234567890123456789012345678901")))
        assertEquals(Md5Format.MAXIMA, EaCloudSavesManager.guessFormat(listOf("123456789012345678901234567890123")))
        val digest = ByteArray(16) { 0x11 }
        assertEquals(Md5Format.HEX, EaCloudSavesManager.recognise(EaCrypto.hex(digest), digest))
    }

    @Test
    fun `cloud names that differ only by case are not pulled and block the push`() {
        val one = entry("a.sav", "cloud one".toByteArray())
        val two = entry("A.SAV", "cloud two".toByteArray())
        manifests(cloud(one, two))
        File(saveDir, "a.sav").writeText("local")

        assertEquals(PullResult.Synced, pull(42, EaCloudPreference.REMOTE))

        assertEquals("local", File(saveDir, "a.sav").readText())
        assertEquals(listOf("a.sav"), saveDir.list()!!.toList())
        assertTrue(backups().isEmpty())
        coVerify(exactly = 0) { EaCloudSyncApi.download(any(), any()) }
        assertFalse(EaCloudSavesManager.uploadAllowed(42))
    }

    @Test
    fun `a synced save is kept when its cloud name becomes ambiguous`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray())))
        assertEquals(PullResult.Synced, pull(43))
        assertEquals("alpha", File(saveDir, "a.sav").readText())
        val one = entry("A.sav", "one".toByteArray())
        val two = EaCloudFile(href("two".toByteArray()), 3, base64("two".toByteArray()), "%Documents%/Game/a.SAV")
        remote[two.href] = "two".toByteArray()
        manifests(cloud(one, two), cloud(one, two))

        assertEquals(PullResult.Synced, pull(43))

        assertEquals("alpha", File(saveDir, "a.sav").readText())
        assertTrue(backups().isEmpty())
        assertFalse(EaCloudSavesManager.uploadAllowed(43))
        assertFalse(push(43))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `push does not publish the manifest when a save is rewritten with the same size during upload`() {
        val save = File(saveDir, "a.sav")
        save.writeText("local")
        seedFormat(Md5Format.BASE64)
        manifests(noCloud, noCloud)
        assertEquals(PullResult.Synced, pull(44))
        coEvery { EaCloudSyncApi.uploadFile(any(), any()) } answers {
            val modified = save.lastModified()
            save.writeText("LOCAL")
            save.setLastModified(modified)
            events += "file"
        }

        assertFalse(push(44))

        assertEquals(listOf("file"), events)
        assertNull(uploadedManifest)
        coVerify(exactly = 0) { EaCloudSyncApi.uploadBytes(any(), any()) }
    }

    @Test
    fun `local save uploads to an empty slot in the maxima format`() {
        File(saveDir, "a.sav").writeText("local")
        manifests(noCloud)

        assertEquals(PullResult.Synced, pull(20))

        assertTrue(EaCloudSavesManager.uploadAllowed(20))
        assertTrue(push(20))
        val mine = "local".toByteArray()
        assertEquals(listOf("file:${href(mine)}", "manifest"), events)
        assertEquals(
            listOf(EaCloudFile(href(mine), mine.size.toLong(), EaCloudSavesManager.encodeMd5(EaCloudSavesManager.Md5Format.MAXIMA, md5(mine)), "%Documents%/Game/a.sav")),
            EaCloudSyncApi.parseManifest(uploadedManifest!!),
        )
    }

    @Test
    fun `a renamed local save replaces its old cloud entry`() {
        File(saveDir, "a.sav").writeText("local")
        manifests(noCloud)
        assertEquals(PullResult.Synced, pull(40))
        assertTrue(push(40))
        val first = EaCloudSyncApi.parseManifest(uploadedManifest!!)

        File(saveDir, "a.sav").renameTo(File(saveDir, "b.sav"))
        events.clear()
        manifests(cloud(*first.toTypedArray()))
        assertEquals(PullResult.Synced, pull(40))
        assertTrue(push(40))

        val mine = "local".toByteArray()
        assertEquals(listOf("file:${href(mine)}", "manifest"), events)
        assertEquals(listOf("%Documents%/Game/b.sav"), EaCloudSyncApi.parseManifest(uploadedManifest!!).map { it.localName })
    }

    @Test
    fun `a cloud entry under a local file is ignored and kept`() {
        val mine = "local".toByteArray()
        File(saveDir, "a.sav").writeBytes(mine)
        seedFormat(Md5Format.BASE64)
        val nested = EaCloudFile(href(mine), mine.size.toLong(), base64(mine), "%Documents%/Game/a.sav/a.sav")
        remote[href(mine)] = mine
        manifests(cloud(nested))

        assertEquals(PullResult.Synced, pull(41))
        assertEquals("local", File(saveDir, "a.sav").readText())
        assertTrue(push(41))

        assertEquals(
            listOf("%Documents%/Game/a.sav/a.sav", "%Documents%/Game/a.sav"),
            EaCloudSyncApi.parseManifest(uploadedManifest!!).map { it.localName },
        )
    }

    @Test
    fun `keep local applies only to conflict names`() {
        val clash = entry("clash.sav", "cloud clash".toByteArray())
        val fresh = entry("fresh.sav", "fresh".toByteArray())
        manifests(cloud(clash, fresh))
        File(saveDir, "clash.sav").writeText("local clash")

        assertEquals(PullResult.Synced, pull(21, EaCloudPreference.LOCAL))
        assertEquals("local clash", File(saveDir, "clash.sav").readText())
        assertEquals("fresh", File(saveDir, "fresh.sav").readText())
        assertTrue(push(21))

        val mine = "local clash".toByteArray()
        assertEquals(listOf("file:${href(mine)}", "manifest"), events)
        assertEquals(
            listOf(EaCloudFile(href(mine), mine.size.toLong(), base64(mine), "%Documents%/Game/clash.sav"), fresh),
            EaCloudSyncApi.parseManifest(uploadedManifest!!),
        )
    }

    @Test
    fun `conflict choice is remembered until exit`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray())))
        File(saveDir, "a.sav").writeText("local")
        coEvery { EaCloudSyncApi.uploadFile(any(), any()) } throws EaCloudSyncException("EA cloud upload HTTP 500")

        assertTrue(pull(22) is PullResult.Conflict)
        assertEquals(PullResult.Synced, pull(22, EaCloudPreference.LOCAL))
        assertEquals(PullResult.Synced, pull(22))
        assertFalse(push(22))
        assertTrue(pull(22) is PullResult.Conflict)
        assertEquals("local", File(saveDir, "a.sav").readText())
    }

    @Test
    fun `pull that would remove most local saves is refused`() {
        val files = listOf("a.sav", "b.sav", "c.sav").map { entry(it, it.toByteArray()) }
        manifests(cloud(*files.toTypedArray()), cloud())
        assertEquals(PullResult.Synced, pull(23))

        assertEquals(PullResult.Failed("mass_delete_guard"), pull(23))

        assertEquals(3, saveDir.listFiles()!!.size)
        assertTrue(backups().isEmpty())
        assertFalse(EaCloudSavesManager.hasSession(23))
    }

    @Test
    fun `leftover apply journal is rolled back before anything else`() {
        val placed = File(saveDir, "a.sav").apply { writeText("half applied") }
        val added = File(saveDir, "b.sav").apply { writeText("new") }
        val backup = File(root, ".ea_cloud/backup/1/a.sav").apply { parentFile!!.mkdirs(); writeText("original") }
        val journal = File(root, ".ea_cloud/apply.json")
        journal.writeText(
            org.json.JSONObject().put(
                "ops",
                org.json.JSONArray()
                    .put(org.json.JSONObject().put("target", placed.path).put("backup", backup.path).put("placed", true))
                    .put(org.json.JSONObject().put("target", added.path).put("placed", true)),
            ).toString(),
        )

        assertFalse(push(24))

        assertEquals("original", placed.readText())
        assertFalse(added.exists())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
        coVerify(exactly = 0) { EaCloudSyncApi.acquire(any(), any(), any()) }
    }

    @Test
    fun `journal rollback keeps a placed save the game rewrote`() {
        val unchanged = File(saveDir, "a.sav").apply { writeText("placed") }
        val rewritten = File(saveDir, "b.sav").apply { writeText("rewritten by the game") }
        val added = File(saveDir, "c.sav").apply { writeText("rewritten too") }
        val backupA = File(root, ".ea_cloud/backup/1/a.sav").apply { parentFile!!.mkdirs(); writeText("original a") }
        val backupB = File(root, ".ea_cloud/backup/1/b.sav").apply { writeText("original b") }
        fun op(target: File, backup: File?, size: Long, modified: Long) = org.json.JSONObject()
            .put("target", target.path).put("placed", true).put("size", size).put("modified", modified)
            .also { if (backup != null) it.put("backup", backup.path) }
        val journal = File(root, ".ea_cloud/apply.json")
        journal.writeText(
            org.json.JSONObject().put(
                "ops",
                org.json.JSONArray()
                    .put(op(unchanged, backupA, unchanged.length(), unchanged.lastModified()))
                    .put(op(rewritten, backupB, 6, rewritten.lastModified()))
                    .put(op(added, null, 6, added.lastModified())),
            ).toString(),
        )

        assertFalse(push(30))

        assertEquals("original a", unchanged.readText())
        assertEquals("rewritten by the game", rewritten.readText())
        assertEquals("original b", backupB.readText())
        assertEquals("rewritten too", added.readText())
        assertFalse(journal.exists())
    }

    @Test
    fun `unreadable apply journal blocks the sync`() {
        every { EaAuthManager.isLoggedIn(any()) } returns false
        File(root, ".ea_cloud").mkdirs()
        File(root, ".ea_cloud/apply.json").writeText("not json")

        assertEquals(PullResult.Failed("journal_rollback"), pull(25))

        coVerify(exactly = 0) { EaCloudSyncApi.acquire(any(), any(), any()) }
    }

    @Test
    fun `download that does not match the md5 in the known format fails`() {
        seedFormat(Md5Format.BASE64)
        manifests(cloud(entry("a.sav", "alpha".toByteArray(), md5 = base64("other".toByteArray()))))

        assertTrue(pull(26) is PullResult.Failed)

        assertFalse(File(saveDir, "a.sav").exists())
        assertFalse(EaCloudSavesManager.hasSession(26))
    }

    @Test
    fun `pull past the lock time limit applies nothing`() {
        manifests(cloud(entry("a.sav", "alpha".toByteArray())))
        EaCloudSavesManager.lockLimitMs = -1

        assertEquals(PullResult.Failed("lock_timeout"), pull(27))

        assertFalse(File(saveDir, "a.sav").exists())
        coVerify(exactly = 1) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `push past the lock time limit does not upload the manifest`() {
        File(saveDir, "a.sav").writeText("local")
        seedFormat(Md5Format.BASE64)
        manifests(noCloud)
        assertEquals(PullResult.Synced, pull(28))
        EaCloudSavesManager.lockLimitMs = -1

        assertFalse(push(28))

        assertEquals(1, events.size)
        assertNull(uploadedManifest)
    }

    @Test
    fun `backups keep the five newest and anything younger than a week`() {
        val backupRoot = File(root, ".ea_cloud/backup")
        val now = System.currentTimeMillis()
        val young = (1..3).map { File(backupRoot, (now - it * 60_000L).toString()) }
        val old = (1..4).map { File(backupRoot, (it * 1000L).toString()) }
        (young + old).forEach { File(it, "x").apply { parentFile!!.mkdirs(); writeText("x") } }
        File(saveDir, "a.sav").writeText("local")
        manifests(cloud(entry("a.sav", "alpha".toByteArray())))

        assertEquals(PullResult.Synced, pull(29, EaCloudPreference.REMOTE))

        assertTrue(young.all { it.isDirectory })
        assertEquals(listOf(false, false, false, true), old.map { it.isDirectory })
        assertEquals(5, backupRoot.listFiles()!!.size)
    }

    @Test
    fun `pull releases the lock when the manifest fetch throws`() {
        coEvery { EaCloudSyncApi.fetchManifest(any()) } throws EaCloudSyncException("EA cloud manifest HTTP 500")

        assertEquals(PullResult.Failed("EA cloud manifest HTTP 500"), pull(16))

        coVerify(exactly = 1) { EaCloudSyncApi.release(any(), any()) }
    }

    @Test
    fun `md5 format recognition covers hex base64 and maxima forms`() {
        val digest = md5("alpha".toByteArray())
        val hex = EaCrypto.hex(digest)
        assertEquals(Md5Format.HEX, EaCloudSavesManager.recognise(hex, digest))
        assertEquals(Md5Format.HEX_UPPER, EaCloudSavesManager.recognise(hex.uppercase(), digest))
        assertEquals(Md5Format.BASE64, EaCloudSavesManager.recognise(Base64.encodeToString(digest, Base64.NO_WRAP), digest))
        assertEquals(Md5Format.MAXIMA, EaCloudSavesManager.recognise(EaCloudSavesManager.encodeMd5(Md5Format.MAXIMA, digest), digest))
        assertNull(EaCloudSavesManager.recognise("nope", digest))

        val one = ByteArray(16).also { it[0] = 1 }
        assertEquals("1" + "=".repeat(23), EaCloudSavesManager.encodeMd5(Md5Format.MAXIMA, one))
        val high = ByteArray(16).also { it[1] = 1 }
        assertEquals("256" + "=".repeat(21), EaCloudSavesManager.encodeMd5(Md5Format.MAXIMA, high))
        for (format in Md5Format.entries) {
            assertEquals(format, EaCloudSavesManager.recognise(EaCloudSavesManager.encodeMd5(format, digest), digest))
        }
    }

    @Test
    fun `content match falls back to the href when md5 is absent`() {
        val bytes = "alpha".toByteArray()
        val digest = md5(bytes)
        assertTrue(EaCloudSavesManager.matches(EaCloudFile(href(bytes), 5, null, "n"), 5, digest))
        assertFalse(EaCloudSavesManager.matches(EaCloudFile("other", 5, null, "n"), 5, digest))
        assertFalse(EaCloudSavesManager.matches(EaCloudFile(href(bytes), 6, base64(bytes), "n"), 5, digest))
        assertTrue(EaCloudSavesManager.matches(EaCloudFile("other", 5, EaCrypto.hex(digest).uppercase(), "n"), 5, digest))
        assertArrayEquals(digest, md5(bytes))
    }
}
