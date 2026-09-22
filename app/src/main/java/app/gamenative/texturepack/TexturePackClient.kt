package app.gamenative.texturepack

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.gamenative.PrefManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class TexturePackNetworkUnavailable : IOException("texture pack transfers are restricted to Wi-Fi")

class TexturePackClient(
    private val context: Context,
    private val baseUrl: String = PrefManager.texturePackServer,
    private val token: String = PrefManager.texturePackToken,
    private val client: OkHttpClient = defaultClient,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun prepareStart(request: PrepareStartRequest): PrepareStartResponse =
        postJson("/v1/prepare/start", json.encodeToString(PrepareStartRequest.serializer(), request))
            .let { json.decodeFromString(PrepareStartResponse.serializer(), it) }

    suspend fun prepareNext(session: String): PrepareIoBatch =
        getString("/v1/prepare/${enc(session)}/next")
            .let { json.decodeFromString(PrepareIoBatch.serializer(), it) }

    suspend fun putIoResult(session: String, id: String, payload: ByteArray) {
        putBytes("/v1/prepare/${enc(session)}/io/${enc(id)}", payload)
    }

    suspend fun lookup(keys: List<String>): LookupResponse =
        postJson("/v1/lookup", json.encodeToString(LookupRequest.serializer(), LookupRequest(keys)))
            .let { json.decodeFromString(LookupResponse.serializer(), it) }

    suspend fun putSource(key: String, payload: ByteArray) {
        putBytes("/v1/source/${enc(key)}", payload)
    }

    suspend fun packRegister(request: PackRegisterRequest): PackRegisterResponse =
        postJson("/v1/pack/register", json.encodeToString(PackRegisterRequest.serializer(), request))
            .let { json.decodeFromString(PackRegisterResponse.serializer(), it) }

    suspend fun pack(fingerprint: String): PackResponse =
        getString("/v1/pack/${enc(fingerprint)}")
            .let { json.decodeFromString(PackResponse.serializer(), it) }

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

    private suspend fun putBytes(path: String, payload: ByteArray): Unit = withContext(Dispatchers.IO) {
        requireAllowedNetwork()
        val request = authed(Request.Builder().url(url(path)).put(payload.toRequestBody(OCTET_MEDIA))).build()
        execute(request) { }
    }

    private fun <T> execute(request: Request, handle: (Response) -> T): T =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("${request.method} ${request.url.encodedPath} failed with ${response.code}")
            }
            handle(response)
        }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun authed(builder: Request.Builder): Request.Builder =
        if (token.isBlank()) builder else builder.header("Authorization", "Bearer $token")

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun requireAllowedNetwork() {
        if (!isTransferAllowed(context)) throw TexturePackNetworkUnavailable()
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun isTransferAllowed(context: Context): Boolean {
            if (PrefManager.texturePackAllowMobileData) return true
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
}
