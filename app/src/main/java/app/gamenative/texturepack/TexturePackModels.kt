package app.gamenative.texturepack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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
    val title: String? = null,
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
    val have: List<String> = emptyList(),
    val want: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

@Serializable
data class PackRegisterRequest(
    val platform: String,
    val storeId: String,
    val files: List<PrepareFileEntry>,
    val keys: List<String> = emptyList(),
    val title: String? = null,
    val needsFullRes: Boolean = false,
)

@Serializable
data class PackPolicy(
    val enabled: Boolean = false,
    val blocks: Map<String, String> = emptyMap(),
    val maxDim: Int? = null,
)

@Serializable
data class PackRegisterResponse(
    val fingerprint: String = "",
    val policy: PackPolicy? = null,
)

@Serializable
data class PackEntry(
    val key: String,
    val size: Long = 0L,
    val rawSize: Long? = null,
    val pending: Boolean = false,
)

@Serializable
data class PackResponse(
    val entries: List<PackEntry> = emptyList(),
    val pending: JsonElement? = null,
    val encoded: Int = 0,
    val expected: Int = 0,
    val next: Long? = null,
    val policy: PackPolicy? = null,
) {
    val pendingKeys: Set<String>
        get() = (pending as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()

    val pendingCount: Int
        get() = when (val value = pending) {
            is JsonArray -> value.size
            is JsonPrimitive -> value.intOrNull ?: 0
            else -> 0
        }
}

@Serializable
data class EntriesRequest(val keys: List<String>)

@Serializable
data class SourceResult(
    val key: String,
    val status: String = "",
    val error: String? = null,
) {
    val accepted: Boolean
        get() = status == STATUS_QUEUED || status == STATUS_HAVE || status == STATUS_PENDING

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_HAVE = "have"
        const val STATUS_PENDING = "pending"
        const val STATUS_REJECTED = "rejected"
    }
}

@Serializable
data class SourcesResponse(
    val results: List<SourceResult> = emptyList(),
)

object TextureCodec {
    const val NONE = "none"
    const val ZLIB = "zlib"
    const val LZ4_BLOCK = "lz4block"
    const val LZ4_FRAME = "lz4frame"
}
