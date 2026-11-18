package app.gamenative.service.ubisoft

import android.content.Context
import android.webkit.WebSettings
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class UbisoftCloudException(message: String, val httpStatus: Int = 0, cause: Throwable? = null) : Exception(message, cause)

data class UbisoftCloudSlot(val id: Int, val filename: String, val hash: String, val size: Long, val lastModified: String?)

data class UbisoftCloudEntity(
    val entityId: String,
    val revision: Int,
    val spaceId: String?,
    val slots: List<UbisoftCloudSlot>,
    val timestamp: String?,
    val pcName: String?,
)

data class UbisoftOwnedProduct(val productId: Long, val uplayId: Long, val steamAppId: Int?)

/** One sync run's connection to the account. Not thread safe. All calls are blocking; call from Dispatchers.IO. */
interface UbisoftCloudTransport : java.io.Closeable {
    /** The cloud save record of a game, or null when the account has none. */
    fun entity(uplayId: Long): UbisoftCloudEntity?

    /** Decompressed bytes per slot filename; a slot with no stored object is left out. */
    fun download(uplayId: Long, filenames: List<String>): Map<String, ByteArray>

    /** Stores each file's bytes (compressed by this call) under its slot filename. productId is the ownership product; null = same as uplayId. */
    fun upload(uplayId: Long, productId: Long?, files: Map<String, ByteArray>)

    /** Creates the record when previous is null, else replaces it using previous.revision; returns the stored record. */
    fun writeEntity(
        uplayId: Long,
        previous: UbisoftCloudEntity?,
        slots: List<UbisoftCloudSlot>,
        timestampSeconds: Long,
        pcName: String,
    ): UbisoftCloudEntity

    /** Games the account owns, with the Steam app id each one's configuration names. */
    fun ownedProducts(): List<UbisoftOwnedProduct>
}

/** Ubisoft Connect cloud saves: the account's save record over HTTPS, the save files through demux-signed URLs. */
object UbisoftCloudApi {
    private val REQUIRED = listOf(
        UbisoftHelperConfig.CLOUD_ENTITIES_URL,
        UbisoftHelperConfig.CLOUD_ENTITY_URL,
        UbisoftHelperConfig.CLOUD_ENTITY_NAME,
        UbisoftHelperConfig.CLOUD_SAVE_HOST,
        UbisoftHelperConfig.CLOUD_SAVE_PATH,
        UbisoftHelperConfig.DEMUX_HOST,
        UbisoftHelperConfig.DEMUX_SERVICE_CLOUDSAVE,
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** False when the installed helper configuration lacks the cloud keys; sync is then skipped. */
    fun isConfigured(context: Context): Boolean =
        runCatching { REQUIRED.all { UbisoftHelperConfig.optional(context, it) != null } }.getOrDefault(false)

    fun open(context: Context, creds: UbisoftCredentials): UbisoftCloudTransport {
        val appContext = context.applicationContext ?: context
        val defaultAgent by lazy { runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull() }
        val config: (String) -> String? = { name ->
            runCatching { UbisoftHelperConfig.optional(appContext, name) }.getOrNull()
                ?: if (name == UbisoftHelperConfig.HTTP_USER_AGENT) defaultAgent else null
        }
        return UbisoftCloudSession(config, http, creds) {
            fun need(name: String) = config(name) ?: throw UbisoftCloudException("Ubisoft cloud configuration is missing $name")
            UbisoftDemuxClient.connect(
                host = need(UbisoftHelperConfig.DEMUX_HOST),
                port = need(UbisoftHelperConfig.DEMUX_PORT).toIntOrNull()
                    ?: throw UbisoftCloudException("Ubisoft cloud configuration has a bad ${UbisoftHelperConfig.DEMUX_PORT}"),
                clientVersion = need(UbisoftHelperConfig.DEMUX_CLIENT_VERSION).toLongOrNull()
                    ?: throw UbisoftCloudException("Ubisoft cloud configuration has a bad ${UbisoftHelperConfig.DEMUX_CLIENT_VERSION}"),
                clientId = need(UbisoftHelperConfig.DEMUX_CLIENT_ID),
                ticket = creds.ticket,
            )
        }
    }
}

/**
 * [UbisoftCloudTransport] over the account's REST service and a demux session that is opened on
 * first use. Signed save URLs are checked against the configured store before any transfer.
 */
internal class UbisoftCloudSession(
    private val config: (String) -> String?,
    private val http: OkHttpClient,
    private val creds: UbisoftCredentials,
    private val maxInflatedBytes: Long = MAX_INFLATED_BYTES,
    private val demuxFactory: () -> UbisoftDemuxConnection,
) : UbisoftCloudTransport {
    private var demux: UbisoftDemuxConnection? = null
    private val services = HashMap<String, Long>()
    private val requestIds = HashMap<Long, Long>()
    private var owned: List<UbisoftOwnedProduct>? = null
    private var closed = false

    override fun entity(uplayId: Long): UbisoftCloudEntity? = guard("cloud save record") {
        val url = UbisoftHelperConfig.fill(
            required(UbisoftHelperConfig.CLOUD_ENTITIES_URL),
            mapOf("profileId" to encode(creds.profileId), "name" to encode(entityName(uplayId))),
        )
        val (code, body) = rest("cloud save record", account(url).get().build())
        if (code !in 200..299) throw httpFailure("cloud save record", code)
        parseEntities(body)
    }

    override fun download(uplayId: Long, filenames: List<String>): Map<String, ByteArray> = guard("cloud save download") {
        if (filenames.isEmpty()) return@guard emptyMap()
        filenames.forEach { checkSlotName(it) }
        val signed = signedRequests(uplayId, null, UbisoftDemuxProto.METHOD_GET, filenames.map { UbisoftDemuxProto.Item(it) })
        val out = LinkedHashMap<String, ByteArray>()
        filenames.forEachIndexed { i, name ->
            http.newCall(signed[i].builder().get().build()).execute().use { resp ->
                Timber.i("Ubisoft cloud download of slot %s -> %d", name, resp.code)
                when (resp.code) {
                    404 -> Unit
                    in 200..299 -> out.put(name, inflate(readBounded(resp, MAX_COMPRESSED_BYTES), maxInflatedBytes))
                    else -> throw httpFailure("cloud save download", resp.code)
                }
            }
        }
        out
    }

    override fun upload(uplayId: Long, productId: Long?, files: Map<String, ByteArray>) {
        guard("cloud save upload") {
            if (files.isEmpty()) return@guard
            val names = files.keys.toList()
            names.forEach { checkSlotName(it) }
            val bodies = names.map { deflate(files.getValue(it)) }
            val md5s = bodies.map { md5Base64(it) }
            val items = names.indices.map { UbisoftDemuxProto.Item(names[it], bodies[it].size.toLong(), md5s[it]) }
            var result = urlResult(uplayId, null, UbisoftDemuxProto.METHOD_PUT, items)
            if (result.status != UbisoftDemuxProto.URL_STATUS_OK) {
                val firstRefusal = refusal(result)
                val token = try {
                    ownershipToken(productId ?: uplayId)
                } catch (e: Exception) {
                    Timber.w("Ubisoft ownership token unavailable: %s", e.javaClass.simpleName)
                    throw firstRefusal
                }
                result = urlResult(uplayId, token, UbisoftDemuxProto.METHOD_PUT, items)
            }
            val signed = signedRequests(result, uplayId, items)
            signed.forEachIndexed { i, s ->
                val signedMd5 = s.header("Content-MD5")
                if (signedMd5 != null && signedMd5 != md5s[i]) throw UbisoftCloudException("signed upload expects other content")
                val signedLength = s.header("Content-Length")
                if (signedLength != null && signedLength.toLongOrNull() != bodies[i].size.toLong()) {
                    throw UbisoftCloudException("signed upload expects another length")
                }
            }
            names.forEachIndexed { i, name ->
                val request = signed[i].builder()
                    .header("Content-MD5", md5s[i])
                    .put(bodies[i].toRequestBody(null))
                    .build()
                http.newCall(request).execute().use { resp ->
                    Timber.i("Ubisoft cloud upload of slot %s (%d bytes) -> %d", name, bodies[i].size, resp.code)
                    if (resp.code !in 200..299) throw httpFailure("cloud save upload", resp.code)
                }
            }
        }
    }

    override fun writeEntity(
        uplayId: Long,
        previous: UbisoftCloudEntity?,
        slots: List<UbisoftCloudSlot>,
        timestampSeconds: Long,
        pcName: String,
    ): UbisoftCloudEntity = guard("cloud save record write") {
        val body = entityBody(
            revision = previous?.revision,
            name = entityName(uplayId),
            type = required(UbisoftHelperConfig.CLOUD_ENTITY_TYPE),
            spaceId = previous?.spaceId ?: config(UbisoftHelperConfig.CLOUD_SPACE_ID),
            slots = slots,
            timestampSeconds = timestampSeconds,
            pcName = pcName,
        ).toString().toRequestBody(JSON)
        val request = if (previous == null) {
            val template = config(UbisoftHelperConfig.CLOUD_ENTITY_CREATE_URL)
                ?: throw UbisoftCloudException("cloud save record cannot be created")
            account(UbisoftHelperConfig.fill(template, mapOf("profileId" to encode(creds.profileId)))).post(body).build()
        } else {
            val template = required(UbisoftHelperConfig.CLOUD_ENTITY_URL)
            account(UbisoftHelperConfig.fill(template, mapOf("entityId" to encode(previous.entityId)))).put(body).build()
        }
        val (code, text) = rest("cloud save record write", request)
        if (code !in 200..299) throw httpFailure("cloud save record write", code)
        storedEntity(text) ?: entity(uplayId) ?: throw UbisoftCloudException("cloud save record missing after the write")
    }

    override fun ownedProducts(): List<UbisoftOwnedProduct> = guard("owned games") { initialize() }

    override fun close() {
        closed = true
        resetDemux()
    }

    // ---- account REST ----

    private fun required(name: String): String =
        config(name) ?: throw UbisoftCloudException("Ubisoft cloud configuration is missing $name")

    private fun entityName(uplayId: Long) =
        UbisoftHelperConfig.fill(required(UbisoftHelperConfig.CLOUD_ENTITY_NAME), mapOf("productId" to uplayId.toString()))

    private fun account(url: String): Request.Builder {
        val values = mapOf("appId" to config(UbisoftHelperConfig.APP_ID).orEmpty())
        return Request.Builder().url(url).apply {
            UbisoftHelperConfig.headers(config(UbisoftHelperConfig.HTTP_HEADERS), values).forEach { (name, value) -> header(name, value) }
            header("Authorization", UbisoftHelperConfig.fill(required(UbisoftHelperConfig.AUTH_TICKET), mapOf("ticket" to creds.ticket)))
            val sessionHeader = config(UbisoftHelperConfig.SESSION_HEADER)
            if (sessionHeader != null && creds.sessionId.isNotEmpty()) header(sessionHeader, creds.sessionId)
            config(UbisoftHelperConfig.HTTP_USER_AGENT)?.let { header("User-Agent", it) }
        }
    }

    private fun rest(what: String, request: Request): Pair<Int, String> = http.newCall(request).execute().use { resp ->
        Timber.i("Ubisoft %s %s -> %d", what, request.method, resp.code)
        resp.code to String(readBounded(resp, MAX_JSON_BYTES), Charsets.UTF_8)
    }

    // ---- demux ----

    private fun demux(): UbisoftDemuxConnection {
        if (closed) throw UbisoftCloudException("cloud save connection is closed")
        return demux ?: demuxFactory().also { demux = it }
    }

    private fun resetDemux() {
        demux?.let { runCatching { it.close() } }
        demux = null
        services.clear()
        requestIds.clear()
    }

    private fun service(key: String): Long {
        val name = required(key)
        return services[name] ?: demux().openService(name).also { services[name] = it }
    }

    private fun serviceCall(key: String, build: (Long) -> ByteArray): UbisoftDemuxProto.Message {
        try {
            val connection = service(key)
            val id = requestIds[connection] ?: 1L
            requestIds[connection] = id + 1
            val down = demux().exchange(connection, build(id))
            return UbisoftDemuxProto.serviceResponse(down, id)
                ?: throw UbisoftCloudException("demux: the service answered another request")
        } catch (e: Exception) {
            resetDemux()
            throw e
        }
    }

    private fun initialize(): List<UbisoftOwnedProduct> = owned ?: run {
        val rsp = serviceCall(UbisoftHelperConfig.DEMUX_SERVICE_OWNERSHIP) { id ->
            UbisoftDemuxProto.ownershipInitialize(id, creds.ticket, creds.sessionId)
        }
        UbisoftDemuxProto.ownedProducts(rsp) ?: throw UbisoftCloudException("owned games: initialize failed")
    }.also { owned = it }

    private fun ownershipToken(productId: Long): String {
        initialize()
        val rsp = serviceCall(UbisoftHelperConfig.DEMUX_SERVICE_OWNERSHIP) { id ->
            UbisoftDemuxProto.ownershipToken(id, productId, creds.ticket, creds.sessionId)
        }
        return UbisoftDemuxProto.ownershipTokenResult(rsp) ?: throw UbisoftCloudException("ownership token refused for product $productId")
    }

    /** A signed request. [builder] sets each signed header once; Host and Content-Length are left to the client. */
    private class Signed(val url: HttpUrl, val headers: List<Pair<String, String>>) {
        fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, true) }?.second

        fun builder(): Request.Builder = Request.Builder().url(url).apply {
            for ((name, value) in headers) {
                if (name.equals("Host", true) || name.equals("Content-Length", true)) continue
                header(name, value)
            }
        }
    }

    private fun urlResult(uplayId: Long, token: String?, method: Int, items: List<UbisoftDemuxProto.Item>): UbisoftDemuxProto.UrlResult {
        val rsp = serviceCall(UbisoftHelperConfig.DEMUX_SERVICE_CLOUDSAVE) { id ->
            UbisoftDemuxProto.cloudsaveUrlRequest(id, token, uplayId, method, items)
        }
        return UbisoftDemuxProto.cloudsaveUrlResult(rsp) ?: throw UbisoftCloudException("cloud save urls: no answer")
    }

    private fun refusal(result: UbisoftDemuxProto.UrlResult) =
        UbisoftCloudException("cloud save urls refused (status ${result.status})")

    private fun signedRequests(uplayId: Long, token: String?, method: Int, items: List<UbisoftDemuxProto.Item>): List<Signed> =
        signedRequests(urlResult(uplayId, token, method, items), uplayId, items)

    private fun signedRequests(result: UbisoftDemuxProto.UrlResult, uplayId: Long, items: List<UbisoftDemuxProto.Item>): List<Signed> {
        if (result.status != UbisoftDemuxProto.URL_STATUS_OK) throw refusal(result)
        if (result.requests.size != items.size) {
            throw UbisoftCloudException("cloud save urls: ${result.requests.size} for ${items.size} items")
        }
        return result.requests.mapIndexed { i, r -> Signed(validateSignedUrl(r.url, uplayId, items[i].name), r.headers) }
    }

    /** The URL when it is HTTPS on the configured save host with exactly this profile's, game's and slot's path. */
    internal fun validateSignedUrl(url: String, uplayId: Long, slot: String): HttpUrl {
        checkSlotName(slot)
        val parsed = url.toHttpUrlOrNull() ?: throw UbisoftCloudException("signed url is not a url")
        val host = required(UbisoftHelperConfig.CLOUD_SAVE_HOST)
        val path = UbisoftHelperConfig.fill(
            required(UbisoftHelperConfig.CLOUD_SAVE_PATH),
            mapOf("profileId" to creds.profileId, "productId" to uplayId.toString(), "slot" to slot),
        )
        val valid = parsed.isHttps && parsed.host.equals(host, ignoreCase = true) && parsed.port == 443 &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.encodedPath == path
        if (!valid) throw UbisoftCloudException("signed url does not point at this save slot")
        return parsed
    }

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: UbisoftCloudException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw UbisoftCloudException("$what failed: ${e.javaClass.simpleName}", cause = e)
    }

    companion object {
        const val MAX_INFLATED_BYTES = 64L * 1024 * 1024
        private const val MAX_COMPRESSED_BYTES = 64L * 1024 * 1024
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024
        private val JSON = "application/json".toMediaType()
        private val SLOT_NAME = Regex("[A-Za-z0-9_.-]{1,128}")

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun httpFailure(what: String, code: Int) = UbisoftCloudException("$what: HTTP $code", code)

        internal fun checkSlotName(name: String) {
            if (!SLOT_NAME.matches(name) || name.contains("..")) throw UbisoftCloudException("cloud save slot name is not usable")
        }

        /** The record of an entities answer: null when the list is empty. */
        internal fun parseEntities(body: String): UbisoftCloudEntity? {
            val json = JSONObject(body)
            val list = json.optJSONArray("entities")
                ?: throw UbisoftCloudException("cloud save record: unexpected answer")
            if (list.length() == 0) return null
            return parseEntity(list.getJSONObject(0))
        }

        /** The record a write answered with, when the answer is one. */
        internal fun storedEntity(body: String): UbisoftCloudEntity? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val candidate = json.optJSONArray("entities")?.optJSONObject(0) ?: json
            if (text(candidate, "entityId") == null || candidate.optJSONObject("obj") == null) return null
            return runCatching { parseEntity(candidate) }.getOrNull()
        }

        internal fun parseEntity(json: JSONObject): UbisoftCloudEntity {
            val entityId = text(json, "entityId") ?: throw UbisoftCloudException("cloud save record has no id")
            val obj = json.optJSONObject("obj")
            val slotArray = obj?.optJSONArray("slots") ?: JSONArray()
            val slots = (0 until slotArray.length()).mapNotNull { slotArray.optJSONObject(it) }.map { s ->
                UbisoftCloudSlot(
                    id = s.optInt("id"),
                    filename = text(s, "filename").orEmpty(),
                    hash = text(s, "hash").orEmpty(),
                    size = s.optLong("size"),
                    lastModified = text(s, "lastModified"),
                )
            }
            return UbisoftCloudEntity(
                entityId = entityId,
                revision = json.optInt("revision"),
                spaceId = text(json, "spaceId"),
                slots = slots,
                timestamp = text(obj, "timestamp"),
                pcName = text(obj, "pc_name"),
            )
        }

        internal fun entityBody(
            revision: Int?,
            name: String,
            type: String,
            spaceId: String?,
            slots: List<UbisoftCloudSlot>,
            timestampSeconds: Long,
            pcName: String,
        ): JSONObject {
            val slotArray = JSONArray()
            for (slot in slots) {
                val s = JSONObject()
                    .put("id", slot.id)
                    .put("filename", slot.filename)
                    .put("hash", slot.hash)
                    .put("size", slot.size)
                if (slot.lastModified != null) s.put("lastModified", slot.lastModified)
                slotArray.put(s)
            }
            val obj = JSONObject()
                .put("slots", slotArray)
                .put("timestamp", timestampSeconds.toString())
                .put("pc_name", pcName)
            return JSONObject().apply {
                if (revision != null) put("revision", revision)
                put("name", name)
                put("type", type)
                if (spaceId != null) put("spaceId", spaceId)
                put("tags", JSONArray())
                put("obj", obj)
            }
        }

        private fun text(json: JSONObject?, key: String): String? =
            if (json == null || json.isNull(key)) null else json.get(key).toString().takeIf { it.isNotEmpty() }

        /** zlib data inflated, failing once the output passes [limit] or the stream ends early. */
        internal fun inflate(data: ByteArray, limit: Long): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && !inflater.finished()) throw UbisoftCloudException("cloud save data is truncated")
                    if (out.size().toLong() + n > limit) throw UbisoftCloudException("cloud save data exceeds size limit")
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } catch (e: DataFormatException) {
                throw UbisoftCloudException("cloud save data is not zlib", cause = e)
            } finally {
                inflater.end()
            }
        }

        internal fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buffer)
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        internal fun md5Base64(data: ByteArray): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data))

        private fun readBounded(resp: Response, limit: Long): ByteArray = resp.body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size().toLong() + n > limit) throw UbisoftCloudException("cloud save answer exceeds size limit")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }
}
