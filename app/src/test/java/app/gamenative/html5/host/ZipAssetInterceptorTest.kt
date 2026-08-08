package app.gamenative.html5.host

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

// pure-jvm tests (no Robolectric, no Android imports) — mirrors AssetInterceptorTest precedent.
// covers companion readIndexAndInjectFromZip + internal openZipEntry (path-traversal + serving).
class ZipAssetInterceptorTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun writeZip(target: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return target
    }

    // source zip must not mutate. asserts byte-length before + after is identical.
    @Test
    fun readIndexAndInjectFromZip_basicInjection_producesShimScriptTag() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "index.html" to """<html><head></head><body><script src="main.js"></script></body></html>"""
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        val lenBefore = zipFile.length()
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val out = ZipAssetInterceptor.readIndexAndInjectFromZip(
                zip,
                listOf("https://example/_shims/shim.js"),
            )
            val text = String(out, Charsets.UTF_8)
            assertTrue("shim url present: $text", text.contains("https://example/_shims/shim.js"))
            assertTrue("script tag present: $text", text.contains("<script"))
            // shim must precede game script.
            val shimIdx = text.indexOf("https://example/_shims/shim.js")
            val gameIdx = text.indexOf("main.js")
            assertTrue("shim precedes game (shim=$shimIdx game=$gameIdx)", shimIdx < gameIdx)
        }
        val lenAfter = zipFile.length()
        assertEquals("source zip length unchanged", lenBefore, lenAfter)
    }

    @Test
    fun readIndexAndInjectFromZip_missingIndex_throwsIllegalStateException() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf("scripts/other.js" to ByteArray(0)),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            assertThrows(IllegalStateException::class.java) {
                ZipAssetInterceptor.readIndexAndInjectFromZip(zip, listOf("/_shims/a.js"))
            }
        }
    }

    @Test
    fun readIndexAndInjectFromZip_passes_locale_through() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "index.html" to """<html><body><script src="g.js"></script></body></html>"""
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val out = ZipAssetInterceptor.readIndexAndInjectFromZip(
                zip,
                listOf("/_shims/s.js"),
                locale = "de-DE",
            )
            val text = String(out, Charsets.UTF_8)
            val localeIdx = text.indexOf("navigator,'language'")
            val shimIdx = text.indexOf("/_shims/s.js")
            val gameIdx = text.indexOf("g.js")
            assertTrue("locale injected: $text", localeIdx >= 0)
            assertTrue("de-DE present", text.contains("\"de-DE\""))
            assertTrue("order: locale < shim < game", localeIdx < shimIdx && shimIdx < gameIdx)
        }
    }

    @Test
    fun readIndexAndInjectFromZip_shimListEmpty_stillInjectsWithoutThrow() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "index.html" to """<html><body><script src="g.js"></script></body></html>"""
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val out = ZipAssetInterceptor.readIndexAndInjectFromZip(zip, emptyList())
            assertTrue("output non-empty", out.isNotEmpty())
            // game script preserved.
            val text = String(out, Charsets.UTF_8)
            assertTrue(text.contains("g.js"))
        }
    }

    @Test
    fun openZipEntry_dotDotInPath_returnsNullAndLogs() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf("index.html" to "<html></html>".toByteArray(Charsets.UTF_8)),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val interceptor = ZipAssetInterceptor(mockk(relaxed = true), zip, listOf())
            assertNull("../secret rejected", interceptor.openZipEntry("../secret"))
            assertNull("nested ../ rejected", interceptor.openZipEntry("scripts/../../etc/passwd"))
        }
    }

    @Test
    fun openZipEntry_leadingSlash_returnsNull() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf("index.html" to "<html></html>".toByteArray(Charsets.UTF_8)),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val interceptor = ZipAssetInterceptor(mockk(relaxed = true), zip, listOf())
            assertNull("/etc/passwd rejected", interceptor.openZipEntry("/etc/passwd"))
        }
    }

    @Test
    fun openZipEntry_missingEntry_returnsNull() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf("index.html" to "<html></html>".toByteArray(Charsets.UTF_8)),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val interceptor = ZipAssetInterceptor(mockk(relaxed = true), zip, listOf())
            assertNull(interceptor.openZipEntry("does-not-exist.js"))
        }
    }

    @Test
    fun openZipEntry_validEntry_returnsResponse() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "index.html" to "<html></html>".toByteArray(Charsets.UTF_8),
                "scripts/c3runtime.js" to "// c3runtime".toByteArray(Charsets.UTF_8),
            ),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val interceptor = ZipAssetInterceptor(mockk(relaxed = true), zip, listOf())
            val response = interceptor.openZipEntry("scripts/c3runtime.js")
            // WebResourceResponse getters are Android stubs in pure-JVM — only assert non-null.
            // mime correctness via mimeFor is exercised in WebViewScreenLifecycleTest.
            assertNotNull("response for valid entry", response)
        }
    }

    @Test
    fun openZipEntry_directoryEntry_returnsNull() {
        val zipFile = writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "index.html" to "<html></html>".toByteArray(Charsets.UTF_8),
                // explicit directory entry — zip convention is trailing slash.
                "scripts/" to ByteArray(0),
                "scripts/a.js" to "// a".toByteArray(Charsets.UTF_8),
            ),
        )
        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val interceptor = ZipAssetInterceptor(mockk(relaxed = true), zip, listOf())
            assertNull("directory entry rejected", interceptor.openZipEntry("scripts/"))
        }
    }
}
