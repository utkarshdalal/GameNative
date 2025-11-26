package app.gamenative.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Net {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)     // no per-packet timer
            .pingInterval(30, TimeUnit.SECONDS)         // keep HTTP/2 alive
            .retryOnConnectionFailure(true)             // default, but explicit
            .build()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
