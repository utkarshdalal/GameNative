package app.gamenative.utils

import android.net.Uri

/**
 * Redacts query parameters and fragment from a URL for safe logging (e.g. OAuth
 * redirect URLs that may contain `code`, `state`, or other sensitive params).
 * If the URL cannot be parsed, returns a placeholder to avoid logging raw input.
 */
fun redactUrlForLogging(url: String?): String =
    url?.let {
        runCatching {
            Uri.parse(it).buildUpon().clearQuery().fragment(null).build().toString()
        }.getOrDefault("<invalid-url>")
    } ?: "null"
