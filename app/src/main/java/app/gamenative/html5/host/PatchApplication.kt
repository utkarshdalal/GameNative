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

    // order: AudioExtensionRemap -> AssetDecrypt -> ResponseBodyReplace. a failing patch is skipped.
    fun applyServeTime(
        originalResponse: WebResourceResponse,
        path: String,
        patches: List<Patch>,
        decryptContext: Html5DecryptContext?,
        omoriContext: OmoriDecryptContext? = null,
    ): WebResourceResponse {
        var response = originalResponse

        // MIME only; stream untouched.
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

        // without a context the ciphertext passes through, so the failure shows as a console syntax error.
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

    // dev diagnostic only, NOT surfaced to the user.
    private fun reportPatchSkip(patch: Patch, t: Throwable) {
        Timber.tag("PackPatch").e(t, "patch %s skipped", patch::class.simpleName)
    }

    private fun isRmmvEncrypted(path: String): Boolean =
        path.endsWith(".rpgmvo", ignoreCase = true) ||
            path.endsWith(".rpgmvp", ignoreCase = true) ||
            path.endsWith(".rpgmvm", ignoreCase = true)

    private fun mimeForDecrypted(path: String): String = when {
        path.endsWith(".rpgmvp", ignoreCase = true) -> "image/png"
        path.endsWith(".rpgmvo", ignoreCase = true) -> "audio/ogg"
        path.endsWith(".rpgmvm", ignoreCase = true) -> "audio/mp4"
        else -> mimeFor(path)
    }
}
