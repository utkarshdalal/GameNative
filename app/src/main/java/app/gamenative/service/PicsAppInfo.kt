package app.gamenative.service

import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo
import `in`.dragonbra.javasteam.types.KeyValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Resolves the KeyValue payload of a PICS app result.
 *
 * Steam no longer inlines app info above a size threshold: the CM response carries only the
 * content hash (and sometimes an HTTP host), and the client is expected to download the text from
 * `https://<host>/appinfo/<appid>/sha/<sha>.txt.gz`. JavaSteam exposes that as
 * [PICSProductInfo.httpUri] and leaves [PICSProductInfo.keyValues] empty. Observed in the wild
 * (2026-10-31): the CM sends the sha with neither `http_host` nor `http_min_size`, so the URI has to
 * be built against Steam's client-config CDN. Every consumer of app PICS data must go through here so
 * those apps are not treated as empty results.
 */
object PicsAppInfo {

    /** Host the Steam client itself uses for appinfo when the CM does not name one. */
    private const val FALLBACK_APPINFO_HOST = "clientconfig.akamai.steamstatic.com"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns the app's KeyValue tree, downloading it over HTTP when Steam asked for that.
     * Returns null when no data is available (missing access token, HTTP failure, empty body).
     */
    suspend fun PICSProductInfo.resolveAppKeyValues(): KeyValue? {
        if (keyValues.children.isNotEmpty()) {
            return keyValues
        }

        // The inline buffer is empty, so HTTP is the only place the data can be. JavaSteam's
        // isUseHttp additionally compares the reported size against http_min_size; do not depend on
        // that, a content hash is enough.
        val shaHex = shaHash?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
        val uri = httpUri
            ?: shaHex?.let { URI.create("https://$FALLBACK_APPINFO_HOST/appinfo/$id/sha/$it.txt.gz") }
        if (uri == null) {
            Timber.w(
                "PICS app $id came back without data (missingToken=$isMissingToken, useHttp=$isUseHttp, " +
                    "onlyPublic=$isOnlyPublic, changeNumber=$changeNumber)",
            )
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(uri.toString()).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("PICS app $id: HTTP appinfo fetch failed with ${response.code} for $uri")
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    // Like the inline CM buffer, the file ends with a NUL byte that the text reader
                    // would otherwise take for the start of a second root key.
                    val bytes = decompressIfGzip(body.byteStream()).use { it.readBytes() }
                    var end = bytes.size
                    while (end > 0 && bytes[end - 1] == 0.toByte()) end--
                    val kv = KeyValue()
                    val ok = ByteArrayInputStream(bytes, 0, end).use { stream -> kv.readAsText(stream) }
                    if (!ok || kv.children.isEmpty()) {
                        Timber.w("PICS app $id: HTTP appinfo from $uri parsed to an empty KeyValue")
                        null
                    } else {
                        Timber.d("PICS app $id: appinfo loaded over HTTP (changeNumber=$changeNumber)")
                        kv
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "PICS app $id: HTTP appinfo fetch threw for $uri")
                null
            }
        }
    }

    /** OkHttp only transparently inflates a Content-Encoding header; the body may be raw .gz bytes. */
    private fun decompressIfGzip(raw: InputStream): InputStream {
        val buffered = BufferedInputStream(raw)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
