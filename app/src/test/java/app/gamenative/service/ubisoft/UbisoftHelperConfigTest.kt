package app.gamenative.service.ubisoft

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Properties

class UbisoftHelperConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    private val text = """
        # values supplied by the helper
        ubi.web.app.id = web app
        ubi.web.genome.id = genome
        ubi.web.next.url = https://landing.test/done
        ubi.web.login.url = https://login.test/?a={appId}&g={genomeId}&l={lang}&n={nextUrl}
        ubi.app.id = launcher-app
        ubi.http.headers = X-App: {appId}|X-Kind: pc| broken
        ubi.empty =
    """.trimIndent()

    private fun properties() = Properties().apply { load(text.reader()) }

    @Test fun readsValuesAndRejectsMissingOnes() {
        val p = properties()
        assertEquals("launcher-app", UbisoftHelperConfig.value(p, UbisoftHelperConfig.APP_ID))
        assertNull(UbisoftHelperConfig.optional(p, "ubi.empty"))
        assertThrows(IllegalStateException::class.java) { UbisoftHelperConfig.value(p, UbisoftHelperConfig.SESSIONS_URL) }
    }

    @Test fun buildsTheLoginUrlFromItsTemplate() {
        assertEquals(
            "https://login.test/?a=web+app&g=genome&l=en-US&n=https%3A%2F%2Flanding.test%2Fdone",
            UbisoftHelperConfig.loginUrl(properties(), "en-US"),
        )
    }

    @Test fun parsesHeaderTemplates() {
        val headers = UbisoftHelperConfig.headers(properties().getProperty(UbisoftHelperConfig.HTTP_HEADERS), mapOf("appId" to "A"))
        assertEquals(listOf("X-App" to "A", "X-Kind" to "pc"), headers)
        assertEquals(emptyList<Pair<String, String>>(), UbisoftHelperConfig.headers(null, emptyMap()))
        assertEquals("t=abc", UbisoftHelperConfig.fill("t={ticket}", mapOf("ticket" to "abc")))
    }

    @Test fun extractsTheArchiveAndReadsItsProperties() {
        val files = temporary.newFolder()
        val bytes = ByteArrayOutputStream().also { out ->
            ZstdCompressorOutputStream(out).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    for ((name, body) in listOf(
                        UbisoftHelperArchive.STUB to "stub", "ubihost32.exe" to "stub32",
                        UbisoftHelperArchive.CONFIG to text, "NOTICE.txt" to "notice",
                    )) {
                        val data = body.toByteArray()
                        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                        tar.write(data)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }.toByteArray()
        UbisoftHelperArchive.ensureExtracted(files) { bytes.inputStream() }
        assertTrue(UbisoftHelperArchive.isReady(files))
        assertEquals("genome", UbisoftHelperArchive.loadConfig(files).getProperty(UbisoftHelperConfig.WEB_GENOME_ID))
    }
}
