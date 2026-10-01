package app.gamenative.utils

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal object CompatibilityHttp {
    val client by lazy {
        Net.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class Result(val code: Int, val body: String?, val retryAfter: String?) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /** Cancellation covers both waiting for headers and reading a slow response body. */
    suspend fun execute(call: Call): Result = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        // Error bodies are unused; a slow body must not hide a received cooldown.
                        if (!it.isSuccessful) call.cancel()
                        Result(it.code, if (it.isSuccessful) it.body?.string() else null, it.header("Retry-After"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
