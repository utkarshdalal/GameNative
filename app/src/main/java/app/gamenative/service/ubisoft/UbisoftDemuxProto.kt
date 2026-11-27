package app.gamenative.service.ubisoft

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream

/**
 * Messages of the launcher's demux protocol and of the ownership and cloud save services carried
 * through it. Pure building and parsing; unknown fields are skipped.
 */
object UbisoftDemuxProto {
    const val METHOD_PUT = 1
    const val METHOD_GET = 2
    const val URL_STATUS_OK = 1L

    /** Fields in the order they are added. */
    class Writer {
        private val bytes = ByteArrayOutputStream()
        private val out = CodedOutputStream.newInstance(bytes)

        fun uint(field: Int, value: Long): Writer = apply { out.writeUInt64(field, value) }
        fun flag(field: Int, value: Boolean): Writer = uint(field, if (value) 1L else 0L)
        fun string(field: Int, value: String): Writer = apply { out.writeString(field, value) }
        fun bytes(field: Int, value: ByteArray): Writer = apply { out.writeByteArray(field, value) }
        fun message(field: Int, value: Writer): Writer = bytes(field, value.toByteArray())

        fun toByteArray(): ByteArray {
            out.flush()
            return bytes.toByteArray()
        }
    }

    class Field(val number: Int, val wireType: Int, val varint: Long, val data: ByteArray?)

    /** A parsed message. A field read as the wrong wire type counts as absent. */
    class Message(val fields: List<Field>) {
        fun has(number: Int) = fields.any { it.number == number }
        fun uint(number: Int): Long =
            fields.firstOrNull { it.number == number && it.wireType == WireFormat.WIRETYPE_VARINT }?.varint ?: 0L
        fun bytes(number: Int): ByteArray? =
            fields.firstOrNull { it.number == number && it.data != null }?.data
        fun string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)
        fun message(number: Int): Message? = bytes(number)?.let { parse(it) }
        fun allBytes(number: Int): List<ByteArray> = fields.filter { it.number == number }.mapNotNull { it.data }
        fun allMessages(number: Int): List<Message> = allBytes(number).map { parse(it) }

        /** A repeated integer, packed or not. */
        fun uints(number: Int): List<Long> = fields.filter { it.number == number }.flatMap { f ->
            if (f.data == null) {
                listOf(f.varint)
            } else {
                val input = CodedInputStream.newInstance(f.data)
                buildList { while (!input.isAtEnd) add(input.readUInt64()) }
            }
        }

        companion object {
            fun parse(data: ByteArray): Message {
                val input = CodedInputStream.newInstance(data)
                val fields = ArrayList<Field>()
                while (true) {
                    val tag = input.readTag()
                    if (tag == 0) break
                    val number = WireFormat.getTagFieldNumber(tag)
                    when (WireFormat.getTagWireType(tag)) {
                        WireFormat.WIRETYPE_VARINT -> fields += Field(number, WireFormat.WIRETYPE_VARINT, input.readUInt64(), null)
                        WireFormat.WIRETYPE_LENGTH_DELIMITED ->
                            fields += Field(number, WireFormat.WIRETYPE_LENGTH_DELIMITED, 0L, input.readByteArray())
                        else -> input.skipField(tag)
                    }
                }
                return Message(fields)
            }
        }
    }

    fun frame(message: ByteArray): ByteArray {
        val n = message.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + message
    }

    fun frameLength(header: ByteArray): Long =
        ((header[0].toLong() and 0xff) shl 24) or ((header[1].toLong() and 0xff) shl 16) or
            ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff)

    /** The message inside a length prefix that covers exactly the rest, else null. */
    fun unframe(framed: ByteArray): ByteArray? {
        if (framed.size < 4) return null
        if (frameLength(framed) != (framed.size - 4).toLong()) return null
        return framed.copyOfRange(4, framed.size)
    }

    // ---- demux ----

    fun clientVersion(version: Long): ByteArray =
        Writer().message(2, Writer().message(4, Writer().uint(1, version))).toByteArray()

    fun authenticate(requestId: Long, ticket: String, clientId: String): ByteArray {
        val auth = Writer().message(1, Writer().string(3, ticket)).string(2, clientId).flag(4, false)
        return Writer().message(1, Writer().uint(1, requestId).message(2, auth)).toByteArray()
    }

    fun openConnection(requestId: Long, service: String): ByteArray =
        Writer().message(1, Writer().uint(1, requestId).message(3, Writer().string(2, service))).toByteArray()

    /** A service message as push data; the service message carries its own length prefix. */
    fun data(connectionId: Long, serviceMessage: ByteArray): ByteArray =
        Writer().message(2, Writer().message(1, Writer().uint(1, connectionId).bytes(2, frame(serviceMessage)))).toByteArray()

    class Down(
        val response: Message?,
        val requestId: Long,
        val dataConnection: Long?,
        val data: ByteArray?,
        val closedConnection: Long?,
        val outdated: Boolean,
    )

    fun parseDown(message: ByteArray): Down {
        val m = Message.parse(message)
        val response = m.message(1)
        val push = m.message(2)
        val dataMessage = push?.message(1)
        return Down(
            response = response,
            requestId = response?.uint(1) ?: 0L,
            dataConnection = dataMessage?.uint(1),
            data = dataMessage?.bytes(2)?.let { unframe(it) },
            closedConnection = push?.message(2)?.uint(1),
            outdated = push?.has(5) == true,
        )
    }

    class AuthResult(val present: Boolean, val success: Boolean, val expired: Boolean, val banned: Boolean)

    fun authenticateResult(response: Message): AuthResult {
        val r = response.message(2) ?: return AuthResult(false, false, false, false)
        return AuthResult(true, r.uint(1) != 0L, r.uint(2) != 0L, r.uint(3) != 0L)
    }

    /** The server-assigned connection id, or null when the service was not opened. */
    fun openConnectionResult(response: Message): Long? {
        val r = response.message(3) ?: return null
        return if (r.uint(2) != 0L) r.uint(1) else null
    }

    // ---- ownership service ----

    private fun ownershipRequest(request: Writer, ticket: String, sessionId: String): ByteArray {
        request.string(30, ticket)
        if (sessionId.isNotEmpty()) request.string(31, sessionId)
        return Writer().message(1, request).toByteArray()
    }

    fun ownershipInitialize(requestId: Long, ticket: String, sessionId: String): ByteArray =
        ownershipRequest(Writer().uint(1, requestId).message(2, Writer().flag(2, true).uint(3, 7)), ticket, sessionId)

    fun ownershipToken(requestId: Long, productId: Long, ticket: String, sessionId: String): ByteArray =
        ownershipRequest(Writer().uint(1, requestId).message(28, Writer().uint(1, productId)), ticket, sessionId)

    /** The response of a service Downstream when it answers [requestId]. */
    fun serviceResponse(down: ByteArray, requestId: Long): Message? {
        val rsp = Message.parse(down).message(1) ?: return null
        return rsp.takeIf { it.uint(1) == requestId }
    }

    /** Owned games of an initialize response, or null when the response has none or failed. */
    fun ownedProducts(response: Message): List<UbisoftOwnedProduct>? {
        val r = response.message(2) ?: return null
        if (r.uint(1) == 0L) return null
        val games = r.message(2) ?: return emptyList()
        return games.allMessages(1).map { g ->
            val productId = g.uint(1)
            UbisoftOwnedProduct(
                productId = productId,
                uplayId = g.uint(19).takeIf { it != 0L } ?: productId,
                steamAppId = steamAppId(g.string(17)),
            )
        }
    }

    private val STEAM_APP_ID = Regex("steam_app_id:\\s*[\"']?(\\d+)")

    fun steamAppId(configuration: String?): Int? =
        configuration?.let { STEAM_APP_ID.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** Every owned product of an initialize response with what the store needs, or null when it failed. */
    fun ownedGames(response: Message): List<UbisoftOwnedGame>? {
        val r = response.message(2) ?: return null
        if (r.uint(1) == 0L) return null
        val games = r.message(2) ?: return emptyList()
        return games.allMessages(1).map { g ->
            val productId = g.uint(1)
            UbisoftOwnedGame(
                productId = productId,
                uplayId = g.uint(19).takeIf { it != 0L } ?: productId,
                owned = g.uint(20) != 0L,
                productType = g.uint(8).toInt(),
                state = g.uint(13).toInt(),
                configuration = g.string(17).orEmpty(),
                latestManifest = g.string(21).orEmpty(),
                associations = g.uints(16),
                suspended = g.uint(34) != 0L || g.uint(33) == 2L || g.uint(37) != 0L,
                spaceId = g.string(30).orEmpty(),
                appId = g.string(31).orEmpty(),
            )
        }
    }

    fun productConfigRequest(requestId: Long, productId: Long, ticket: String, sessionId: String): ByteArray =
        ownershipRequest(Writer().uint(1, requestId).message(9, Writer().uint(1, productId)), ticket, sessionId)

    /** The configuration of a product config response; null unless the result is Success (1). */
    fun productConfigResult(response: Message): String? {
        val r = response.message(9) ?: return null
        if (r.uint(1) != 1L) return null
        return r.string(3)
    }

    class OwnershipToken(val token: String, val expiresAtSeconds: Long)

    fun ownershipTokenWithExpiry(response: Message): OwnershipToken? {
        val token = ownershipTokenResult(response) ?: return null
        return OwnershipToken(token, response.message(27)?.uint(3) ?: 0L)
    }

    /** The token of an ownership token response, or null when it was refused. */
    fun ownershipTokenResult(response: Message): String? {
        val r = response.message(27) ?: return null
        if (r.uint(1) == 0L) return null
        return r.string(2)?.takeIf { it.isNotEmpty() }
    }

    // ---- cloud save service ----

    class Item(val name: String, val writeLength: Long? = null, val md5Base64: String? = null)

    fun cloudsaveUrlRequest(requestId: Long, ownershipToken: String?, uplayId: Long, method: Int, items: List<Item>): ByteArray {
        val req = Writer()
        if (ownershipToken != null) req.string(1, ownershipToken)
        req.uint(2, uplayId).uint(3, method.toLong())
        for (item in items) {
            val w = Writer().string(1, item.name)
            if (item.writeLength != null || item.md5Base64 != null) {
                val args = Writer()
                item.writeLength?.let { args.uint(1, it) }
                item.md5Base64?.let { args.string(2, it) }
                w.message(2, args)
            }
            req.message(4, w)
        }
        return Writer().message(1, Writer().uint(1, requestId).message(2, req)).toByteArray()
    }

    class SignedRequest(val headers: List<Pair<String, String>>, val url: String)

    class UrlResult(val status: Long, val requests: List<SignedRequest>)

    fun cloudsaveUrlResult(response: Message): UrlResult? {
        val r = response.message(2) ?: return null
        val requests = r.allMessages(2).map { req ->
            SignedRequest(
                headers = req.allBytes(1).flatMap { headerBlock(it.toString(Charsets.UTF_8)) },
                url = req.string(2).orEmpty(),
            )
        }
        return UrlResult(r.uint(1), requests)
    }

    /** `Name: value` lines. */
    fun headerBlock(text: String): List<Pair<String, String>> =
        text.split('\n').mapNotNull { line ->
            val name = line.substringBefore(':', "").trim()
            if (name.isEmpty()) null else name to line.substringAfter(':').trim()
        }

    // ---- download service ----

    fun downloadInitialize(requestId: Long, ownershipToken: String): ByteArray =
        Writer().message(1, Writer().uint(1, requestId).message(2, Writer().string(5, ownershipToken))).toByteArray()

    fun downloadInitializeResult(response: Message): Boolean = response.message(2)?.uint(1) == 1L

    fun downloadUrls(requestId: Long, productId: Long, relativePaths: List<String>): ByteArray {
        val request = Writer().uint(1, productId)
        relativePaths.forEach { request.string(2, it) }
        return Writer().message(1, Writer().uint(1, requestId).message(3, Writer().message(1, request))).toByteArray()
    }

    class UrlAnswer(val owned: Boolean, val urls: List<List<String>>, val ttlSeconds: Long)

    /** Candidate URLs per requested path, in request order; null when the response has no answer. */
    fun downloadUrlsResult(response: Message): UrlAnswer? {
        val r = response.message(3) ?: return null
        val first = r.allMessages(1).firstOrNull() ?: return UrlAnswer(true, emptyList(), r.uint(2))
        return UrlAnswer(
            owned = first.uint(1) == 0L,
            urls = first.allMessages(2).map { d -> d.allBytes(1).map { it.toString(Charsets.UTF_8) }.filter { it.isNotEmpty() } },
            ttlSeconds = r.uint(2),
        )
    }
}
