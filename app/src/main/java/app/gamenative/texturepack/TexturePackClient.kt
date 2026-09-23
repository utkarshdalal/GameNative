package app.gamenative.texturepack

import android.content.Context
import android.net.ConnectivityManager
import app.gamenative.PrefManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

class TexturePackNetworkUnavailable : IOException("texture pack transfers are restricted to unmetered networks")

class TexturePackClient(
    private val context: Context,
    private val baseUrl: String = PrefManager.texturePackServer,
    private val token: String = PrefManager.texturePackToken,
    private val client: OkHttpClient = defaultClient,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun lookup(keys: List<String>): LookupResponse =
        postJson("/v1/lookup", json.encodeToString(LookupRequest.serializer(), LookupRequest(keys)))
            .let { json.decodeFromString(LookupResponse.serializer(), it) }

    suspend fun putSource(key: String, payload: ByteArray, compressed: Boolean = false) {
        val body = if (compressed) payload else gzip(payload)
        putBytes("/v1/source/${enc(key)}", body, contentEncoding = GZIP_ENCODING)
    }

    suspend fun packRegister(request: PackRegisterRequest): PackRegisterResponse =
        postJson("/v1/pack/register", json.encodeToString(PackRegisterRequest.serializer(), request))
            .let { json.decodeFromString(PackRegisterResponse.serializer(), it) }

    suspend fun pack(fingerprint: String, after: Long? = null): PackResponse =
        getString("/v1/pack/${enc(fingerprint)}" + (after?.let { "?after=$it" } ?: ""))
            .let { json.decodeFromString(PackResponse.serializer(), it) }

    suspend fun entries(keys: List<String>, onRecord: (String, ByteArray) -> Unit): Unit = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        val body = json.encodeToString(EntriesRequest.serializer(), EntriesRequest(keys)).toRequestBody(JSON_MEDIA)
        execute(authed(Request.Builder().url(url("/v1/entries")).post(body)).build()) { response ->
            if (response.code == 404) return@execute
            response.body.byteStream().buffered(STREAM_BUFFER_BYTES).use { input ->
                while (true) {
                    val record = TexturePackFraming.readRecord(input) ?: break
                    onRecord(record.key, record.payload)
                }
            }
        }
    }

    suspend fun putSources(sources: List<Pair<String, File>>): SourcesResponse = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        val body = object : RequestBody() {
            override fun contentType() = OCTET_MEDIA

            override fun writeTo(sink: BufferedSink) {
                val gzip = GZIPOutputStream(sink.outputStream(), STREAM_BUFFER_BYTES)
                for ((key, file) in sources) TexturePackFraming.writeRecord(gzip, key, file.readBytes())
                gzip.finish()
                gzip.flush()
            }
        }
        val request = authed(Request.Builder().url(url("/v1/sources")).post(body))
            .header("Content-Encoding", GZIP_ENCODING)
            .build()
        execute(request) { response ->
            if (response.code == 404) throw IOException("POST /v1/sources failed with 404")
            json.decodeFromString(SourcesResponse.serializer(), response.body.string())
        }
    }

    /** Returns null when the server has not encoded this entry yet (404). */
    suspend fun entry(key: String): ByteArray? = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        execute(authed(Request.Builder().url(url("/v1/entry/${enc(key)}")).get()).build()) { response ->
            if (response.code == 404) null else response.body.bytes()
        }
    }

    private suspend fun getString(path: String): String = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        execute(authed(Request.Builder().url(url(path)).get()).build()) { it.body.string() }
    }

    private suspend fun postJson(path: String, body: String): String = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        val request = authed(Request.Builder().url(url(path)).post(body.toRequestBody(JSON_MEDIA))).build()
        execute(request) { it.body.string() }
    }

    private suspend fun putBytes(
        path: String,
        payload: ByteArray,
        contentEncoding: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        val builder = authed(Request.Builder().url(url(path)).put(payload.toRequestBody(OCTET_MEDIA)))
        if (contentEncoding != null) builder.header("Content-Encoding", contentEncoding)
        execute(builder.build()) { }
    }

    private suspend fun <T> execute(request: Request, handle: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    throw IOException("${request.method} ${request.url.encodedPath} failed with ${response.code}")
                }
                handle(response)
            }
        } finally {
            canceller.cancel()
        }
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun authed(builder: Request.Builder): Request.Builder =
        if (token.isBlank()) builder else builder.header("Authorization", "Bearer $token")

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun requireAllowedNetwork() {
        if (!isTransferAllowed(context)) throw TexturePackNetworkUnavailable()
    }

    companion object {
        private const val GZIP_ENCODING = "gzip"
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private val JSON_MEDIA = "application/json".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()

        fun gzip(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size / 2 + 64)
            GZIPOutputStream(out).use { it.write(payload) }
            return out.toByteArray()
        }

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun transferAllowed(metered: Boolean, allowMobileData: Boolean): Boolean = !metered || allowMobileData

        fun isTransferAllowed(context: Context): Boolean {
            val allowMobileData = PrefManager.texturePackAllowMobileData
            if (allowMobileData) return true
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            return transferAllowed(manager.isActiveNetworkMetered, allowMobileData)
        }
    }
}
