package app.gamenative.utils

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object Net {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no per-packet timer
            .pingInterval(30, TimeUnit.SECONDS) // keep HTTP/2 alive
            .retryOnConnectionFailure(true) // default, but explicit
            .build()
    }
}
