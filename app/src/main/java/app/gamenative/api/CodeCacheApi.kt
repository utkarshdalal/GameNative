package app.gamenative.api

import app.gamenative.utils.FexImages
import com.github.luben.zstd.ZstdInputStream
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Looks up and downloads hosted FEX code caches by image (POST /api/code-caches/lookup). */
object CodeCacheApi {

    data class HostedCache(
        val imageSha256: String,
        /** The code identity the cache was matched on (FexImages.codeSha256). */
        val codeSha256: String,
        /** The name FEX looks for on this device, computed by the server from the image's name and PE header. */
        val fileName: String,
        val size: Long,
        val compressedSize: Long,
        val sha256: String,
        val url: String,
        /** sha256 of the previous version a hosted zstd patch applies to, if any. */
        val patchFrom: String?,
    )

    /** The hosted caches for [images] on this FEX build and SoC; images without one are left out. */
    suspend fun lookup(fexVersion: String, soc: String, images: List<FexImages.Image>): ApiResult<List<HostedCache>> {
        val body = JSONObject().apply {
            put("fexVersion", fexVersion)
            put("soc", soc)
            put(
                "images",
                JSONArray().apply {
                    images.forEach { image ->
                        put(
                            JSONObject()
                                .put("codeSha256", image.codeSha256)
                                .put("sha256", image.sha256)
                                .put("name", image.name)
                                .put("timeDateStamp", image.timeDateStamp)
                                .put("sizeOfImage", image.sizeOfImage),
                        )
                    }
                },
            )
        }
        val request = GameNativeApi.buildPostRequest("${GameNativeApi.BASE_URL}/api/code-caches/lookup", body)
        return GameNativeApi.executeRequest(request) { response ->
            // An unrouted path on the API host answers 200 from another worker; only a real list counts.
            val caches = JSONObject(response).optJSONArray("caches") ?: throw IOException("no cache list in response")
            buildList {
                for (i in 0 until caches.length()) {
                    val c = caches.getJSONObject(i)
                    add(
                        HostedCache(
                            c.getString("imageSha256"),
                            c.optString("codeSha256"),
                            c.getString("fileName"),
                            c.getLong("size"),
                            c.optLong("compressedSize", -1),
                            c.getString("sha256"),
                            c.getString("url"),
                            c.optString("patchFrom").takeIf { it.isNotBlank() && it != "null" },
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
                if (total != cache.size || sha256 != cache.sha256) throw IOException("cache ${cache.fileName} did not verify")
            }
            return tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
