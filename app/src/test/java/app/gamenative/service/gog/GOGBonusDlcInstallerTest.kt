package app.gamenative.service.gog

import app.gamenative.data.DownloadInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGBonusDlcInstallerTest {

    private val bonusOnlyJson = """
        {
          "id": 2086613524,
          "title": "Two Beasts Or Not To Beast - Unrated Patch",
          "downloads": {
            "installers": [],
            "patches": [],
            "language_packs": [],
            "bonus_content": [
              {
                "id": 103676,
                "name": "Unrated Patch",
                "type": "game add-ons",
                "count": 1,
                "total_size": 346030080,
                "files": [
                  {
                    "id": 103676,
                    "size": 346030080,
                    "downlink": "https://api.gog.com/products/2086613524/downlink/product_bonus/103676"
                  }
                ]
              },
              {
                "id": 103677,
                "name": "Soundtrack",
                "type": "audio",
                "count": 1,
                "total_size": 1000,
                "files": [
                  { "id": 103677, "size": 1000, "downlink": "https://api.gog.com/products/2086613524/downlink/product_bonus/103677" }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    private val installerJson = """
        {
          "id": 1,
          "downloads": {
            "installers": [
              {
                "id": "installer_mac_en",
                "os": "mac",
                "language": "en",
                "total_size": 5,
                "files": [ { "id": "en2installer0", "size": 5, "downlink": "https://api.gog.com/products/1/downlink/installer/en2installer0" } ]
              },
              {
                "id": "installer_windows_en",
                "os": "windows",
                "language": "en",
                "total_size": 300,
                "files": [
                  { "id": "en1installer0", "size": 100, "downlink": "https://api.gog.com/products/1/downlink/installer/en1installer0" },
                  { "id": "en1installer1", "size": 200, "downlink": "https://api.gog.com/products/1/downlink/installer/en1installer1" }
                ]
              },
              {
                "id": "installer_windows_de",
                "os": "windows",
                "language": "de",
                "total_size": 40,
                "files": [ { "id": "de1installer0", "size": 40, "downlink": "https://api.gog.com/products/1/downlink/installer/de1installer0" } ]
              }
            ],
            "bonus_content": [
              {
                "id": 9,
                "name": "Patch",
                "type": "game add-ons",
                "total_size": 7,
                "files": [ { "id": 9, "size": 7, "downlink": "https://api.gog.com/products/1/downlink/product_bonus/9" } ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun parseProductDownloads_readsBonusContentFiles() {
        val downloads = GOGApiClient.parseProductDownloads(JSONObject(bonusOnlyJson))

        assertTrue(downloads.installers.isEmpty())
        assertEquals(2, downloads.bonusContent.size)
        val patch = downloads.bonusContent.first()
        assertEquals("103676", patch.id)
        assertEquals("game add-ons", patch.type)
        assertEquals(346030080L, patch.totalSize)
        assertEquals(
            "https://api.gog.com/products/2086613524/downlink/product_bonus/103676",
            patch.files.single().downlink,
        )
    }

    @Test
    fun resolve_bonusOnlyProductIsBonusWithAddOnSize() {
        val resolution = GOGBonusDlcInstaller.resolve(GOGApiClient.parseProductDownloads(JSONObject(bonusOnlyJson)), "english")

        assertEquals(GogDlcKind.BONUS, resolution.kind)
        assertEquals(listOf("103676"), resolution.items.map { it.id })
        assertEquals(346030080L, resolution.downloadSize)
    }

    @Test
    fun resolve_windowsInstallerWinsAndSumsItsFiles() {
        val resolution = GOGBonusDlcInstaller.resolve(GOGApiClient.parseProductDownloads(JSONObject(installerJson)), "english")

        assertEquals(GogDlcKind.INSTALLER, resolution.kind)
        assertEquals(listOf("installer_windows_en"), resolution.items.map { it.id })
        assertEquals(300L, resolution.downloadSize)
    }

    @Test
    fun resolve_picksInstallerForContainerLanguage() {
        val resolution = GOGBonusDlcInstaller.resolve(GOGApiClient.parseProductDownloads(JSONObject(installerJson)), "german")

        assertEquals(listOf("installer_windows_de"), resolution.items.map { it.id })
        assertEquals(40L, resolution.downloadSize)
    }

    @Test
    fun resolve_nothingUsableIsNone() {
        val json = JSONObject(
            """{"downloads":{"installers":[],"bonus_content":[{"id":1,"name":"Art","type":"artworks","total_size":5,"files":[{"id":1,"size":5,"downlink":"https://x"}]}]}}""",
        )

        val resolution = GOGBonusDlcInstaller.resolve(GOGApiClient.parseProductDownloads(json), "english")

        assertEquals(GogDlcKind.NONE, resolution.kind)
        assertEquals(0L, resolution.downloadSize)
    }

    @Test
    fun parseChecksumXml_readsFileAndChunks() {
        val xml = """
            <file name="patch.zip" available="1" notavailablemsg="" md5="0123456789abcdef0123456789abcdef" chunks="2" timestamp="2020-01-01 00:00:00" total_size="20971520">
            	<chunk id="0" from="0" to="10485759" method="md5">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</chunk>
            	<chunk id="1" from="10485760" to="20971519" method="md5">bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb</chunk>
            </file>
        """.trimIndent()

        val info = GOGBonusDlcInstaller.parseChecksumXml(xml)!!

        assertEquals("patch.zip", info.name)
        assertEquals("0123456789abcdef0123456789abcdef", info.md5)
        assertEquals(20971520L, info.totalSize)
        assertEquals(2, info.chunks.size)
        assertEquals(GOGBonusDlcInstaller.ChecksumChunk(1, 10485760L, 20971519L, "md5", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), info.chunks[1])
    }

    @Test
    fun parseChecksumXml_emptyOrInvalidIsNull() {
        assertNull(GOGBonusDlcInstaller.parseChecksumXml(""))
        assertNull(GOGBonusDlcInstaller.parseChecksumXml("not xml"))
        assertNull(GOGBonusDlcInstaller.parseChecksumXml("<other/>"))
    }

    @Test
    fun extractZip_writesEntriesUnderRootOverwriting() {
        val root = Files.createTempDirectory("gog-bonus-root").toFile()
        val zip = File(Files.createTempDirectory("gog-bonus-zip").toFile(), "patch.zip")
        File(root, "data").mkdirs()
        File(root, "data/game.pak").writeText("old")
        writeZip(zip, mapOf("data/game.pak" to "new", "data/extra/added.txt" to "added"))

        GOGBonusDlcInstaller.extractZip(zip, root)

        assertEquals("new", File(root, "data/game.pak").readText())
        assertEquals("added", File(root, "data/extra/added.txt").readText())
        assertEquals(listOf("game.pak", "extra").sorted(), File(root, "data").list()!!.sorted())
        root.deleteRecursively()
        zip.parentFile?.deleteRecursively()
    }

    @Test
    fun extractZip_rejectsEntriesEscapingRootBeforeWriting() {
        val parent = Files.createTempDirectory("gog-bonus-slip").toFile()
        val root = File(parent, "game").apply { mkdirs() }
        val zip = File(parent, "evil.zip")
        writeZip(zip, mapOf("ok.txt" to "ok", "../escaped.txt" to "bad"))

        assertThrows(IOException::class.java) { GOGBonusDlcInstaller.extractZip(zip, root) }

        assertFalse(File(parent, "escaped.txt").exists())
        assertFalse(File(root, "ok.txt").exists())
        parent.deleteRecursively()
    }

    @Test
    fun extractZip_stopsWhenDownloadIsCancelled() {
        val root = Files.createTempDirectory("gog-bonus-cancel").toFile()
        val zip = File(Files.createTempDirectory("gog-bonus-cancel-zip").toFile(), "patch.zip")
        writeZip(zip, mapOf("data/game.pak" to "new"))
        val downloadInfo = DownloadInfo(jobCount = 1, gameId = 1, downloadingAppIds = CopyOnWriteArrayList())
        downloadInfo.setActive(false)

        assertThrows(IOException::class.java) { GOGBonusDlcInstaller.extractZip(zip, root, downloadInfo) }

        assertFalse(File(root, "data/game.pak").exists())
        root.deleteRecursively()
        zip.parentFile?.deleteRecursively()
    }

    @Test
    fun targetNameFor_prefixesFileIdWhenAnotherFileTookTheName() {
        val dir = Files.createTempDirectory("gog-bonus-names").toFile()
        val first = GogDownloadFile(id = "1", size = 1L, downlink = "https://api.gog.com/1")
        val second = GogDownloadFile(id = "2", size = 1L, downlink = "https://api.gog.com/2")

        assertEquals("patch.zip", GOGBonusDlcInstaller.targetNameFor(dir, first, "patch.zip"))
        File(dir, "1.done").writeText("patch.zip")
        assertEquals("patch.zip", GOGBonusDlcInstaller.targetNameFor(dir, first, "patch.zip"))
        assertEquals("2-patch.zip", GOGBonusDlcInstaller.targetNameFor(dir, second, "patch.zip"))
        assertEquals("other.zip", GOGBonusDlcInstaller.targetNameFor(dir, second, "other.zip"))
        File(dir, "3.done").writeText("2-patch.zip")
        assertEquals("2-2-patch.zip", GOGBonusDlcInstaller.targetNameFor(dir, second, "patch.zip"))
        File(dir, "4.done").writeText("2-2-patch.zip")
        assertEquals("2-3-patch.zip", GOGBonusDlcInstaller.targetNameFor(dir, second, "patch.zip"))
        dir.deleteRecursively()
    }

    @Test
    fun appliedLanguageMatches_requiresRecordedLanguage() {
        val dir = Files.createTempDirectory("gog-bonus-language").toFile()

        assertFalse(GOGBonusDlcInstaller.appliedLanguageMatches(dir, "english"))
        File(dir, "applied_language").writeText("english")
        assertTrue(GOGBonusDlcInstaller.appliedLanguageMatches(dir, "English"))
        assertFalse(GOGBonusDlcInstaller.appliedLanguageMatches(dir, "german"))
        dir.deleteRecursively()
    }

    @Test
    fun fileNameFor_prefersContentDispositionAndSanitizes() {
        assertEquals(
            "Unrated_Patch_1.0.zip",
            GOGBonusDlcInstaller.fileNameFor("attachment; filename=\"Unrated Patch 1.0.zip\"", "/secure/abc/x.bin", "dlc-1"),
        )
        assertEquals(
            "setup_dlc_1.0__12345_.exe",
            GOGBonusDlcInstaller.fileNameFor(null, "/secure/abc/setup_dlc_1.0_%2812345%29.exe", "dlc-1"),
        )
        assertEquals("dlc-1", GOGBonusDlcInstaller.fileNameFor(null, "/", "dlc-1"))
    }

    @Test
    fun isTrustedGogUrl_acceptsOnlyHttpsGogHosts() {
        assertTrue(GOGApiClient.isTrustedGogUrl("https://api.gog.com/products/1/downlink/installer/en1installer0"))
        assertTrue(GOGApiClient.isTrustedGogUrl("https://gog.com/downlink"))
        assertFalse(GOGApiClient.isTrustedGogUrl("http://api.gog.com/products/1/downlink"))
        assertFalse(GOGApiClient.isTrustedGogUrl("https://api.gog.com.evil.example/downlink"))
        assertFalse(GOGApiClient.isTrustedGogUrl("https://evilgog.com/downlink"))
        assertFalse(GOGApiClient.isTrustedGogUrl("not a url"))
    }

    private fun writeZip(zip: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }
}
