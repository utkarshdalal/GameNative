package app.gamenative.html5.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// typed patch variants consumed by AssetInterceptor + ZipAssetInterceptor.
// kotlinx.serialization default classDiscriminator "type" -- emitted/read as
// {"type":"<SerialName>", ...}. lenient Json (ignoreUnknownKeys) tolerates
// future variants older app versions haven't learned.
@Serializable
sealed class Patch {
    @Serializable
    @SerialName("audio-ext-remap")
    data class AudioExtensionRemap(val fromExt: String, val toExt: String) : Patch()

    @Serializable
    @SerialName("url-redirect")
    data class UrlPathRedirect(val from: String, val to: String) : Patch()

    @Serializable
    @SerialName("response-body-replace")
    data class ResponseBodyReplace(val pathPattern: String, val find: String, val replace: String) : Patch()

    @Serializable
    @SerialName("asset-decrypt")
    data class AssetDecrypt(val kind: String) : Patch()
}
