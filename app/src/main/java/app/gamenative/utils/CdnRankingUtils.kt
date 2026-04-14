package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object CdnRankingUtils {
    suspend fun rankBaseUrlsByHeadProbe(
        baseUrls: List<String>,
        httpClient: OkHttpClient,
        userAgent: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val urls = baseUrls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.size <= 1) return@withContext urls

        val scored = urls.map { url ->
            val start = System.nanoTime()
            val success = try {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", userAgent)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    response.code in 200..499
                }
            } catch (_: Exception) {
                false
            }

            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            Triple(url, success, elapsedMs)
        }

        scored
            .sortedWith(compareByDescending<Triple<String, Boolean, Long>> { it.second }.thenBy { it.third })
            .map { it.first }
    }
}
