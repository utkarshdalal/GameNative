package app.gamenative.service.ubisoft

import app.gamenative.service.ubisoft.UbisoftDemuxProto.Writer
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UbisoftCloudApiTest {
    private val profile = "11111111-2222-3333-4444-555555555555"
    private val creds = UbisoftCredentials(
        ticket = "tick", sessionId = "sess", expiresAt = 0L, userId = profile, profileId = profile, nameOnPlatform = "player",
    )
    private val config = mutableMapOf(
        UbisoftHelperConfig.APP_ID to "app-1",
        UbisoftHelperConfig.HTTP_HEADERS to "X-App: {appId}",
        UbisoftHelperConfig.AUTH_TICKET to "t={ticket}",
        UbisoftHelperConfig.SESSION_HEADER to "X-Session",
        UbisoftHelperConfig.CLOUD_ENTITIES_URL to "https://api.test/v1/profiles/{profileId}/entities?name={name}",
        UbisoftHelperConfig.CLOUD_ENTITY_URL to "https://api.test/v1/profiles/entities/{entityId}",
        UbisoftHelperConfig.CLOUD_ENTITY_CREATE_URL to "https://api.test/v1/profiles/{profileId}/entities",
        UbisoftHelperConfig.CLOUD_ENTITY_NAME to "saveRecord_{productId}",
        UbisoftHelperConfig.CLOUD_ENTITY_TYPE to "saveType",
        UbisoftHelperConfig.CLOUD_SPACE_ID to "space-config",
        UbisoftHelperConfig.CLOUD_SAVE_HOST to "saves.test",
        UbisoftHelperConfig.CLOUD_SAVE_PATH to "/{profileId}/{productId}/{slot}.savegame",
        UbisoftHelperConfig.DEMUX_SERVICE_OWNERSHIP to "ownership_svc",
        UbisoftHelperConfig.DEMUX_SERVICE_CLOUDSAVE to "cloudsave_svc",
    )

    private class Recorded(val request: Request, val body: ByteArray?)

    private val recorded = mutableListOf<Recorded>()
    private var respond: (Request) -> Pair<Int, ByteArray> = { 404 to ByteArray(0) }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() }
        recorded += Recorded(request, body)
        val (code, bytes) = respond(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(bytes.toResponseBody(null)).build()
    }.build()

    private inner class FakeDemux : UbisoftDemuxConnection {
        val opened = mutableListOf<String>()
        val urlRequests = mutableListOf<UbisoftDemuxProto.Message>()
        val ownershipRequests = mutableListOf<UbisoftDemuxProto.Message>()
        var closed = false
        var tokenRefused = false
        var urlStatus: (UbisoftDemuxProto.Message) -> Long = { 1L }
        var signedMd5: (String?) -> String? = { it }

        /** One url per item; a PUT item's size and MD5 come back as its signed header block. */
        var urlReply: (UbisoftDemuxProto.Message) -> Writer = { req ->
            val uplay = req.uint(2)
            req.allMessages(4).fold(Writer().uint(1, urlStatus(req))) { w, item ->
                val r = Writer()
                item.message(2)?.let { args ->
                    r.string(1, "Content-Length: ${args.uint(1)}\r\nContent-MD5: ${signedMd5(args.string(2))}")
                }
                w.message(2, r.string(2, "https://saves.test/$profile/$uplay/${item.string(1)}.savegame?X-Amz-Signature=abc"))
            }
        }

        override fun openService(service: String): Long {
            opened += service
            return if (service == "cloudsave_svc") 11L else 12L
        }

        override fun exchange(connectionId: Long, message: ByteArray): ByteArray {
            val req = UbisoftDemuxProto.Message.parse(message).message(1)!!
            val rsp = Writer().uint(1, req.uint(1))
            if (connectionId == 11L) {
                val urlReq = req.message(2)!!
                urlRequests += urlReq
                rsp.message(2, urlReply(urlReq))
            } else {
                ownershipRequests += req
                if (req.has(2)) {
                    val game = Writer().uint(1, 64).uint(19, 30).string(17, "start_game:\n  steam:\n    steam_app_id: 4321\n")
                    rsp.message(2, Writer().uint(1, 1).message(2, Writer().message(1, game)))
                }
                if (req.has(28)) {
                    rsp.message(27, if (tokenRefused) Writer().uint(1, 0) else Writer().uint(1, 1).string(2, "own-token").uint(3, 900))
                }
            }
            return Writer().message(1, rsp).toByteArray()
        }

        override fun close() { closed = true }
    }

    private val demux = FakeDemux()
    private var demuxOpens = 0

    private fun session(maxInflated: Long = UbisoftCloudSession.MAX_INFLATED_BYTES) =
        UbisoftCloudSession({ config[it] }, http, creds, maxInflated) { demuxOpens++; demux }

    private fun zlib(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private val entityJson = """
        {"entityId":"ent-1","profileId":"$profile","spaceId":"space-1","type":"saveType","name":"saveRecord_77","tags":[],
         "obj":{"slots":[{"id":1,"filename":"1","hash":"0123456789abcdef0123456789abcdef","size":1500,
                          "lastModified":"2026-01-02T03:04:05.000Z"}],"timestamp":"1767323045","pc_name":"DESKTOP-TEST"},
         "revision":3}
    """.trimIndent()

    @Test fun entityIsReadFromTheWrappedListWithoutDemux() {
        respond = { 200 to """{"entities":[$entityJson]}""".toByteArray() }
        val e = session().entity(77)!!
        assertEquals("ent-1", e.entityId)
        assertEquals(3, e.revision)
        assertEquals("space-1", e.spaceId)
        assertEquals("1767323045", e.timestamp)
        assertEquals("DESKTOP-TEST", e.pcName)
        assertEquals(listOf(UbisoftCloudSlot(1, "1", "0123456789abcdef0123456789abcdef", 1500, "2026-01-02T03:04:05.000Z")), e.slots)
        val request = recorded.single().request
        assertEquals("/v1/profiles/$profile/entities", request.url.encodedPath)
        assertEquals("saveRecord_77", request.url.queryParameter("name"))
        assertEquals("t=tick", request.header("Authorization"))
        assertEquals("app-1", request.header("X-App"))
        assertEquals("sess", request.header("X-Session"))
        assertEquals(0, demuxOpens)
    }

    @Test fun emptyEntityListMeansNoCloudSave() {
        respond = { 200 to """{"entities":[]}""".toByteArray() }
        assertNull(session().entity(77))
    }

    @Test fun rejectedSessionCarriesTheStatus() {
        respond = { 401 to "{}".toByteArray() }
        val e = assertThrows(UbisoftCloudException::class.java) { session().entity(77) }
        assertEquals(401, e.httpStatus)
    }

    @Test fun signedUrlValidation() {
        val s = session()
        val good = "https://saves.test/$profile/77/1.savegame?X-Amz-Signature=abc"
        assertEquals("/$profile/77/1.savegame", s.validateSignedUrl(good, 77, "1").encodedPath)
        val bad = listOf(
            "http://saves.test/$profile/77/1.savegame?x=1",
            "https://elsewhere.test/$profile/77/1.savegame?x=1",
            "https://saves.test.evil.test/$profile/77/1.savegame",
            "https://saves.test:8443/$profile/77/1.savegame",
            "https://user@saves.test/$profile/77/1.savegame",
            "https://saves.test/99999999-2222-3333-4444-555555555555/77/1.savegame",
            "https://saves.test/$profile/78/1.savegame",
            "https://saves.test/$profile/77/2.savegame",
            "https://saves.test/$profile/77/1.savegame/extra",
        )
        for (url in bad) assertThrows(url, UbisoftCloudException::class.java) { s.validateSignedUrl(url, 77, "1") }
        assertThrows(UbisoftCloudException::class.java) { s.validateSignedUrl(good, 77, "../1") }
    }

    @Test fun aForeignUrlIsNeverFetched() {
        demux.urlReply = { Writer().uint(1, 1).message(2, Writer().string(2, "https://elsewhere.test/$profile/77/1.savegame")) }
        assertThrows(UbisoftCloudException::class.java) { session().download(77, listOf("1")) }
        assertTrue(recorded.isEmpty())
    }

    @Test fun downloadInflatesAndOmitsMissingObjects() {
        val save = ByteArray(5000) { (it % 7).toByte() }
        respond = { r -> if (r.url.encodedPath.endsWith("/1.savegame")) 200 to zlib(save) else 404 to "<Error/>".toByteArray() }
        val out = session().download(77, listOf("1", "2"))
        assertEquals(setOf("1"), out.keys)
        assertArrayEquals(save, out["1"])
        assertEquals(listOf("cloudsave_svc"), demux.opened)
        val urlReq = demux.urlRequests.single()
        assertFalse(urlReq.has(1))
        assertEquals(77L, urlReq.uint(2))
        assertEquals(UbisoftDemuxProto.METHOD_GET.toLong(), urlReq.uint(3))
        assertEquals(listOf("1", "2"), urlReq.allMessages(4).map { it.string(1) })
        assertTrue(recorded.all { it.request.method == "GET" && it.request.header("Authorization") == null })
    }

    @Test fun inflateBoundIsEnforced() {
        respond = { 200 to zlib(ByteArray(1000)) }
        assertThrows(UbisoftCloudException::class.java) { session(maxInflated = 999).download(77, listOf("1")) }
        assertEquals(1000, UbisoftCloudSession.inflate(zlib(ByteArray(1000)), 1000).size)
        val truncated = zlib(ByteArray(100_000) { it.toByte() }).let { it.copyOf(it.size / 2) }
        assertThrows(UbisoftCloudException::class.java) { UbisoftCloudSession.inflate(truncated, 1_000_000) }
        assertThrows(UbisoftCloudException::class.java) { UbisoftCloudSession.inflate("not zlib".toByteArray(), 1_000_000) }
    }

    @Test fun uploadSendsCompressedBodyWithItsMd5OnceAndNoToken() {
        respond = { 200 to ByteArray(0) }
        val save = "a save file ".repeat(200).toByteArray()
        session().upload(77, 64, mapOf("1" to save))

        assertEquals(listOf("cloudsave_svc"), demux.opened)
        assertTrue(demux.ownershipRequests.isEmpty())

        val put = recorded.single()
        assertEquals("PUT", put.request.method)
        assertNull(put.request.header("Authorization"))
        assertNull(put.request.header("Content-Length"))
        val body = put.body!!
        assertEquals(body.size.toLong(), put.request.body!!.contentLength())
        assertArrayEquals(save, InflaterInputStream(body.inputStream()).readBytes())
        val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body))
        assertEquals(listOf(md5), put.request.headers("Content-MD5"))

        val urlReq = demux.urlRequests.single()
        assertFalse(urlReq.has(1))
        assertEquals(77L, urlReq.uint(2))
        assertEquals(UbisoftDemuxProto.METHOD_PUT.toLong(), urlReq.uint(3))
        val args = urlReq.allMessages(4).single().message(2)!!
        assertEquals(body.size.toLong(), args.uint(1))
        assertEquals(md5, args.string(2))
    }

    @Test fun refusedUploadRetriesOnceWithAnOwnershipToken() {
        respond = { 200 to ByteArray(0) }
        demux.urlStatus = { req -> if (req.has(1)) 1L else 3L }
        session().upload(77, 64, mapOf("1" to byteArrayOf(1, 2, 3)))

        assertEquals(2, demux.urlRequests.size)
        assertFalse(demux.urlRequests[0].has(1))
        assertEquals("own-token", demux.urlRequests[1].string(1))
        assertEquals(64L, demux.ownershipRequests.last().message(28)!!.uint(1))
        assertEquals("tick", demux.ownershipRequests.last().string(30))
        assertEquals(1, recorded.size)
    }

    @Test fun refusedUploadWithoutProductAsksOwnershipForTheGame() {
        respond = { 200 to ByteArray(0) }
        demux.urlStatus = { req -> if (req.has(1)) 1L else 3L }
        session().upload(77, null, mapOf("1" to byteArrayOf(1, 2, 3)))
        assertEquals(77L, demux.ownershipRequests.last().message(28)!!.uint(1))
    }

    @Test fun refusedUploadWithoutATokenFailsWithTheFirstRefusal() {
        demux.urlStatus = { 3L }
        demux.tokenRefused = true
        val e = assertThrows(UbisoftCloudException::class.java) { session().upload(77, 64, mapOf("1" to byteArrayOf(1))) }
        assertEquals("cloud save urls refused (status 3)", e.message)
        assertEquals(1, demux.urlRequests.size)
        assertTrue(recorded.isEmpty())
    }

    @Test fun signedMd5MismatchStopsBeforeAnyTransfer() {
        demux.signedMd5 = { "AAAAAAAAAAAAAAAAAAAAAA==" }
        assertThrows(UbisoftCloudException::class.java) { session().upload(77, 64, mapOf("1" to byteArrayOf(1, 2, 3))) }
        assertTrue(recorded.isEmpty())
    }

    @Test fun failedUploadCarriesTheStatus() {
        respond = { 403 to ByteArray(0) }
        val e = assertThrows(UbisoftCloudException::class.java) { session().upload(77, null, mapOf("1" to byteArrayOf(1))) }
        assertEquals(403, e.httpStatus)
    }

    private fun slots() = listOf(UbisoftCloudSlot(1, "1", "fedcba9876543210fedcba9876543210", 2000, "2026-02-03T04:05:06.000Z"))

    @Test fun replaceSendsRevisionAndTheWholeRecord() {
        val previous = UbisoftCloudSession.parseEntity(JSONObject(entityJson))
        respond = { 200 to entityJson.replace("\"revision\":3", "\"revision\":4").toByteArray() }
        val stored = session().writeEntity(77, previous, slots(), 1_770_000_000L, "HANDHELD")
        assertEquals(4, stored.revision)

        val put = recorded.single()
        assertEquals("PUT", put.request.method)
        assertEquals("/v1/profiles/entities/ent-1", put.request.url.encodedPath)
        assertEquals("t=tick", put.request.header("Authorization"))
        val body = JSONObject(String(put.body!!))
        assertEquals(3, body.getInt("revision"))
        assertEquals("saveRecord_77", body.getString("name"))
        assertEquals("saveType", body.getString("type"))
        assertEquals("space-1", body.getString("spaceId"))
        assertEquals(0, body.getJSONArray("tags").length())
        val obj = body.getJSONObject("obj")
        assertEquals("1770000000", obj.getString("timestamp"))
        assertEquals("HANDHELD", obj.getString("pc_name"))
        val slot = obj.getJSONArray("slots").getJSONObject(0)
        assertEquals(1, slot.getInt("id"))
        assertEquals("1", slot.getString("filename"))
        assertEquals("fedcba9876543210fedcba9876543210", slot.getString("hash"))
        assertEquals(2000L, slot.getLong("size"))
        assertEquals("2026-02-03T04:05:06.000Z", slot.getString("lastModified"))
        assertEquals(0, demuxOpens)
    }

    @Test fun createPostsWithoutRevisionAndRereadsWhenTheAnswerIsNoRecord() {
        respond = { r -> if (r.method == "POST") 201 to "{}".toByteArray() else 200 to """{"entities":[$entityJson]}""".toByteArray() }
        val stored = session().writeEntity(77, null, slots(), 1_770_000_000L, "HANDHELD")
        assertEquals("ent-1", stored.entityId)
        val post = recorded.first()
        assertEquals("POST", post.request.method)
        assertEquals("/v1/profiles/$profile/entities", post.request.url.encodedPath)
        val body = JSONObject(String(post.body!!))
        assertFalse(body.has("revision"))
        assertEquals("space-config", body.getString("spaceId"))
        assertEquals("saveRecord_77", body.getString("name"))
        assertEquals("GET", recorded[1].request.method)
    }

    @Test fun createNeedsItsEndpoint() {
        config.remove(UbisoftHelperConfig.CLOUD_ENTITY_CREATE_URL)
        val e = assertThrows(UbisoftCloudException::class.java) { session().writeEntity(77, null, slots(), 1L, "x") }
        assertEquals("cloud save record cannot be created", e.message)
        assertTrue(recorded.isEmpty())
    }

    @Test fun revisionConflictCarriesTheStatus() {
        val previous = UbisoftCloudSession.parseEntity(JSONObject(entityJson))
        respond = { 409 to "{}".toByteArray() }
        val e = assertThrows(UbisoftCloudException::class.java) { session().writeEntity(77, previous, slots(), 1L, "x") }
        assertEquals(409, e.httpStatus)
    }

    @Test fun ownedProductsInitializesOnceAndCloseDropsDemux() {
        val s = session()
        assertEquals(listOf(UbisoftOwnedProduct(64, 30, 4321)), s.ownedProducts())
        s.ownedProducts()
        assertEquals(1, demux.ownershipRequests.size)
        s.close()
        assertTrue(demux.closed)
        assertThrows(UbisoftCloudException::class.java) { s.download(77, listOf("1")) }
    }

    @Test fun slotNamesOutsideTheSafeSetAreRefused() {
        assertThrows(UbisoftCloudException::class.java) { session().download(77, listOf("a/b")) }
        assertThrows(UbisoftCloudException::class.java) { session().download(77, listOf("1?x")) }
        assertEquals(0, demuxOpens)
    }
}
