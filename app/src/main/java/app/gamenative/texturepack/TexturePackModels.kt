package app.gamenative.texturepack

import kotlinx.serialization.Serializable

@Serializable
data class PrepareFileEntry(
    val path: String,
    val size: Long,
)

@Serializable
data class PrepareStartRequest(
    val platform: String,
    val storeId: String,
    val files: List<PrepareFileEntry>,
)

@Serializable
data class PrepareStartResponse(
    val session: String = "",
    val status: String = STATUS_UNSUPPORTED,
    val fingerprint: String = "",
    val sourceFiles: Int? = null,
    val packBytes: Long? = null,
    val packEntries: Int? = null,
) {
    companion object {
        const val STATUS_READY = "ready"
        const val STATUS_NEEDS_IO = "needs_io"
        const val STATUS_UNSUPPORTED = "unsupported"
    }
}

@Serializable
data class IoRequest(
    val id: String,
    val file: String,
    val offset: Long,
    val length: Int,
    val codec: String = TextureCodec.NONE,
    val innerOffset: Int? = null,
    val innerLength: Int? = null,
)

@Serializable
data class PrepareIoBatch(
    val requests: List<IoRequest> = emptyList(),
    val done: Boolean = false,
    val plan: PreparePlan? = null,
)

@Serializable
data class PlanMip(
    val file: String,
    val offset: Long,
    val length: Int,
    val codec: String = TextureCodec.NONE,
    val innerOffset: Int? = null,
    val innerLength: Int? = null,
    val src: String,
    val w: Int,
    val h: Int,
)

@Serializable
data class PreparePlan(
    val mips: List<PlanMip> = emptyList(),
    val uploadEstimateBytes: Long = 0L,
    val downloadEstimateBytes: Long = 0L,
)

@Serializable
data class LookupRequest(val keys: List<String>)

@Serializable
data class LookupResponse(
    val missing: List<String> = emptyList(),
    val present: Int = 0,
)

@Serializable
data class PackEntry(
    val key: String,
    val size: Long = 0L,
)

@Serializable
data class PackResponse(
    val entries: List<PackEntry> = emptyList(),
)

object TextureCodec {
    const val NONE = "none"
    const val ZLIB = "zlib"
    const val LZ4_BLOCK = "lz4block"
    const val LZ4_FRAME = "lz4frame"
}
