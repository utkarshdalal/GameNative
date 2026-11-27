package app.gamenative.service.ubisoft

import app.gamenative.service.ubisoft.UbisoftDemuxProto.Writer
import com.github.luben.zstd.Zstd
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UbisoftStoreInstallTest {
    private val cdn = HashMap<String, ByteArray>()
    private val requests = AtomicInteger()
    @Volatile private var failAfter = Int.MAX_VALUE
    private lateinit var dir: File

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = cdn[request.url.encodedPath.removePrefix("/")]
        val ok = body != null && requests.incrementAndGet() <= failAfter
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (ok) 200 else 500).message("")
            .body((if (ok) body!! else ByteArray(0)).toResponseBody())
            .build()
    }.build()

    private val progress = object : UbisoftProgress {
        var total = 0L
        var done = 0L
        var resumed = 0L

        override fun total(bytes: Long, done: Long) {
            total = bytes
            this.done = done
            resumed = done
        }

        @Synchronized override fun advance(bytes: Long) {
            done += bytes
        }

        override fun phase(phase: UbisoftProgress.Phase) {}
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("ubisoft-install").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)

    private fun data(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed * 7) xor (it shr 3)).toByte() }

    private fun piece(content: ByteArray): Writer {
        val packed = Zstd.compress(content, 3)
        val id = UbisoftManifest.hex(sha(packed))
        cdn["slices_v3/${UbisoftManifest.sliceFolder(id)}/$id"] = packed
        return Writer().uint(1, content.size.toLong()).uint(2, packed.size.toLong()).bytes(3, sha(packed))
    }

    private fun file(name: String, parts: List<ByteArray>): Writer {
        val w = Writer().string(1, name).uint(2, parts.sumOf { it.size }.toLong()).flag(3, false)
        parts.forEach { w.bytes(4, sha(it)) }
        parts.forEach { w.message(7, piece(it)) }
        return w
    }

    private fun manifestFile(body: Writer): ByteArray {
        val deflater = Deflater()
        deflater.setInput(body.toByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val signature = ByteArray(344) { 'S'.code.toByte() }
        val z = out.toByteArray()
        return le(3) + le(signature.size) + le(z.size) + signature + z
    }

    private fun v3() = Writer().uint(17, 1).uint(21, 3).uint(22, 3)

    /** Ownership and download services as the store session sees them; URLs point at the fake CDN. */
    private inner class FakeDemux : UbisoftDemuxConnection {
        private var initializes = 0
        private var ownershipInitialized = false

        override fun openService(service: String) = if (service == "ownership") 1L else 2L

        override fun exchange(connectionId: Long, message: ByteArray): ByteArray {
            val req = UbisoftDemuxProto.Message.parse(message).message(1)!!
            val rsp = Writer().uint(1, req.uint(1))
            if (connectionId == 1L) {
                if (req.has(2)) { ownershipInitialized = true; rsp.message(2, Writer().flag(1, true).message(2, Writer())) }
                // Like the service: a request before this connection's initialize closes it.
                if (req.has(28) && !ownershipInitialized) throw UbisoftCloudException("demux: service connection closed")
                if (req.has(28)) rsp.message(27, Writer().flag(1, true).string(2, "TOKEN").uint(3, System.currentTimeMillis() / 1000 + 3600))
            } else {
                // Like the service: one initialize per connection.
                if (req.has(2)) rsp.message(2, Writer().flag(1, req.message(2)!!.string(5) == "TOKEN" && ++initializes == 1))
                if (req.has(3)) {
                    val urls = Writer()
                    req.message(3)!!.message(1)!!.allBytes(2).forEach { urls.message(2, Writer().string(1, "https://cdn.test/" + String(it))) }
                    rsp.message(3, Writer().message(1, urls).uint(2, 600))
                }
            }
            return Writer().message(1, rsp).toByteArray()
        }

        override fun close() {}
    }

    private val connects = AtomicInteger()
    @Volatile private var clock = System.currentTimeMillis() / 1000

    private fun session() = UbisoftStoreSession(
        UbisoftCredentials("T", "S", Long.MAX_VALUE, "U", "U", "P"),
        "ownership",
        "download",
        nowSeconds = { clock },
    ) { connects.incrementAndGet(); FakeDemux() }

    private fun game(build: String) = UbisoftStoreGame(9, 9, "Test", "", "", "", build, "root:\n  name: Test\n")

    private fun install(build: String, manifest: ByteArray, language: String?) =
        runBlocking { UbisoftInstaller(session(), http, dir).install(game(build), manifest, language, emptySet(), progress) }

    @Test fun installResumesAndUpdates() {
        val shared = data(5000, 9)
        val exe = listOf(data(4000, 1), shared, data(3000, 2))
        val first = manifestFile(
            v3().message(
                4,
                Writer().uint(1, 0).uint(2, 0)
                    .message(3, file("bin\\game.exe", exe))
                    .message(3, file("data/a.pak", listOf(shared, data(100, 3))))
                    .message(3, Writer().string(1, "saves").uint(2, 0).flag(3, true))
                    .message(3, Writer().string(1, "empty.txt").uint(2, 0).flag(3, false))
                    .message(3, file("old.dat", listOf(data(50, 4)))),
            )
                .message(4, Writer().uint(1, 1).uint(2, 0).string(5, "fr-FR").message(3, file("loc/fr.pak", listOf(data(70, 5)))))
                .message(4, Writer().uint(1, 2).uint(2, 0).string(5, "en-US").message(3, file("loc/en.pak", listOf(data(80, 6))))),
        )

        failAfter = 3
        assertTrue(runCatching { install("M1", first, "en-US") }.isFailure)
        failAfter = Int.MAX_VALUE
        val record = install("M1", first, "en-US")
        assertTrue(progress.resumed > 0)
        assertEquals(progress.total, progress.done)
        assertArrayEquals(exe.reduce { a, b -> a + b }, File(dir, "bin/game.exe").readBytes())
        assertArrayEquals(shared + data(100, 3), File(dir, "data/a.pak").readBytes())
        assertTrue(File(dir, "saves").isDirectory)
        assertEquals(0L, File(dir, "empty.txt").length())
        assertTrue(File(dir, "loc/en.pak").isFile)
        assertFalse(File(dir, "loc/fr.pak").exists())
        assertTrue(dir.walk().none { it.name.endsWith(".ugpart") })
        assertEquals("M1", record.manifestId)
        assertEquals("en-US", UbisoftInstaller.readRecord(dir)?.language)

        val second = manifestFile(
            v3().message(
                4,
                Writer().uint(1, 0).uint(2, 0)
                    .message(3, file("bin\\game.exe", listOf(exe[0], shared, data(3100, 7))))
                    .message(3, file("data/a.pak", listOf(shared, data(100, 3))))
                    .message(3, file("data/new.pak", listOf(data(60, 8)))),
            ).message(4, Writer().uint(1, 2).uint(2, 0).string(5, "en-US").message(3, file("loc/en.pak", listOf(data(80, 6))))),
        )
        requests.set(0)
        install("M2", second, "en-US")
        assertArrayEquals(exe[0] + shared + data(3100, 7), File(dir, "bin/game.exe").readBytes())
        assertFalse(File(dir, "old.dat").exists())
        assertTrue(File(dir, "data/new.pak").isFile)
        // Unchanged files are kept and a piece they hold is copied, not fetched.
        assertEquals(3, requests.get())
    }

    @Test fun killedDuringAssemblyRefetchesInsteadOfWritingZeros() {
        val content = listOf(data(3000, 21), data(2000, 22))
        val bytes = manifestFile(v3().message(4, Writer().uint(1, 0).uint(2, 0).message(3, file("big.bin", content))))
        install("M4", bytes, null)
        // As after a kill between moving the file into place and saving: every piece marked fetched, no part file, no record.
        val ids = UbisoftInstallPlan.build(UbisoftManifest.parse(bytes).selectFiles(null, emptySet())).fetch.map { it.id }
        File(dir, ".gamenative-ubisoft/install.json").delete()
        File(dir, ".gamenative-ubisoft/download-state.json").writeText(
            org.json.JSONObject().put("manifestId", "M4").put("fetched", org.json.JSONArray(ids)).put("done", org.json.JSONArray()).toString(),
        )
        install("M4", bytes, null)
        assertArrayEquals(content[0] + content[1], File(dir, "big.bin").readBytes())
    }

    @Test fun expiringTokenStartsANewDownloadConnection() {
        val s = session()
        s.urls(9, listOf("manifests/M.manifest"))
        assertEquals(1, connects.get())
        s.urls(9, listOf("manifests/M.manifest"))
        assertEquals(1, connects.get())
        clock += 3600
        assertEquals(1, s.urls(9, listOf("manifests/M.manifest")).size)
        assertEquals(2, connects.get())
    }

    @Test fun corruptedPieceIsRefused() {
        val content = data(500, 11)
        val manifest = manifestFile(v3().message(4, Writer().uint(1, 0).uint(2, 0).message(3, file("x.bin", listOf(content)))))
        cdn.keys.toList().forEach { cdn[it] = Zstd.compress(data(500, 12), 3) }
        val error = runCatching { install("M3", manifest, null) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(File(dir, "x.bin").exists())
    }

    @Test fun unsupportedBuildsAreNamed() {
        val v2 = UbisoftManifest.parse(manifestFile(Writer().uint(17, 1).uint(21, 3).uint(22, 2)))
        assertTrue(runCatching { v2.requireSupported() }.exceptionOrNull() is UbisoftUnsupportedException)
        val lzham = UbisoftManifest.parse(manifestFile(Writer().uint(17, 1).uint(21, 2).uint(22, 3)))
        assertTrue(runCatching { lzham.requireSupported() }.exceptionOrNull()?.message!!.contains("LZHAM"))
        val plainLzham = UbisoftManifest.parse(manifestFile(Writer().uint(17, 0).uint(21, 2).uint(22, 3)))
        plainLzham.requireSupported()
    }

    @Test fun slicePaths() {
        val m = UbisoftManifest.parse(manifestFile(v3()))
        assertEquals("slices_v3/s/7CEA7566B386C7BC72D296624CBD0B5C6CF0A143", m.slicePath("7CEA7566B386C7BC72D296624CBD0B5C6CF0A143"))
        assertEquals("slices_v3/0/C07B657009F47B5D2D6411EFC679F6E5767C71EE", m.slicePath("C07B657009F47B5D2D6411EFC679F6E5767C71EE"))
        assertEquals("slices/AB", UbisoftManifest.parse(manifestFile(Writer().uint(22, 0))).slicePath("AB"))
        assertTrue(runCatching { UbisoftManifest.normalizeName("../evil") }.isFailure)
        assertTrue(runCatching { UbisoftManifest.normalizeName("C:\\evil") }.isFailure)
        assertEquals("bin/a.exe", UbisoftManifest.normalizeName("\\bin\\a.exe"))
    }

    @Test fun languagePick() {
        val m = UbisoftManifest.parse(
            manifestFile(v3().message(23, Writer().string(1, "en-US")).message(23, Writer().string(1, "fr-FR")).message(23, Writer().string(1, "de-DE"))),
        )
        assertEquals("fr-FR", m.pickLanguage(Locale.FRANCE))
        assertEquals("fr-FR", m.pickLanguage(Locale.CANADA_FRENCH))
        assertEquals("en-US", m.pickLanguage(Locale.JAPAN))
    }

    private val fc3Like = """
        version: 2.0
        root:
          name: "Far Cry® 3"
          thumb_image: thumb.jpg
          start_game:
            online:
              overlay_supported: no
              executables:
                - path:
                    register: HKEY_CURRENT_USER\SOFTWARE\UBISOFT\Far Cry 3\ExecutableName
                  working_directory:
                    register: HKEY_LOCAL_MACHINE\SOFTWARE\UBISOFT\Far Cry 3\InstallDir
                  arguments_register: HKEY_CURRENT_USER\SOFTWARE\UBISOFT\Far Cry 3\StartArguments
                  description: FC3_GAME
                - path:
                    relative: bin\FC3Editor.exe
          space_id: abc
    """.trimIndent()

    private val localized = """
        root:
          name: l1
          thumb_image: l2
          start_game:
            offline:
              executables:
              - shortcut_name: Game
                path:
                  relative: Game.exe
                working_directory:
                  register: HKEY_LOCAL_MACHINE\SOFTWARE\Ubisoft\Launcher\Installs\720\InstallDir
                arguments: -offline
          overlay_notifications: []
        localizations:
          default:
            l1: Localized Game
            l2: art.jpg
    """.trimIndent()

    private val steamCopy = """
        root:
          name: Steam Game
          start_game:
            steam:
              steam_app_id: 552520
          third_party_platform:
            name: Steam
    """.trimIndent()

    private fun registry(key: String, vararg values: Pair<String, String>) =
        UbisoftManifest.RegistryValue(key, mapOf(*values), emptyMap())

    @Test fun startFromRegistryValues() {
        val config = UbisoftProductConfig.parse(fc3Like)!!
        assertEquals("Far Cry® 3", config.name)
        assertEquals(2, config.executables.size)
        val reg = listOf(
            registry("HKEY_CURRENT_USER\\SOFTWARE\\Ubisoft\\Far Cry 3\\ExecutableName", "" to "bin/FC3Updater.exe"),
            registry("HKEY_LOCAL_MACHINE\\SOFTWARE\\Ubisoft\\Far Cry 3\\InstallDir", "" to "\$INSTALL_DIR"),
            registry("HKEY_CURRENT_USER\\SOFTWARE\\Ubisoft\\Far Cry 3\\StartArguments", "" to ""),
            registry("HKEY_LOCAL_MACHINE\\SOFTWARE\\Ubisoft\\Far Cry 3\\Language", "en-US" to "English", "fr-FR" to "French"),
            registry("HKEY_LOCAL_MACHINE\\SOFTWARE\\Ubisoft\\Far Cry 3\\GameUpdate\\execPath", "" to "\$INSTALL_DIR/bin/FC3Updater.exe"),
        )
        val start = UbisoftGameStart.resolve(config, reg, "fr-FR")!!
        assertEquals("bin/FC3Updater.exe", start.executable)
        assertEquals("", start.workingDirectory)
        assertEquals("", start.arguments)
        val writes = UbisoftGameStart.registryWrites(reg, "fr-FR", "A:\\").associateBy { it.name }
        assertEquals("A:\\", writes.getValue("InstallDir").text)
        assertEquals("French", writes.getValue("Language").text)
        assertEquals("A:/bin/FC3Updater.exe", writes.getValue("execPath").text)
        assertEquals("HKCU", writes.getValue("ExecutableName").hive)
        assertEquals("SOFTWARE\\Ubisoft\\Far Cry 3\\GameUpdate", writes.getValue("execPath").key)
    }

    @Test fun localizedNamesAndLibraryFilter() {
        val config = UbisoftProductConfig.parse(localized)!!
        assertEquals("Localized Game", config.name)
        assertEquals("art.jpg", config.thumbImage)
        val start = UbisoftGameStart.resolve(config, emptyList(), null)!!
        assertEquals("Game.exe", start.executable)
        assertEquals("", start.workingDirectory)
        assertEquals("-offline", start.arguments)
        assertEquals("Steam", UbisoftProductConfig.parse(steamCopy)!!.thirdPartyPlatform)
        val placeholder = localized.replace("arguments: -offline", "arguments: /appid:{AppId} -x {other}")
        val noAppId = UbisoftProductConfig.parse(placeholder)!!
        assertTrue(runCatching { UbisoftGameStart.resolve(noAppId, emptyList(), null) }.exceptionOrNull() is UbisoftUnsupportedException)
        assertEquals("/appid:owned-id -x {other}", UbisoftGameStart.resolve(noAppId, emptyList(), null, "owned-id")!!.arguments)
        val withAppId = UbisoftProductConfig.parse(placeholder.replace("  name:", "  app_id: 0a1b2c3d-0000-4000-8000-123456789abc\n  name:"))!!
        assertEquals("/appid:0a1b2c3d-0000-4000-8000-123456789abc -x {other}", UbisoftGameStart.resolve(withAppId, emptyList(), null, "owned-id")!!.arguments)

        fun owned(id: Long, config: String, manifest: String = "M", type: Int = 0, isOwned: Boolean = true) =
            UbisoftOwnedGame(id, id, isOwned, type, 3, config, manifest, emptyList(), false, "")
        val games = UbisoftLibrary.storeGames(
            listOf(
                owned(1, localized),
                owned(2, steamCopy),
                owned(3, localized, manifest = ""),
                owned(4, localized, type = 1),
                owned(5, localized, isOwned = false),
            ),
        ) { "https://assets/$it" }
        assertEquals(listOf(1L), games.map { it.productId })
        assertEquals("https://assets/art.jpg", games[0].coverUrl)

        val art = localized.replace("thumb_image: l2", "thumb_image: l2\n  logo_image: logo.png\n  icon_image: game.ico")
        val titled = UbisoftLibrary.storeGames(listOf(owned(6, art))) { "https://assets/$it" }.single()
        assertEquals("https://assets/logo.png", titled.logoUrl)
        assertEquals("", titled.iconUrl)
    }

    @Test fun downloadServiceMessages() {
        val up = UbisoftDemuxProto.Message.parse(UbisoftDemuxProto.downloadUrls(4, 46, listOf("a", "b")))
        val req = up.message(1)!!
        assertEquals(4L, req.uint(1))
        assertEquals(46L, req.message(3)!!.message(1)!!.uint(1))
        assertEquals(listOf("a", "b"), req.message(3)!!.message(1)!!.allBytes(2).map { String(it) })
        val rsp = UbisoftDemuxProto.Message.parse(
            Writer().uint(1, 4).message(3, Writer().message(1, Writer().uint(1, 1)).uint(2, 60)).toByteArray(),
        )
        assertFalse(UbisoftDemuxProto.downloadUrlsResult(rsp)!!.owned)
        val init = UbisoftDemuxProto.Message.parse(UbisoftDemuxProto.downloadInitialize(1, "tok")).message(1)!!
        assertEquals("tok", init.message(2)!!.string(5))
        assertNull(UbisoftDemuxProto.productConfigResult(UbisoftDemuxProto.Message.parse(Writer().uint(1, 1).toByteArray())))
        val owned = UbisoftDemuxProto.ownedGames(
            UbisoftDemuxProto.Message.parse(
                Writer().uint(1, 1).message(
                    2,
                    Writer().flag(1, true).message(
                        2,
                        Writer().message(1, Writer().uint(1, 46).flag(20, true).string(2, "KEY").string(17, "root:\n").string(21, "ABC").bytes(16, byteArrayOf(5, 6))),
                    ),
                ).toByteArray(),
            ),
        )
        assertNotNull(owned)
        assertEquals("ABC", owned!![0].latestManifest)
        assertEquals(listOf(5L, 6L), owned[0].associations)
    }
}
