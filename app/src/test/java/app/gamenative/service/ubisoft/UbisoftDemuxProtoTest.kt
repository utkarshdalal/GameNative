package app.gamenative.service.ubisoft

import app.gamenative.service.ubisoft.UbisoftDemuxProto.Writer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbisoftDemuxProtoTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun Writer.withNoise(): Writer = apply {
        bytes(90, byteArrayOf(1, 2))
        uint(91, 5)
    }

    /** Appends a fixed32 (field 101) and a fixed64 (field 102) field. */
    private fun noise(message: ByteArray): ByteArray = message + hex("ad0601020304") + hex("b1060102030405060708")

    @Test fun clientVersionPush() {
        assertArrayEquals(hex("1205220308cd68"), UbisoftDemuxProto.clientVersion(13389))
    }

    @Test fun authenticateRequest() {
        assertArrayEquals(
            hex("0a0e" + "0801" + "120a" + "0a031a0174" + "120163" + "2000"),
            UbisoftDemuxProto.authenticate(1, "t", "c"),
        )
    }

    @Test fun openConnectionRequest() {
        assertArrayEquals(hex("0a07" + "0802" + "1a03" + "120173"), UbisoftDemuxProto.openConnection(2, "s"))
    }

    @Test fun serviceDataIsFramedInsidePushData() {
        assertArrayEquals(
            hex("120b" + "0a09" + "0805" + "1205" + "00000001aa"),
            UbisoftDemuxProto.data(5, byteArrayOf(0xaa.toByte())),
        )
    }

    @Test fun framing() {
        assertArrayEquals(hex("00000002abcd"), UbisoftDemuxProto.frame(hex("abcd")))
        assertArrayEquals(hex("abcd"), UbisoftDemuxProto.unframe(hex("00000002abcd")))
        assertNull(UbisoftDemuxProto.unframe(hex("00000003abcd")))
    }

    @Test fun cloudsaveGetRequestCarriesNoToken() {
        val bytes = UbisoftDemuxProto.cloudsaveUrlRequest(1, null, 661, UbisoftDemuxProto.METHOD_GET, listOf(UbisoftDemuxProto.Item("1")))
        assertArrayEquals(hex("0a0e" + "0801" + "120a" + "109505" + "1802" + "2203" + "0a0131"), bytes)
    }

    @Test fun cloudsavePutRequestCarriesTokenSizeAndMd5() {
        val bytes = UbisoftDemuxProto.cloudsaveUrlRequest(
            1, "tk", 5, UbisoftDemuxProto.METHOD_PUT, listOf(UbisoftDemuxProto.Item("1", 3, "m")),
        )
        assertArrayEquals(
            hex("0a18" + "0801" + "1214" + "0a02746b" + "1005" + "1801" + "220a" + "0a0131" + "1205" + "0803" + "12016d"),
            bytes,
        )
    }

    @Test fun ownershipRequestsCarryTicketAndSession() {
        val init = UbisoftDemuxProto.Message.parse(UbisoftDemuxProto.ownershipInitialize(3, "tick", "sess")).message(1)!!
        assertEquals(3L, init.uint(1))
        assertEquals(1L, init.message(2)!!.uint(2))
        assertEquals(7L, init.message(2)!!.uint(3))
        assertEquals("tick", init.string(30))
        assertEquals("sess", init.string(31))
        val token = UbisoftDemuxProto.Message.parse(UbisoftDemuxProto.ownershipToken(4, 4_000_000_000L, "tick", "")).message(1)!!
        assertEquals(4_000_000_000L, token.message(28)!!.uint(1))
        assertFalse(token.has(31))
    }

    @Test fun authenticateReply() {
        val down = noise(Writer().message(1, Writer().uint(1, 1).message(2, Writer().uint(1, 1).withNoise())).toByteArray())
        val parsed = UbisoftDemuxProto.parseDown(down)
        assertEquals(1L, parsed.requestId)
        val auth = UbisoftDemuxProto.authenticateResult(parsed.response!!)
        assertTrue(auth.present && auth.success && !auth.expired && !auth.banned)

        val expired = Writer().message(1, Writer().uint(1, 1).message(2, Writer().uint(1, 0).uint(2, 1))).toByteArray()
        val e = UbisoftDemuxProto.authenticateResult(UbisoftDemuxProto.parseDown(expired).response!!)
        assertTrue(e.present && !e.success && e.expired)
    }

    @Test fun openConnectionReply() {
        val ok = Writer().message(1, Writer().uint(1, 2).message(3, Writer().uint(1, 42).uint(2, 1))).toByteArray()
        assertEquals(42L, UbisoftDemuxProto.openConnectionResult(UbisoftDemuxProto.parseDown(noise(ok)).response!!))
        val refused = Writer().message(1, Writer().uint(1, 2).message(3, Writer().uint(1, 42).uint(2, 0))).toByteArray()
        assertNull(UbisoftDemuxProto.openConnectionResult(UbisoftDemuxProto.parseDown(refused).response!!))
    }

    @Test fun pushDataAndClosedConnection() {
        val data = UbisoftDemuxProto.parseDown(UbisoftDemuxProto.data(7, hex("0102")))
        assertEquals(7L, data.dataConnection)
        assertArrayEquals(hex("0102"), data.data)
        assertNull(data.response)
        val closed = UbisoftDemuxProto.parseDown(Writer().message(2, Writer().message(2, Writer().uint(1, 7).uint(2, 3))).toByteArray())
        assertEquals(7L, closed.closedConnection)
        val outdated = UbisoftDemuxProto.parseDown(Writer().message(2, Writer().message(5, Writer())).toByteArray())
        assertTrue(outdated.outdated)
    }

    @Test fun urlReplyWithTwoUrls() {
        val url = Writer().uint(1, 1)
            .message(2, Writer().string(2, "https://saves.test/p/5/1.savegame?sig=a"))
            .message(2, Writer().string(1, "x-amz-acl: private\r\nX-Other: v").string(2, "https://saves.test/p/5/2.savegame?sig=b").withNoise())
        val down = noise(Writer().message(1, Writer().uint(1, 9).message(2, url)).toByteArray())
        assertNull(UbisoftDemuxProto.serviceResponse(down, 8))
        val rsp = UbisoftDemuxProto.serviceResponse(down, 9)!!
        val result = UbisoftDemuxProto.cloudsaveUrlResult(rsp)!!
        assertEquals(UbisoftDemuxProto.URL_STATUS_OK, result.status)
        assertEquals(2, result.requests.size)
        assertEquals("https://saves.test/p/5/1.savegame?sig=a", result.requests[0].url)
        assertTrue(result.requests[0].headers.isEmpty())
        assertEquals(listOf("x-amz-acl" to "private", "X-Other" to "v"), result.requests[1].headers)
    }

    @Test fun ownedGamesReply() {
        val yaml = "version: 2.0\nroot:\n  start_game:\n    steam:\n      offline_supported: yes\n      steam_app_id: 123450\n"
        val gameA = Writer().uint(1, 64).string(17, yaml).uint(19, 30).uint(20, 1).withNoise()
        val gameB = Writer().uint(1, 449).string(17, "root:\n  name: x\n").uint(19, 0)
        val gameC = Writer().uint(1, 7).string(17, "steam_app_id: \"98765\"")
        val init = Writer().uint(1, 1).message(2, Writer().message(1, gameA).message(1, gameB).message(1, gameC)).bytes(3, hex("0102"))
        val down = noise(Writer().message(1, Writer().uint(1, 1).message(2, init)).toByteArray())
        val games = UbisoftDemuxProto.ownedProducts(UbisoftDemuxProto.serviceResponse(down, 1)!!)!!
        assertEquals(
            listOf(
                UbisoftOwnedProduct(64, 30, 123450),
                UbisoftOwnedProduct(449, 449, null),
                UbisoftOwnedProduct(7, 7, 98765),
            ),
            games,
        )
        val failed = Writer().message(1, Writer().uint(1, 1).message(2, Writer().uint(1, 0))).toByteArray()
        assertNull(UbisoftDemuxProto.ownedProducts(UbisoftDemuxProto.serviceResponse(failed, 1)!!))
    }

    @Test fun ownershipTokenReply() {
        val ok = Writer().message(1, Writer().uint(1, 2).message(27, Writer().uint(1, 1).string(2, "tok").uint(3, 1_800_000_000))).toByteArray()
        assertEquals("tok", UbisoftDemuxProto.ownershipTokenResult(UbisoftDemuxProto.serviceResponse(ok, 2)!!))
        val refused = Writer().message(1, Writer().uint(1, 2).message(27, Writer().uint(1, 0))).toByteArray()
        assertNull(UbisoftDemuxProto.ownershipTokenResult(UbisoftDemuxProto.serviceResponse(refused, 2)!!))
    }
}
