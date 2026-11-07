package app.gamenative.api

import com.github.luben.zstd.ZstdInputStream
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

    data class HostedCache(
        val id: Long,
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val sha256: String,
        val url: String,
        /** sha256 of the previous version a hosted zstd patch applies to, if any. */
        val patchFrom: String?,
    )

    suspend fun list(store: String, gameId: Int, fexVersion: String, soc: String): ApiResult<List<HostedCache>> {
        val url = "${GameNativeApi.BASE_URL}/api/code-caches?store=$store&gameId=$gameId&fexVersion=$fexVersion&soc=${URLEncoder.encode(soc, "UTF-8")}"
        val request = GameNativeApi.buildGetRequest(url)
        return GameNativeApi.executeRequest(request) { body ->
            val caches = JSONObject(body).optJSONArray("caches")
            buildList {
                for (i in 0 until (caches?.length() ?: 0)) {
                    val c = caches!!.getJSONObject(i)
                    add(
                        HostedCache(
                            c.getLong("id"),
                            c.getString("name"),
                            c.getLong("size"),
                            c.optLong("compressedSize", -1),
                            c.getString("sha256"),
                            c.getString("url"),
                            c.optString("patchFrom").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Brings [target] to the hosted version. When the device holds the version a hosted patch
     * applies to ([haveSha256] equals the cache's patchFrom), only the zstd patch is fetched and
     * applied with the old file as dictionary; otherwise the full gzip is streamed. Either way the
     * result is inflated through a temp file, hashed, and renamed only when size and sha256 match.
     */
    fun download(cache: HostedCache, target: File, haveSha256: String? = null): Boolean {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            val usePatch = haveSha256 != null && haveSha256 == cache.patchFrom && target.isFile
            val url = if (usePatch) "${cache.url}?have=$haveSha256" else cache.url
            val request = Request.Builder().url(url).get().build()
            GameNativeApi.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val isPatch = response.header("X-Patch-From") == haveSha256 && usePatch
                val body = response.body!!.byteStream()
                val input = if (isPatch) {
                    // zstd --patch-from: the previous file is the dictionary, window up to 2 GB.
                    ZstdInputStream(body).apply {
                        setLongMax(31)
                        setDict(target.readBytes())
                    }
                } else {
                    GZIPInputStream(body)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                input.use { inflated ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = inflated.read(buffer)
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
