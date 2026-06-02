package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// — RmmvSaveMapper contract tests.
// validates the strategy-B passthrough: chromium localStorage LevelDB ↔ .rpgsave files.
// synthetic DBs exercise the framing + key-mapping code without needing on-device fixtures.
class RmmvSaveMapperTest {

    private lateinit var tmpRoot: File
    private val origin = "https_game-termina-608a_0"

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("rmmv-mapper-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    // --- writeLocalStorageToFiles ---

    @Test
    fun writeLocalStorageToFiles_fromSyntheticDb_producesMatchingFiles() {
        val src = File(tmpRoot, "ls-src").also { it.mkdirs() }
        val saveDir = File(tmpRoot, "saves")

        val rpgConfig = "N4Igzg..LZString..Config".toByteArray(Charsets.US_ASCII)
        val rpgFile1 = ByteArray(2048) { (it and 0x7F).toByte() }
        val rpgFile2 = ByteArray(512) { 0x42 }
        val rpgGlobal = "GlobalPayloadBytes".toByteArray(Charsets.US_ASCII)

        openDb(src).use { db ->
            db.put(makeKey("RPG Config"), frameValue(rpgConfig))
            db.put(makeKey("RPG File1"), frameValue(rpgFile1))
            db.put(makeKey("RPG File2"), frameValue(rpgFile2))
            db.put(makeKey("RPG Global"), frameValue(rpgGlobal))
        }

        RmmvSaveMapper.writeLocalStorageToFiles(src, origin, saveDir)

        assertTrue("config.rpgsave exists", File(saveDir, "config.rpgsave").isFile)
        assertArrayEquals(rpgConfig, File(saveDir, "config.rpgsave").readBytes())
        assertArrayEquals(rpgFile1, File(saveDir, "file1.rpgsave").readBytes())
        assertArrayEquals(rpgFile2, File(saveDir, "file2.rpgsave").readBytes())
        assertArrayEquals(rpgGlobal, File(saveDir, "global.rpgsave").readBytes())
    }

    @Test
    fun writeLocalStorageToFiles_ignoresNonRpgKeys() {
        val src = File(tmpRoot, "ls-src").also { it.mkdirs() }
        val saveDir = File(tmpRoot, "saves")

        val rpgConfig = "ConfigPayload".toByteArray(Charsets.US_ASCII)

        openDb(src).use { db ->
            db.put(makeKey("RPG Config"), frameValue(rpgConfig))
            db.put(makeKey("OtherAppData"), frameValue("junk".toByteArray()))
            db.put(makeKey("com.example.metadata"), frameValue("moar junk".toByteArray()))
            // metadata-style key — chromium uses META: prefix; must not match our filter.
            db.put("META:$origin".toByteArray(Charsets.US_ASCII), byteArrayOf(0x01, 0x02))
        }

        RmmvSaveMapper.writeLocalStorageToFiles(src, origin, saveDir)

        val files = saveDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(setOf("config.rpgsave"), files)
    }

    @Test
    fun writeLocalStorageToFiles_missingSrc_throwsPathMissing() {
        val src = File(tmpRoot, "nonexistent")
        val saveDir = File(tmpRoot, "saves")
        try {
            RmmvSaveMapper.writeLocalStorageToFiles(src, origin, saveDir)
            fail("expected PathMissing")
        } catch (e: SaveSyncFailure.PathMissing) {
            assertEquals(src.absolutePath, e.path)
        }
    }

    // --- readFilesToLocalStorage ---

    @Test
    fun readFilesToLocalStorage_roundTrip_preservesBytes() {
        val saveDir = File(tmpRoot, "saves").also { it.mkdirs() }
        val db = File(tmpRoot, "ls-db")

        val payloads = mapOf(
            "config.rpgsave" to "ConfigBytes12345".toByteArray(Charsets.US_ASCII),
            "file1.rpgsave" to ByteArray(4096) { (it and 0xFF).toByte() },
            "file3.rpgsave" to "File3".toByteArray(Charsets.US_ASCII),
            "global.rpgsave" to "GlobalZZZ".toByteArray(Charsets.US_ASCII),
        )
        for ((name, bytes) in payloads) File(saveDir, name).writeBytes(bytes)

        RmmvSaveMapper.readFilesToLocalStorage(saveDir, db, origin)

        // inspect LevelDB — expect 4 matching keys + each value framed `01 <payload>`.
        val seenKeys = mutableMapOf<String, ByteArray>()
        openDb(db, readOnly = true).use { levelDb ->
            levelDb.iterator().use { iter ->
                iter.seekToFirst()
                val prefix = makeKeyPrefix()
                while (iter.hasNext()) {
                    val e = iter.next()
                    if (!e.key.startsWith(prefix)) continue
                    val keyText = decodeUtf16Le(e.key.copyOfRange(prefix.size, e.key.size))
                    seenKeys[keyText] = e.value
                }
            }
        }
        assertEquals(
            setOf("RPG Config", "RPG File1", "RPG File3", "RPG Global"),
            seenKeys.keys,
        )
        for ((storageKey, raw) in seenKeys) {
            assertEquals("framing byte", 0x01.toByte(), raw[0])
            val payload = raw.copyOfRange(1, raw.size)
            val expected = when (storageKey) {
                "RPG Config" -> payloads["config.rpgsave"]
                "RPG File1" -> payloads["file1.rpgsave"]
                "RPG File3" -> payloads["file3.rpgsave"]
                "RPG Global" -> payloads["global.rpgsave"]
                else -> null
            }
            assertArrayEquals("payload for $storageKey", expected, payload)
        }
    }

    @Test
    fun readFilesToLocalStorage_skipsEmptyFile() {
        val saveDir = File(tmpRoot, "saves").also { it.mkdirs() }
        val db = File(tmpRoot, "ls-db")
        File(saveDir, "file1.rpgsave").writeBytes(ByteArray(0))
        File(saveDir, "config.rpgsave").writeBytes("Config".toByteArray())

        RmmvSaveMapper.readFilesToLocalStorage(saveDir, db, origin)

        val keys = mutableSetOf<String>()
        openDb(db, readOnly = true).use { levelDb ->
            levelDb.iterator().use { iter ->
                iter.seekToFirst()
                val prefix = makeKeyPrefix()
                while (iter.hasNext()) {
                    val e = iter.next()
                    if (!e.key.startsWith(prefix)) continue
                    keys.add(decodeUtf16Le(e.key.copyOfRange(prefix.size, e.key.size)))
                }
            }
        }
        // file1 skipped (empty), config present.
        assertEquals(setOf("RPG Config"), keys)
    }

    @Test
    fun readFilesToLocalStorage_ignoresNonRpgsaveFiles() {
        val saveDir = File(tmpRoot, "saves").also { it.mkdirs() }
        val db = File(tmpRoot, "ls-db")
        File(saveDir, "config.rpgsave").writeBytes("C".toByteArray())
        File(saveDir, "notes.txt").writeBytes("junk".toByteArray())
        File(saveDir, "random.rpgsave").writeBytes("X".toByteArray()) // .rpgsave but unknown pattern

        RmmvSaveMapper.readFilesToLocalStorage(saveDir, db, origin)

        val keys = mutableSetOf<String>()
        openDb(db, readOnly = true).use { levelDb ->
            levelDb.iterator().use { iter ->
                iter.seekToFirst()
                val prefix = makeKeyPrefix()
                while (iter.hasNext()) {
                    val e = iter.next()
                    if (!e.key.startsWith(prefix)) continue
                    keys.add(decodeUtf16Le(e.key.copyOfRange(prefix.size, e.key.size)))
                }
            }
        }
        assertEquals(setOf("RPG Config"), keys)
    }

    // --- round-trip + mapping ---

    @Test
    fun roundTrip_filesToDbToFiles_preservesBytes() {
        val saveDirA = File(tmpRoot, "saves-a").also { it.mkdirs() }
        val db = File(tmpRoot, "ls-db")
        val saveDirB = File(tmpRoot, "saves-b")

        val configBytes = "ConfigPayload".toByteArray(Charsets.US_ASCII)
        val file1Bytes = ByteArray(1000) { (it and 0xFF).toByte() }
        File(saveDirA, "config.rpgsave").writeBytes(configBytes)
        File(saveDirA, "file1.rpgsave").writeBytes(file1Bytes)

        RmmvSaveMapper.readFilesToLocalStorage(saveDirA, db, origin)
        RmmvSaveMapper.writeLocalStorageToFiles(db, origin, saveDirB)

        assertArrayEquals(configBytes, File(saveDirB, "config.rpgsave").readBytes())
        assertArrayEquals(file1Bytes, File(saveDirB, "file1.rpgsave").readBytes())
    }

    @Test
    fun storageKeyForFilename_mapsCommonCases() {
        assertEquals("RPG Config", RmmvSaveMapper.storageKeyForFilename("config.rpgsave"))
        assertEquals("RPG Global", RmmvSaveMapper.storageKeyForFilename("global.rpgsave"))
        assertEquals("RPG File1", RmmvSaveMapper.storageKeyForFilename("file1.rpgsave"))
        assertEquals("RPG File99", RmmvSaveMapper.storageKeyForFilename("file99.rpgsave"))
        assertEquals("RPG Save1", RmmvSaveMapper.storageKeyForFilename("file1save.rpgsave"))
        assertNull(RmmvSaveMapper.storageKeyForFilename("random.rpgsave"))
        assertNull(RmmvSaveMapper.storageKeyForFilename("config.txt"))
        assertNull(RmmvSaveMapper.storageKeyForFilename(""))
    }

    @Test
    fun filenameForStorageKey_mapsCommonCases() {
        assertEquals("config.rpgsave", RmmvSaveMapper.filenameForStorageKey("RPG Config"))
        assertEquals("global.rpgsave", RmmvSaveMapper.filenameForStorageKey("RPG Global"))
        assertEquals("file1.rpgsave", RmmvSaveMapper.filenameForStorageKey("RPG File1"))
        assertEquals("file99.rpgsave", RmmvSaveMapper.filenameForStorageKey("RPG File99"))
        assertEquals("file2save.rpgsave", RmmvSaveMapper.filenameForStorageKey("RPG Save2"))
        assertNull(RmmvSaveMapper.filenameForStorageKey("OtherKey"))
    }

    // --- failure classification ---

    @Test
    fun classifyFailure_lockMessage_mapsToLockContention() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = RuntimeException("Unable to acquire lock")
        val classified = RmmvSaveMapper.classifyFailure(fake, src, dst)
        assertTrue(classified is SaveSyncFailure.LockContention)
    }

    @Test
    fun classifyFailure_corruption_mapsToCorruption() {
        val src = File(tmpRoot, "src")
        val dst = File(tmpRoot, "dst")
        val fake = RuntimeException("detected corruption in MANIFEST")
        val classified = RmmvSaveMapper.classifyFailure(fake, src, dst)
        assertTrue(classified is SaveSyncFailure.Corruption)
    }

    // --- helpers ---

    // byte key format: `_<origin>\x00` + utf-16-LE storage key
    private fun makeKeyPrefix(): ByteArray {
        val originBytes = origin.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + originBytes.size + 1)
        out[0] = '_'.code.toByte()
        System.arraycopy(originBytes, 0, out, 1, originBytes.size)
        out[out.size - 1] = 0x00
        return out
    }

    private fun makeKey(storageKey: String): ByteArray {
        val prefix = makeKeyPrefix()
        val chars = storageKey.toCharArray()
        val utf16 = ByteArray(chars.size * 2)
        for ((i, c) in chars.withIndex()) {
            val code = c.code
            utf16[i * 2] = (code and 0xFF).toByte()
            utf16[i * 2 + 1] = ((code ushr 8) and 0xFF).toByte()
        }
        return prefix + utf16
    }

    private fun frameValue(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = 0x01
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    private fun decodeUtf16Le(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size / 2)
        var i = 0
        while (i < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun openDb(dir: File, readOnly: Boolean = false): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(!readOnly)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
        }
        return Iq80DBFactory.factory.open(dir, options)
    }
}
