package app.gamenative.html5.shim

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WebApiTicketFormat {
    // Steam returns the ticket at its real length (GetTicketForWebApiResponse_t.m_cubTicket); JavaSteam pads it to
    // the 2560-byte buffer with random bytes. the sections are length-prefixed: gc token, session header, ownership
    // ticket. anything that doesn't parse is returned as it came.
    fun trim(raw: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        repeat(3) {
            if (pos + 4 > raw.size) return raw
            val len = buf.getInt(pos)
            if (len < 0 || len > raw.size - pos - 4) return raw
            pos += 4 + len
        }
        return raw.copyOf(pos)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
