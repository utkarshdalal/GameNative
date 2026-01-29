package app.gamenative.utils

import kotlinx.serialization.Serializable

@Serializable
data class ManifestEntry(
    val id: String,
    val name: String,
    val url: String,
    val variant: String? = null,
    val arch: String? = null,
)

@Serializable
data class ManifestData(
    val version: Int?,
    val updatedAt: String?,
    val items: Map<String, List<ManifestEntry>>,
) {
    companion object {
        fun empty(): ManifestData = ManifestData(null, null, emptyMap())
    }
}

object ManifestContentTypes {
    const val DRIVER = "driver"
    const val DXVK = "dxvk"
    const val VKD3D = "vkd3d"
    const val BOX64 = "box64"
    const val WOWBOX64 = "wowbox64"
    const val FEXCORE = "fexcore"
    const val WINE = "wine"
    const val PROTON = "proton"
}
