package app.gamenative.service.ea

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.winlator.xenvironment.ImageFs
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class EaCloudSaveConfigTest {
    private lateinit var context: android.content.Context
    private lateinit var driveC: File
    private lateinit var documents: File
    private lateinit var server: MockWebServer

    private val sims = EaCloudSaveTarget(
        offerId = "OFB-EAST:109552677",
        cloudId = "186019_1014885",
        includes = listOf("%Documents%/Electronic Arts/The Sims 4/saves/*", "%SavedGames%\\Respawn\\*.sav"),
        excludes = listOf("%Documents%/Electronic Arts/The Sims 4/saves/scratch/*"),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "ea_cloud").deleteRecursively()
        driveC = Files.createTempDirectory("ea_cloud_drive_c").toFile()
        documents = File(driveC, "users/${ImageFs.USER}/Documents")
        server = MockWebServer()
        server.start()
        mockkObject(EaAuthManager)
        coEvery { EaAuthManager.accessToken(any()) } returns "token"
        EaCloudSaveConfig.endpoint = server.url("/graphql").toString()
        EaCloudSaveConfig.cacheMaxAgeMs = 24 * 60 * 60 * 1000L
    }

    @After
    fun tearDown() {
        EaCloudSaveConfig.endpoint = EaConstants.SERVICE_AGGREGATION_ENDPOINT
        EaCloudSaveConfig.cacheMaxAgeMs = 24 * 60 * 60 * 1000L
        unmockkObject(EaAuthManager)
        server.shutdown()
        driveC.deleteRecursively()
    }

    private fun write(root: File, path: String, text: String = "x"): File =
        File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    private fun offersJson(vararg offers: String) = "{\"data\":{\"legacyOffers\":[${offers.joinToString(",")}]}}"

    private fun offerJson(offerId: String, contentId: String, multiplayerId: String?, config: String?) = JSONObject()
        .put("offerId", offerId)
        .put("contentId", contentId)
        .put("primaryMasterTitleId", "186019")
        .put("multiplayerId", multiplayerId ?: JSONObject.NULL)
        .put("cloudSaveConfigurationOverride", config ?: JSONObject.NULL)
        .toString()

    private fun ownedJson(vararg offerIds: String) =
        "{\"data\":{\"me\":{\"ownedGameProducts\":{\"next\":null,\"totalCount\":${offerIds.size}," +
        "\"items\":[${offerIds.joinToString(",") { "{\"originOfferId\":\"$it\"}" }}]}}}}"

    private val criteria = "<saveFileCriteria><include order=\"0\">%Documents%/Electronic Arts/The Sims 4/saves/*</include>" +
        "</saveFileCriteria>"

    @Test
    fun `criteria parse orders entries and unescapes text`() {
        val (includes, excludes) = EaCloudSaveConfig.parseCriteria(
            "<saveFileCriteria>" +
                "<include order=\"1\">%SavedGames%\\Tom &amp; Jerry\\*.sav</include>" +
                "<exclude order=\"2\">%Documents%/EA/&lt;tmp&gt;/*</exclude>" +
                "<include order=\"0\">%Documents%/EA/saves/*</include>" +
                "</saveFileCriteria>",
        )
        assertEquals(listOf("%Documents%/EA/saves/*", "%SavedGames%\\Tom & Jerry\\*.sav"), includes)
        assertEquals(listOf("%Documents%/EA/<tmp>/*"), excludes)
    }

    @Test
    fun `criteria parse accepts a fully escaped document`() {
        val (includes, excludes) = EaCloudSaveConfig.parseCriteria(
            "&lt;saveFileCriteria&gt;&lt;include order=&quot;0&quot;&gt;%Documents%/EA/*&lt;/include&gt;&lt;/saveFileCriteria&gt;",
        )
        assertEquals(listOf("%Documents%/EA/*"), includes)
        assertTrue(excludes.isEmpty())
    }

    @Test
    fun `trailing star matches the whole tree and keeps empty files`() {
        write(documents, "Electronic Arts/The Sims 4/saves/Slot_00000001.save")
        write(documents, "Electronic Arts/The Sims 4/saves/backup/deep/Slot_00000002.save")
        write(documents, "Electronic Arts/The Sims 4/saves/empty.save", "")
        write(documents, "Electronic Arts/The Sims 4/Options.ini")
        File(documents, "Electronic Arts/The Sims 4/saves/emptydir").mkdirs()

        val files = EaCloudSaveConfig.localFiles(sims, driveC)

        assertEquals(
            setOf(
                "%Documents%/Electronic Arts/The Sims 4/saves/Slot_00000001.save",
                "%Documents%/Electronic Arts/The Sims 4/saves/backup/deep/Slot_00000002.save",
                "%Documents%/Electronic Arts/The Sims 4/saves/empty.save",
            ),
            files.keys,
        )
        assertTrue(files.values.all { it.isFile })
    }

    @Test
    fun `trailing separator matches the whole folder`() {
        val nfs = EaCloudSaveTarget(
            "OFB-EAST:46851",
            "185235_71530",
            listOf("%Documents%/Criterion Games/Need For Speed(TM) Most Wanted/Save/"),
            emptyList(),
        )
        write(documents, "Criterion Games/Need For Speed(TM) Most Wanted/Save/1000933177888/MUD.29.NFS13Save")
        write(documents, "Criterion Games/Need For Speed(TM) Most Wanted/config.NFS13Save")

        val files = EaCloudSaveConfig.localFiles(nfs, driveC)

        assertEquals(setOf("%Documents%/Criterion Games/Need For Speed(TM) Most Wanted/Save/1000933177888/MUD.29.NFS13Save"), files.keys)
        assertTrue(
            EaCloudSaveConfig.isAllowed(
                nfs,
                "%Documents%\\Criterion Games\\Need For Speed(TM) Most Wanted\\Save\\1000933177888\\MUD.29.NFS13Save",
            ),
        )
    }

    @Test
    fun `directories match ignoring case and keys keep the names on disk`() {
        write(documents, "electronic arts/the sims 4/Saves/Slot.save")

        val files = EaCloudSaveConfig.localFiles(sims, driveC)

        assertEquals(1, files.size)
        assertEquals(
            EaCloudSaveConfig.normalizeName("%Documents%/Electronic Arts/The Sims 4/saves/Slot.save"),
            EaCloudSaveConfig.normalizeName(files.keys.single()),
        )
        assertEquals("Slot.save", files.values.single().name)
    }

    @Test
    fun `backslash patterns match and produce backslash names without recursing`() {
        val savedGames = File(driveC, "users/${ImageFs.USER}/Saved Games")
        val save = write(savedGames, "Respawn/profile.SAV")
        write(savedGames, "Respawn/notes.txt")
        write(savedGames, "Respawn/nested/other.sav")

        val files = EaCloudSaveConfig.localFiles(sims, driveC)

        assertEquals(setOf("%SavedGames%\\Respawn\\profile.SAV"), files.keys)
        assertEquals("%SavedGames%\\Respawn\\profile.SAV", EaCloudSaveConfig.toLocalName(sims, save, driveC))
    }

    @Test
    fun `toLocalName uses the separator of the matching include`() {
        val save = write(documents, "Electronic Arts/The Sims 4/saves/Slot.save")
        val other = write(documents, "Electronic Arts/The Sims 4/Options.ini")

        assertEquals("%Documents%/Electronic Arts/The Sims 4/saves/Slot.save", EaCloudSaveConfig.toLocalName(sims, save, driveC))
        assertNull(EaCloudSaveConfig.toLocalName(sims, other, driveC))
    }

    @Test
    fun `exclude wins over include`() {
        write(documents, "Electronic Arts/The Sims 4/saves/Slot.save")
        write(documents, "Electronic Arts/The Sims 4/saves/Scratch/tmp.save")

        val files = EaCloudSaveConfig.localFiles(sims, driveC)

        assertEquals(setOf("%Documents%/Electronic Arts/The Sims 4/saves/Slot.save"), files.keys)
        assertFalse(EaCloudSaveConfig.isAllowed(sims, "%Documents%\\Electronic Arts\\The Sims 4\\saves\\scratch\\tmp.save"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        val target = sims.copy(includes = listOf("%LocalAppData%/Game/slot?.dat"), excludes = emptyList())
        val local = File(driveC, "users/${ImageFs.USER}/AppData/Local")
        write(local, "Game/slot1.dat")
        write(local, "Game/slot10.dat")

        assertEquals(setOf("%LocalAppData%/Game/slot1.dat"), EaCloudSaveConfig.localFiles(target, driveC).keys)
    }

    @Test
    fun `unknown tokens in patterns are skipped`() {
        val target = sims.copy(includes = listOf("%InstallDir%/saves/*", "%ProgramData%/Game/*"), excludes = emptyList())
        write(File(driveC, "ProgramData"), "Game/a.bin")

        assertEquals(setOf("%ProgramData%/Game/a.bin"), EaCloudSaveConfig.localFiles(target, driveC).keys)
    }

    @Test
    fun `isAllowed accepts either separator and any case`() {
        assertTrue(EaCloudSaveConfig.isAllowed(sims, "%Documents%\\Electronic Arts\\The Sims 4\\saves\\Slot.save"))
        assertTrue(EaCloudSaveConfig.isAllowed(sims, "%documents%/electronic arts/the sims 4/SAVES/sub/Slot.save"))
        assertTrue(EaCloudSaveConfig.isAllowed(sims, "%SavedGames%/Respawn/profile.sav"))
        assertFalse(EaCloudSaveConfig.isAllowed(sims, "%SavedGames%/Respawn/nested/profile.sav"))
        assertFalse(EaCloudSaveConfig.isAllowed(sims, "%Documents%/Electronic Arts/The Sims 4/Options.ini"))
        assertFalse(EaCloudSaveConfig.isAllowed(sims, "%Documents%/Electronic Arts/The Sims 4/saves/../../x"))
        assertFalse(EaCloudSaveConfig.isAllowed(sims, "%AppData%/Electronic Arts/The Sims 4/saves/Slot.save"))
    }

    @Test
    fun `toFile rejects traversal, absolute paths and unknown tokens`() {
        assertNull(EaCloudSaveConfig.toFile("%Documents%/../../../etc/passwd", driveC))
        assertNull(EaCloudSaveConfig.toFile("%Documents%\\EA\\..\\..\\x.save", driveC))
        assertNull(EaCloudSaveConfig.toFile("/data/data/app.gamenative/files/x", driveC))
        assertNull(EaCloudSaveConfig.toFile("C:\\Windows\\system32\\x.dll", driveC))
        assertNull(EaCloudSaveConfig.toFile("%Documents%/C:/x.save", driveC))
        assertNull(EaCloudSaveConfig.toFile("%InstallDir%/saves/x.save", driveC))
        assertNull(EaCloudSaveConfig.toFile("%Documents%", driveC))
        assertNull(EaCloudSaveConfig.toFile("Electronic Arts/saves/x.save", driveC))
    }

    @Test
    fun `toFile maps both separators into the prefix`() {
        val expected = File(documents, "Electronic Arts/The Sims 4/saves/Slot.save").canonicalPath

        assertEquals(
            expected,
            EaCloudSaveConfig.toFile("%Documents%\\Electronic Arts\\The Sims 4\\saves\\Slot.save", driveC)?.canonicalPath,
        )
        assertEquals(expected, EaCloudSaveConfig.toFile("%Documents%/Electronic Arts/The Sims 4/saves/Slot.save", driveC)?.canonicalPath)
        assertEquals(
            File(driveC, "users/${ImageFs.USER}/AppData/Roaming/Game/a.bin").canonicalPath,
            EaCloudSaveConfig.toFile("%AppData%/Game/a.bin", driveC)?.canonicalPath,
        )
    }

    @Test
    fun `folders that resolve outside drive c are skipped`() {
        val outside = Files.createTempDirectory("ea_cloud_outside").toFile()
        try {
            write(outside, "Electronic Arts/The Sims 4/saves/Slot.save")
            documents.parentFile!!.mkdirs()
            Files.createSymbolicLink(documents.toPath(), outside.toPath())
            val savedGames = File(driveC, "users/${ImageFs.USER}/Saved Games")
            write(savedGames, "Respawn/profile.sav")

            val files = EaCloudSaveConfig.localFiles(sims, driveC)

            assertEquals(setOf("%SavedGames%\\Respawn\\profile.sav"), files.keys)
            assertNull(EaCloudSaveConfig.toFile("%Documents%/Electronic Arts/The Sims 4/saves/Slot.save", driveC))
            assertNull(EaCloudSaveConfig.toFile("%Documents%/Electronic Arts/The Sims 4/saves/New.save", driveC))
            assertNull(EaCloudSaveConfig.toLocalName(sims, File(documents, "Electronic Arts/The Sims 4/saves/Slot.save"), driveC))
            assertNotNull(EaCloudSaveConfig.toFile("%SavedGames%\\Respawn\\profile.sav", driveC))
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `toFile reuses an existing directory with different case`() {
        File(documents, "Electronic Arts/The Sims 4/Saves").mkdirs()

        val file = EaCloudSaveConfig.toFile("%Documents%\\electronic arts\\the sims 4\\saves\\Slot1.save", driveC)

        assertNotNull(file)
        assertEquals("Saves", file!!.parentFile!!.name)
        assertEquals("The Sims 4", file.parentFile!!.parentFile!!.name)
        assertEquals("Slot1.save", file.name)
    }

    @Test
    fun `normalizeName folds case and separators`() {
        assertEquals(
            "%documents%/electronic arts/the sims 4/saves/slot.save",
            EaCloudSaveConfig.normalizeName("%Documents%\\Electronic Arts\\\\The Sims 4//saves\\Slot.save"),
        )
    }

    @Test
    fun `offer selection prefers the earliest content id`() {
        val offers = EaCloudSaveConfig.parseOffers(
            JSONObject(
                offersJson(
                    offerJson("OFB-DLC", "71111", null, null),
                    offerJson("OFB-BASE", "70000", "1014885", criteria),
                    offerJson("OFB-OTHER", "99999", "5", criteria),
                ),
            ).getJSONObject("data"),
        )

        val offer = EaCloudSaveConfig.selectOffer(offers, listOf("70000", "71111"))

        assertEquals("OFB-BASE", offer?.offerId)
        assertNull(EaCloudSaveConfig.selectOffer(offers, listOf("12345")))
        assertEquals(
            EaCloudSaveTarget("OFB-BASE", "186019_1014885", listOf("%Documents%/Electronic Arts/The Sims 4/saves/*"), emptyList()),
            EaCloudSaveConfig.targetOf(offer!!),
        )
        assertNull(EaCloudSaveConfig.targetOf(offers.first()))
    }

    @Test
    fun `resolve queries the catalog once then serves the cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(ownedJson("OFB-DLC", "OFB-BASE")))
        server.enqueue(
            MockResponse().setBody(
                offersJson(offerJson("OFB-DLC", "71111", null, null), offerJson("OFB-BASE", "70000", "1014885", criteria)),
            ),
        )

        val target = EaCloudSaveConfig.resolve(context, listOf("70000", "71111"))

        assertEquals("186019_1014885", target?.cloudId)
        assertEquals("OFB-BASE", target?.offerId)
        val owned = server.takeRequest()
        assertEquals("Bearer token", owned.getHeader("Authorization"))
        assertEquals("getPreloadedOwnedGames", JSONObject(owned.body.readUtf8()).getString("operationName"))
        val defs = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("getLegacyCatalogDefs", defs.getString("operationName"))
        assertEquals(2, defs.getJSONObject("variables").getJSONArray("offerIds").length())

        assertEquals(target, EaCloudSaveConfig.resolve(context, listOf("70000", "71111")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `resolve falls back to the cache when the catalog call fails`() = runBlocking {
        server.enqueue(MockResponse().setBody(ownedJson("OFB-BASE")))
        server.enqueue(MockResponse().setBody(offersJson(offerJson("OFB-BASE", "70000", "1014885", criteria))))
        val target = EaCloudSaveConfig.resolve(context, listOf("70000"))

        EaCloudSaveConfig.cacheMaxAgeMs = 0
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(target, EaCloudSaveConfig.resolve(context, listOf("70000")))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `resolve drops the stale cache when the account no longer owns the game`() = runBlocking {
        server.enqueue(MockResponse().setBody(ownedJson("OFB-BASE")))
        server.enqueue(MockResponse().setBody(offersJson(offerJson("OFB-BASE", "70000", "1014885", criteria))))
        assertNotNull(EaCloudSaveConfig.resolve(context, listOf("70000")))

        EaCloudSaveConfig.cacheMaxAgeMs = 0
        server.enqueue(MockResponse().setBody(ownedJson("OFB-OTHER")))
        server.enqueue(MockResponse().setBody(offersJson(offerJson("OFB-OTHER", "99999", "5", criteria))))
        assertNull(EaCloudSaveConfig.resolve(context, listOf("70000")))

        server.enqueue(MockResponse().setResponseCode(500))
        try {
            EaCloudSaveConfig.resolve(context, listOf("70000"))
            fail("expected the lookup failure to propagate without a cache entry")
        } catch (e: IllegalStateException) {
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun `resolve returns null for a game without cloud saves and remembers it`() = runBlocking {
        server.enqueue(MockResponse().setBody(ownedJson("OFB-BASE")))
        server.enqueue(MockResponse().setBody(offersJson(offerJson("OFB-BASE", "70000", null, criteria))))

        assertNull(EaCloudSaveConfig.resolve(context, listOf("70000")))
        assertNull(EaCloudSaveConfig.resolve(context, listOf("70000")))
        assertEquals(2, server.requestCount)
    }

    @Test(expected = IllegalStateException::class)
    fun `resolve throws when there is no cache and the network fails`() {
        server.enqueue(MockResponse().setResponseCode(503))
        runBlocking { EaCloudSaveConfig.resolve(context, listOf("70000")) }
    }
}
