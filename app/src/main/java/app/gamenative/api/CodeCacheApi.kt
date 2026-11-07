package app.gamenative.api

import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Lists and downloads hosted FEX code caches (GET /api/code-caches). */
object CodeCacheApi {

    data class HostedCache(val id: Long, val name: String, val size: Long, val compressedSize: Long, val sha256: String, val url: String)

    suspend fun list(store: String, gameId: Int, fexVersion: String, soc: String): ApiResult<List<HostedCache>> {
        val url = "${GameNativeApi.BASE_URL}/api/code-caches?store=$store&gameId=$gameId&fexVersion=$fexVersion&soc=${URLEncoder.encode(soc, "UTF-8")}"
        val request = GameNativeApi.buildGetRequest(url)
        return GameNativeApi.executeRequest(request) { body ->
            val caches = JSONObject(body).optJSONArray("caches")
            buildList {
                for (i in 0 until (caches?.length() ?: 0)) {
                    val c = caches!!.getJSONObject(i)
                    add(HostedCache(c.getLong("id"), c.getString("name"), c.getLong("size"), c.optLong("compressedSize", -1), c.getString("sha256"), c.getString("url")))
                }
            }
        }
    }

    /**
     * Streams one cache (served as gzip) into [target] through a temp file, inflating and hashing on
     * the way; throws (and leaves nothing) when the inflated size or sha256 does not match.
     */
    fun download(cache: HostedCache, target: File): Boolean {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            val request = Request.Builder().url(cache.url).get().build()
            GameNativeApi.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                GZIPInputStream(response.body!!.byteStream()).use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            total += n
                        }
                    }
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != cache.size || sha256 != cache.sha256) throw IOException("cache ${cache.name} did not verify")
            }
            return tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
