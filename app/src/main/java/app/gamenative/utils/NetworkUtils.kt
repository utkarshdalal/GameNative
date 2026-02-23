package app.gamenative.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)         // 60s timeout for reading response data
            .writeTimeout(30, TimeUnit.SECONDS)        // 30s timeout for writing request data
            .callTimeout(5, TimeUnit.MINUTES)          // overall timeout for entire call
            .pingInterval(30, TimeUnit.SECONDS)         // keep HTTP/2 alive
            .retryOnConnectionFailure(true)             // default, but explicit
            .build()
    }
}
