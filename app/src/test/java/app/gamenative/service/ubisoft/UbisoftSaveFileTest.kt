package app.gamenative.service.ubisoft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbisoftSaveFileTest {
    private val hash = "0123456789abcdef0123456789abcdef"

    private fun save(payload: String, stamp: String? = null): ByteArray {
        val header = ByteArray(40)
        header[0] = 0x24
        header[1] = 0x02
        stamp?.toByteArray(Charsets.ISO_8859_1)?.copyInto(header, 8)
        return header + payload.toByteArray()
    }

    @Test fun slotNamesAreNumberedSaves() {
        assertEquals(1, UbisoftSaveFile.slotOf("1.save"))
        assertEquals(42, UbisoftSaveFile.slotOf("42.save"))
        assertNull(UbisoftSaveFile.slotOf("0.save"))
        assertNull(UbisoftSaveFile.slotOf("01.save"))
        assertNull(UbisoftSaveFile.slotOf("1.save.upload"))
        assertNull(UbisoftSaveFile.slotOf("1.save.delete"))
        assertNull(UbisoftSaveFile.slotOf(".1.save.part"))
        assertNull(UbisoftSaveFile.slotOf("profile.save"))
        assertNull(UbisoftSaveFile.slotOf("1.SAVE"))
        assertEquals("7.save", UbisoftSaveFile.fileName(7))
    }

    @Test fun contentKeyIgnoresTheStampedHash() {
        val plain = save("payload")
        val stamped = save("payload", hash)
        assertEquals(UbisoftSaveFile.contentKey(plain), UbisoftSaveFile.contentKey(stamped))
        assertNotEquals(UbisoftSaveFile.contentKey(plain), UbisoftSaveFile.contentKey(save("payload2")))
        val headerChange = plain.copyOf().also { it[2] = 1 }
        assertNotEquals(UbisoftSaveFile.contentKey(plain), UbisoftSaveFile.contentKey(headerChange))
        assertEquals(32, UbisoftSaveFile.contentKey(plain).length)
    }

    @Test fun stampRoundTripsAndLeavesTheInputAlone() {
        val plain = save("payload")
        assertNull(UbisoftSaveFile.stampedHash(plain))
        val stamped = UbisoftSaveFile.stamp(plain, hash)
        assertEquals(hash, UbisoftSaveFile.stampedHash(stamped))
        assertNull(UbisoftSaveFile.stampedHash(plain))
        assertEquals(plain.size, stamped.size)
        assertArrayEquals(plain.copyOfRange(40, plain.size), stamped.copyOfRange(40, stamped.size))
        assertEquals(UbisoftSaveFile.contentKey(plain), UbisoftSaveFile.contentKey(stamped))
    }

    @Test fun nonHexStampIsNotAHash() {
        assertNull(UbisoftSaveFile.stampedHash(save("x", "zz23456789abcdef0123456789abcdef")))
        assertEquals(hash, UbisoftSaveFile.stampedHash(save("x", hash.uppercase())))
    }

    @Test fun shortFilesHashWholeAndCannotBeStamped() {
        val short = ByteArray(20) { it.toByte() }
        assertNull(UbisoftSaveFile.stampedHash(short))
        assertFalse(UbisoftSaveFile.canStamp(short))
        assertTrue(UbisoftSaveFile.canStamp(ByteArray(40)))
        assertNotEquals(UbisoftSaveFile.contentKey(short), UbisoftSaveFile.contentKey(ByteArray(20)))
        assertTrue(runCatching { UbisoftSaveFile.stamp(short, hash) }.isFailure)
        assertTrue(runCatching { UbisoftSaveFile.stamp(save("x"), "nothex") }.isFailure)
    }

    @Test fun shortHeaderGenerationUsesTheSameHashBytes() {
        val header = ByteArray(40)
        header[0] = 0x24
        val bytes = header + "payload right after the hash".toByteArray()
        assertNull(UbisoftSaveFile.stampedHash(bytes))
        val stamped = UbisoftSaveFile.stamp(bytes, hash)
        assertEquals(hash, UbisoftSaveFile.stampedHash(stamped))
        assertEquals(0x24.toByte(), stamped[0])
        assertEquals(UbisoftSaveFile.contentKey(bytes), UbisoftSaveFile.contentKey(stamped))
        assertTrue(UbisoftSaveFile.canStamp(header))
    }

    @Test fun newHashIsTheContentKey() {
        val bytes = save("payload", hash)
        assertEquals(UbisoftSaveFile.contentKey(bytes), UbisoftSaveFile.newHash(bytes))
    }
}
