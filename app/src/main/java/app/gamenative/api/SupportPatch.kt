package app.gamenative.api

import app.gamenative.utils.DebugRunParams
import org.json.JSONObject

data class SupportPatch(
    val patchsetId: String,
    val summary: String?,
    val ops: List<Op>,
    val rerun: Boolean,
    val run: DebugRunParams?,
    val applicable: Boolean,
) {
    data class Op(
        val path: String,
        val op: String,
        val originalSha256: String,
        val sha256: String,
        val size: Long,
        val artifactKey: String?,
        val artifactUrl: String?,
    )

    companion object {
        const val OP_REPLACE = "replace"
        const val MAX_OPS = 8
        private const val MAX_SUMMARY = 300

        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        fun isSha256(value: String?): Boolean = value != null && SHA256.matches(value)

        private fun isValid(op: Op): Boolean =
            op.op == OP_REPLACE &&
                SupportFilesRequest.isSafePath(op.path) &&
                isSha256(op.originalSha256) &&
                isSha256(op.sha256) &&
                op.size in 0..SupportFilesRequest.MAX_BYTES &&
                op.artifactUrl?.startsWith("https://") == true

        fun parse(json: JSONObject?): SupportPatch? {
            if (json == null) return null
            if (json.optInt("v", 0) != 1) return null
            val patchsetId = json.cardText("patchsetId")
            if (!SupportFilesRequest.isValidId(patchsetId)) return null
            val array = json.optJSONArray("ops") ?: return null
            if (array.length() == 0) return null
            var applicable = array.length() <= MAX_OPS
            val ops = mutableListOf<Op>()
            for (i in 0 until minOf(array.length(), MAX_OPS)) {
                val item = array.optJSONObject(i)
                val path = item?.cardText("path")
                if (item == null || path == null) {
                    applicable = false
                    continue
                }
                val op = Op(
                    path = path,
                    op = item.cardText("op") ?: "",
                    originalSha256 = item.cardText("originalSha256")?.lowercase() ?: "",
                    sha256 = item.cardText("sha256")?.lowercase() ?: "",
                    size = if (item.has("size") && !item.isNull("size")) item.optLong("size", -1L) else -1L,
                    artifactKey = item.cardText("artifactKey"),
                    artifactUrl = item.cardText("artifactUrl"),
                )
                if (!isValid(op)) applicable = false
                ops += op
            }
            if (ops.isEmpty()) return null
            if (ops.groupBy { it.path.replace('\\', '/').lowercase() }.any { it.value.size > 1 }) applicable = false
            return SupportPatch(
                patchsetId = patchsetId!!,
                summary = cardClean(json.cardText("summary"), MAX_SUMMARY),
                ops = ops,
                rerun = json.optBoolean("rerun", false),
                run = DebugRunParams.fromJson(json.optJSONObject("run")),
                applicable = applicable,
            )
        }
    }
}
