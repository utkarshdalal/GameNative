package app.gamenative.service.xbox.live

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal object LiveHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class Reply(val code: Int, val body: String)

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        http: OkHttpClient = client,
    ): Reply {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType())).build()
        return http.newCall(request).await()
    }

    /** The body is read on OkHttp's callback thread so callers on the main thread never block. */
    private suspend fun Call.await(): Reply = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { Reply(it.code, it.body.string()) }
                } catch (e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                    return
                }
                cont.resume(reply) { _, _, _ -> }
            }
        })
    }
}
