package app.gamenative.html5.shim

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WebApiTicketFormatTest {

    private fun section(bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).put(bytes).array()

    private val gc = ByteArray(20) { (it + 1).toByte() }
    private val session = ByteArray(24) { 0x55 }
    private val ownership = ByteArray(170) { (it % 7).toByte() }
    private val ticket = section(gc) + section(session) + section(ownership)

    @Test
    fun paddingAfterTheThreeSectionsIsDropped() {
        val padded = ticket + ByteArray(2560 - ticket.size) { 0x7f }
        assertArrayEquals(ticket, WebApiTicketFormat.trim(padded))
    }

    @Test
    fun anUnpaddedTicketIsUnchanged() {
        assertArrayEquals(ticket, WebApiTicketFormat.trim(ticket))
    }

    @Test
    fun aLengthRunningPastTheEndKeepsTheBytesAsTheyCame() {
        val broken = section(gc) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(9999).array() + session
        assertArrayEquals(broken, WebApiTicketFormat.trim(broken))
        val short = byteArrayOf(1, 0)
        assertArrayEquals(short, WebApiTicketFormat.trim(short))
    }

    @Test
    fun hexIsLowercaseTwoDigitsPerByte() {
        assertEquals("000fa0ff", WebApiTicketFormat.hex(byteArrayOf(0, 15, 0xa0.toByte(), 0xff.toByte())))
    }
}
