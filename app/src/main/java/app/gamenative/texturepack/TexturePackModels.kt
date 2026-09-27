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
