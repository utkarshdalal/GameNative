package app.gamenative.service.ea

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EaZipTest {

    private class Item(val nameBytes: ByteArray, val utf8: Boolean, val data: ByteArray, val deflate: Boolean) {
        val crc: Long = CRC32().also { it.update(data) }.value
        val stored: ByteArray = if (deflate) rawDeflate(data) else data
        var offset = 0L
    }

    private class Built(val bytes: ByteArray, val cdOffset: Long)

    private fun item(name: String, data: ByteArray, deflate: Boolean) = Item(name.toByteArray(Charsets.UTF_8), true, data, deflate)

    private fun buildZip(
        items: List<Item>,
        centralOrder: List<Int> = items.indices.toList(),
        zip64Entry: Int = -1,
        zip64Eocd: Boolean = false,
        comment: ByteArray = ByteArray(0),
    ): Built {
        val out = ByteArrayOutputStream()
        for (it in items) {
            it.offset = out.size().toLong()
            out.write(le(4, 0x04034b50L))
            out.write(le(2, 20))
            out.write(le(2, if (it.utf8) 1L shl 11 else 0L))
            out.write(le(2, if (it.deflate) 8 else 0))
            out.write(le(4, 0))
            out.write(le(4, it.crc))
            out.write(le(4, it.stored.size.toLong()))
            out.write(le(4, it.data.size.toLong()))
            out.write(le(2, it.nameBytes.size.toLong()))
            out.write(le(2, 0))
            out.write(it.nameBytes)
            out.write(it.stored)
        }
        val cdOffset = out.size().toLong()
        for (index in centralOrder) {
            val it = items[index]
            val big = index == zip64Entry
            val extra = if (big) {
                le(2, 0x5455) + le(2, 5) + ByteArray(5) +
                    le(2, 0x0001) + le(2, 24) + le(8, it.data.size.toLong()) + le(8, it.stored.size.toLong()) + le(8, it.offset)
            } else {
                ByteArray(0)
            }
            out.write(le(4, 0x02014b50L))
            out.write(le(2, 45))
            out.write(le(2, 45))
            out.write(le(2, if (it.utf8) 1L shl 11 else 0L))
            out.write(le(2, if (it.deflate) 8 else 0))
            out.write(le(4, 0))
            out.write(le(4, it.crc))
            out.write(le(4, if (big) 0xFFFFFFFFL else it.stored.size.toLong()))
            out.write(le(4, if (big) 0xFFFFFFFFL else it.data.size.toLong()))
            out.write(le(2, it.nameBytes.size.toLong()))
            out.write(le(2, extra.size.toLong()))
            out.write(le(2, 0))
            out.write(le(2, 0))
            out.write(le(2, 0))
            out.write(le(4, 0))
            out.write(le(4, if (big) 0xFFFFFFFFL else it.offset))
            out.write(it.nameBytes)
            out.write(extra)
        }
        val cdSize = out.size() - cdOffset
        if (zip64Eocd) {
            val zip64EocdOffset = out.size().toLong()
            out.write(le(4, 0x06064b50L))
            out.write(le(8, 44))
            out.write(le(2, 45))
            out.write(le(2, 45))
            out.write(le(4, 0))
            out.write(le(4, 0))
            out.write(le(8, items.size.toLong()))
            out.write(le(8, items.size.toLong()))
            out.write(le(8, cdSize))
            out.write(le(8, cdOffset))
            out.write(le(4, 0x07064b50L))
            out.write(le(4, 0))
            out.write(le(8, zip64EocdOffset))
            out.write(le(4, 1))
        }
        out.write(le(4, 0x06054b50L))
        out.write(le(2, 0))
        out.write(le(2, 0))
        out.write(le(2, if (zip64Eocd) 0xFFFFL else items.size.toLong()))
        out.write(le(2, if (zip64Eocd) 0xFFFFL else items.size.toLong()))
        out.write(le(4, if (zip64Eocd) 0xFFFFFFFFL else cdSize))
        out.write(le(4, if (zip64Eocd) 0xFFFFFFFFL else cdOffset))
        out.write(le(2, comment.size.toLong()))
        out.write(comment)
        return Built(out.toByteArray(), cdOffset)
    }

    private fun sampleItems() = listOf(
        item("Game/", ByteArray(0), deflate = false),
        item("Game/readme.txt", "hello EA".toByteArray(), deflate = false),
        item("Game/data.bin", ByteArray(20_000) { (it % 7).toByte() }, deflate = true),
        item("Game/日本語.txt", "unicode name".toByteArray(), deflate = true),
        Item("Game/caf\u00e9.txt".toByteArray(Charsets.ISO_8859_1), false, "cp1252 name".toByteArray(), deflate = false),
    )

    private fun parseAll(zip: ByteArray): List<EaZip.Entry> {
        val result = EaZip.parseTail(zip, zip.size.toLong())
        assertTrue("expected entries, got $result", result is EaZip.TailResult.Entries)
        return (result as EaZip.TailResult.Entries).entries
    }

    private fun content(zip: ByteArray, entry: EaZip.Entry): ByteArray {
        val raw = zip.copyOfRange(entry.dataOffset.toInt(), (entry.dataOffset + entry.compressedSize).toInt())
        return when (entry.method) {
            0 -> raw
            8 -> rawInflate(raw, entry.uncompressedSize.toInt())
            else -> throw AssertionError("unexpected method ${entry.method}")
        }
    }

    private fun assertMatches(zip: ByteArray, items: List<Item>, entries: List<EaZip.Entry>) {
        assertEquals(items.size, entries.size)
        for (it in items) {
            val name = String(it.nameBytes, if (it.utf8) Charsets.UTF_8 else Charsets.ISO_8859_1)
            val entry = entries.single { e -> e.name == name }
            assertEquals(it.crc, entry.crc32)
            assertEquals(it.data.size.toLong(), entry.uncompressedSize)
            assertEquals(it.stored.size.toLong(), entry.compressedSize)
            assertEquals(it.offset, entry.localHeaderOffset)
            assertEquals(30L + it.nameBytes.size + it.offset, entry.dataOffset)
            assertArrayEquals(it.data, content(zip, entry))
        }
    }

    @Test
    fun handBuiltArchive_isAValidZip() {
        val items = sampleItems().take(4)
        val zip = buildZip(items).bytes
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                names += e.name
                val data = zin.readBytes()
                assertArrayEquals(items.single { it.nameBytes.contentEquals(e.name.toByteArray()) }.data, data)
            }
        }
        assertEquals(items.map { String(it.nameBytes) }, names)
    }

    @Test
    fun parseTail_storedAndDeflatedEntries_dataOffsetsPointAtTheData() {
        val items = sampleItems()
        val zip = buildZip(items, comment = "a comment".toByteArray()).bytes
        val entries = parseAll(zip)
        assertMatches(zip, items, entries)
        assertTrue(entries.single { it.name == "Game/" }.isDirectory)
        assertNotNull(entries.singleOrNull { it.name == "Game/日本語.txt" })
        assertNotNull(entries.singleOrNull { it.name == "Game/café.txt" })
        assertEquals(8, entries.single { it.name == "Game/data.bin" }.method)
    }

    @Test
    fun parseTail_centralDirectoryOutOfOrder_stillDerivesOffsets() {
        val items = sampleItems()
        val zip = buildZip(items, centralOrder = items.indices.reversed().toList()).bytes
        assertMatches(zip, items, parseAll(zip))
    }

    @Test
    fun parseTail_zip64ExtraFieldAfterAnotherRecord_readsZip64Values() {
        val items = sampleItems()
        val zip = buildZip(items, zip64Entry = 2).bytes
        assertMatches(zip, items, parseAll(zip))
    }

    @Test
    fun parseTail_zip64EndOfCentralDirectory_isFollowed() {
        val items = sampleItems()
        val zip = buildZip(items, zip64Eocd = true).bytes
        assertMatches(zip, items, parseAll(zip))
    }

    @Test
    fun parseTail_truncatedTail_asksForTheCentralDirectory() {
        val built = buildZip(sampleItems())
        val zip = built.bytes
        val tail = zip.copyOfRange(zip.size - 22, zip.size)
        assertEquals(EaZip.TailResult.NeedFrom(built.cdOffset), EaZip.parseTail(tail, zip.size.toLong()))
    }

    @Test
    fun parseTail_growingFromATinyTail_converges() {
        for ((zip64, tailSize) in listOf(false to 10, true to 10, false to 22, true to 22)) {
            val items = sampleItems()
            val comment = if (tailSize == 10) ByteArray(300) { 'x'.code.toByte() } else ByteArray(0)
            val zip = buildZip(items, zip64Eocd = zip64, comment = comment).bytes
            var start = zip.size - tailSize
            var rounds = 0
            while (true) {
                val result = EaZip.parseTail(zip.copyOfRange(start, zip.size), zip.size.toLong())
                if (result is EaZip.TailResult.Entries) {
                    assertMatches(zip, items, result.entries)
                    break
                }
                val offset = (result as EaZip.TailResult.NeedFrom).offset
                assertTrue(offset >= 0 && offset < start)
                start = offset.toInt()
                rounds++
                assertTrue(rounds < 20)
            }
        }
    }

    @Test
    fun parseTail_zipOutputStreamStoredArchive() {
        val files = linkedMapOf("dir/" to ByteArray(0), "dir/a.txt" to "alpha".toByteArray(), "dir/ü.bin" to ByteArray(1000) { it.toByte() })
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zout ->
                for ((name, data) in files) {
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().also { it.update(data) }.value
                    zout.putNextEntry(entry)
                    zout.write(data)
                    zout.closeEntry()
                }
            }
        }.toByteArray()
        val entries = parseAll(bytes)
        assertEquals(files.keys, entries.map { it.name }.toSet())
        for ((name, data) in files) {
            assertArrayEquals(data, content(bytes, entries.single { it.name == name }))
        }
    }

    @Test
    fun parseTail_notAZip_throws() {
        val junk = ByteArray(100) { 1 }
        try {
            EaZip.parseTail(junk, junk.size.toLong())
            throw AssertionError("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("end of central directory"))
        }
    }

    @Test
    fun isSafeName_rejectsEscapes() {
        assertTrue(EaZip.isSafeName("Game/bin/x.exe"))
        assertTrue(EaZip.isSafeName("Game\\bin\\x.exe"))
        assertTrue(EaZip.isSafeName("dir/"))
        assertTrue(EaZip.isSafeName("a..b/c"))
        assertFalse(EaZip.isSafeName(""))
        assertFalse(EaZip.isSafeName("/etc/passwd"))
        assertFalse(EaZip.isSafeName("\\Windows\\x.dll"))
        assertFalse(EaZip.isSafeName("C:/x.exe"))
        assertFalse(EaZip.isSafeName("c:\\x.exe"))
        assertFalse(EaZip.isSafeName("../x"))
        assertFalse(EaZip.isSafeName("Game/../../x"))
        assertFalse(EaZip.isSafeName("Game\\..\\x"))
        assertFalse(EaZip.isSafeName("Game/.."))
    }

    private val dipXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DiPManifest version="4.0">
          <buildMetaData>
            <gameVersion version="1.0.3.7"/>
          </buildMetaData>
          <contentIDs>
            <contentID>1234567</contentID>
            <contentID>7654321</contentID>
            <contentID>1234567</contentID>
          </contentIDs>
          <gameTitles>
            <gameTitle locale="en_US">Example &amp; Game</gameTitle>
          </gameTitles>
          <runtime>
            <launcher uid="trial">
              <filePath>[HKEY_LOCAL_MACHINE\SOFTWARE\Example\Install Dir]Game\Trial.exe</filePath>
              <trial>true</trial>
            </launcher>
            <launcher uid="main">
              <FilePath>[HKEY_LOCAL_MACHINE\SOFTWARE\Example\Install Dir]Game\Example &amp; Co.exe</FilePath>
              <parameters>-dx12</parameters>
              <trial>false</trial>
            </launcher>
          </runtime>
          <touchup>
            <filePath>/Core/ActivationUI.exe</filePath>
            <parameters>/lang {locale} /installLocation "{installLocation}"</parameters>
          </touchup>
        </DiPManifest>
    """.trimIndent()

    private fun assertSample(data: EaInstallerData?) {
        assertNotNull(data)
        data!!
        assertEquals("1.0.3.7", data.version)
        assertEquals(2, data.launchers.size)
        assertTrue(data.launchers[0].trial)
        assertEquals("", data.launchers[0].parameters)
        assertEquals("[HKEY_LOCAL_MACHINE\\SOFTWARE\\Example\\Install Dir]Game\\Example & Co.exe", data.executable)
        assertEquals("-dx12", data.launchers[1].parameters)
        assertEquals("Core/ActivationUI.exe", data.touchupPath)
        assertEquals("/lang {locale} /installLocation \"{installLocation}\"", data.touchupParameters)
        assertEquals(listOf("1234567", "7654321"), data.contentIds)
        assertEquals("Example & Game", data.gameTitle)
    }

    @Test
    fun installerData_parsesUtf8() {
        assertSample(EaInstallerData.parse(dipXml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun installerData_parsesUtf16WithAndWithoutBom() {
        assertSample(EaInstallerData.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + dipXml.toByteArray(Charsets.UTF_16LE)))
        assertSample(EaInstallerData.parse(dipXml.toByteArray(Charsets.UTF_16LE)))
        assertSample(EaInstallerData.parse(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + dipXml.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun installerData_rejectsOtherXml() {
        assertNull(EaInstallerData.parse("<game><executable/></game>".toByteArray()))
    }

    @Test
    fun relativePath_stripsRegistryPrefix() {
        assertEquals("Game\\x.exe", EaInstallerData.relativePath("[HKEY_LOCAL_MACHINE\\SOFTWARE\\EA\\Install Dir]Game\\x.exe"))
        assertEquals("Game\\x.exe", EaInstallerData.relativePath("[HKEY_LOCAL_MACHINE\\SOFTWARE\\EA\\Install Dir]\\Game/x.exe"))
        assertEquals("Game\\x.exe", EaInstallerData.relativePath("/Game/x.exe"))
        assertEquals("x.exe", EaInstallerData.relativePath("x.exe"))
    }

    @Test
    fun touchupArguments_substitutesLocaleAndLocation() {
        assertEquals(
            "/lang en_US /installLocation \"C:\\Games\\Example Game\"",
            EaInstallerData.touchupArguments("/lang {locale} /installLocation \"{installLocation}\"", "en_US", "C:/Games/Example Game/"),
        )
    }

    @Test
    fun touchupArguments_driveRootKeepsItsBackslash() {
        assertEquals(
            "install -locale en_US -installPath \"A:\\\\\" -autologging",
            EaInstallerData.touchupArguments("install -locale {locale} -installPath \"{installLocation}\" -autologging", "en_US", "A:"),
        )
    }

    private companion object {
        fun le(size: Int, value: Long): ByteArray {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
            return buf.array().copyOf(size)
        }

        fun le(size: Int, value: Int): ByteArray = le(size, value.toLong())

        fun rawDeflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            deflater.end()
            return out.toByteArray()
        }

        fun rawInflate(data: ByteArray, size: Int): ByteArray {
            val inflater = Inflater(true)
            inflater.setInput(data + byteArrayOf(0))
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inflater.finished()) n += inflater.inflate(out, n, size - n)
            inflater.end()
            return out.copyOf(n)
        }
    }
}
