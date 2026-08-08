package app.gamenative.html5.host

import android.webkit.WebResourceResponse
import app.gamenative.html5.profile.Patch
import java.io.ByteArrayInputStream
import timber.log.Timber

object PatchApplication {
    // rewrite URL path before asset lookup. null = no rewrite.
    fun applyUrlRedirects(path: String, patches: List<Patch>): String? {
        patches.filterIsInstance<Patch.UrlPathRedirect>()
            .firstOrNull { path == it.from }
            ?.let { return it.to }
        return null
    }

    // serve-time transforms applied to resolved response body.
    // order: AudioExtensionRemap → AssetDecrypt → ResponseBodyReplace.
    // each patch wrapped in runCatching; first failure logs + emits one snackbar.
    // omoriContext is optional and only consulted when an asset-decrypt patch with
    // kind="omori-aes-ctr" matches a `.OMORI` request -- see OmoriDecryptContext.
    fun applyServeTime(
        originalResponse: WebResourceResponse,
        path: String,
        patches: List<Patch>,
        decryptContext: Html5DecryptContext?,
        omoriContext: OmoriDecryptContext? = null,
    ): WebResourceResponse {
        var response = originalResponse

        // 1. AudioExtensionRemap -- changes MIME only; stream untouched.
        patches.filterIsInstance<Patch.AudioExtensionRemap>()
            .firstOrNull { path.endsWith(it.fromExt, ignoreCase = true) }
            ?.let { patch ->
                runCatching {
                    response = WebResourceResponse(
                        mimeFor("dummy${patch.toExt}"),
                        response.encoding,
                        response.data,
                    )
                }.onFailure { reportPatchSkip(patch, it) }
            }

        // 2. AssetDecrypt -- stream wrapper (XOR header via SequenceInputStream).
        patches.filterIsInstance<Patch.AssetDecrypt>()
            .firstOrNull { it.kind == "rpgmv-xor" && isRmmvEncrypted(path) }
            ?.let { patch ->
                runCatching {
                    val decryptedStream = decryptContext?.wrapStream(response.data)
                        ?: return@runCatching
                    response = WebResourceResponse(
                        mimeForDecrypted(path),
                        response.encoding,
                        decryptedStream,
                    )
                }.onFailure { reportPatchSkip(patch, it) }
            }

        // 2b. AssetDecrypt -- OMORI AES-256-CTR full-body decrypt for `.OMORI` plugin files.
        // requires omoriContext (built Kotlin-side from Steam launch args). on missing context
        // or decrypt failure, the original encrypted body passes through; the resulting JS
        // syntax error surfaces in the WebView console rather than silently breaking.
        patches.filterIsInstance<Patch.AssetDecrypt>()
            .firstOrNull { it.kind == "omori-aes-ctr" && path.endsWith(".OMORI", ignoreCase = true) }
            ?.let { patch ->
                runCatching {
                    val decryptedStream = omoriContext?.decryptStream(response.data)
                        ?: return@runCatching
                    response = WebResourceResponse(
                        "application/javascript",
                        "utf-8",
                        decryptedStream,
                    )
                }.onFailure { reportPatchSkip(patch, it) }
            }

        // 3. ResponseBodyReplace -- read body, splice literal, rewrap.
        patches.filterIsInstance<Patch.ResponseBodyReplace>()
            .forEach { patch ->
                runCatching {
                    if (!Regex(patch.pathPattern).containsMatchIn(path)) return@runCatching
                    val bytes = response.data.readBytes()
                    val text = String(bytes, Charsets.UTF_8)
                    val replaced = text.replace(patch.find, patch.replace)
                    response = WebResourceResponse(
                        response.mimeType,
                        response.encoding,
                        ByteArrayInputStream(replaced.toByteArray(Charsets.UTF_8)),
                    )
                }.onFailure { reportPatchSkip(patch, it) }
            }

        return response
    }

    // pack-patch failures are dev diagnostics -- logged with stacktrace, NOT surfaced to the user
    // (interceptor thread has no Context; the logcat line is the only audience that can act on it).
    private fun reportPatchSkip(patch: Patch, t: Throwable) {
        Timber.tag("PackPatch").e(t, "patch %s skipped", patch::class.simpleName)
    }

    private fun isRmmvEncrypted(path: String): Boolean =
        path.endsWith(".rpgmvo", ignoreCase = true) ||
            path.endsWith(".rpgmvp", ignoreCase = true) ||
            path.endsWith(".rpgmvm", ignoreCase = true)

    // mime for decrypted RPGMV assets; falls back to mimeFor for unknown extensions.
    private fun mimeForDecrypted(path: String): String = when {
        path.endsWith(".rpgmvp", ignoreCase = true) -> "image/png"
        path.endsWith(".rpgmvo", ignoreCase = true) -> "audio/ogg"
        path.endsWith(".rpgmvm", ignoreCase = true) -> "audio/mp4"
        else -> mimeFor(path)
    }
}
