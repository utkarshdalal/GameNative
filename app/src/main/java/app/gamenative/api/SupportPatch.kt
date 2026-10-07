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
        val hive: String? = null,
        val key: String? = null,
        val values: List<RegValue> = emptyList(),
    ) {
        val isRegistry: Boolean get() = op == OP_REGMERGE
    }

    data class RegValue(val name: String, val type: String?, val data: String?, val delete: Boolean)

    companion object {
        const val OP_REPLACE = "replace"
        const val OP_REGMERGE = "regmerge"
        const val MAX_OPS = 8
        const val MAX_REG_VALUES = 64
        private const val MAX_SUMMARY = 300
        private const val MAX_REG_KEY = 512
        private const val MAX_REG_NAME = 255
        private const val MAX_REG_STRING = 4096

        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        private val REG_ROOTS = mapOf("HKCU" to listOf("software", "control panel"), "HKLM" to listOf("software"))
        private val REG_DATA = mapOf(
            "dword" to Regex("^[0-9a-fA-F]{1,8}$"),
            "qword" to Regex("^[0-9a-fA-F]{1,16}$"),
            "binary" to Regex("^(?:[0-9a-fA-F]{2}){0,2048}$"),
        )
        private val READ_ONLY_TOKENS = listOf("%WINEPREFIX%", "%STEAM%")

        fun isSha256(value: String?): Boolean = value != null && SHA256.matches(value)

        private fun hasControl(value: String): Boolean = value.any { it.code < 0x20 || it.code == 0x7f }

        private fun isValidRegKey(hive: String?, key: String?): Boolean {
            val roots = REG_ROOTS[hive] ?: return false
            if (key == null || key.isEmpty() || key.length > MAX_REG_KEY || hasControl(key)) return false
            val parts = key.split('\\')
            return parts.size >= 2 && parts.none { it.isEmpty() || it.length > 255 } && parts[0].lowercase() in roots
        }

        private fun isValidRegValue(value: RegValue): Boolean {
            if (value.name.length > MAX_REG_NAME || hasControl(value.name)) return false
            if (value.delete) return value.type == null && value.data == null
            val data = value.data ?: return false
            return when (value.type) {
                "string" -> data.length <= MAX_REG_STRING && !hasControl(data)
                else -> REG_DATA[value.type]?.matches(data) == true
            }
        }

        private fun isValidRegistry(op: Op): Boolean =
            isValidRegKey(op.hive, op.key) &&
                op.values.size in 1..MAX_REG_VALUES &&
                op.values.all(::isValidRegValue) &&
                op.values.groupBy { it.name.lowercase() }.none { it.value.size > 1 }

        private fun isValid(op: Op): Boolean {
            if (op.isRegistry) return isValidRegistry(op)
            return op.op == OP_REPLACE &&
                SupportFilesRequest.isSafePath(op.path) &&
                READ_ONLY_TOKENS.none { op.path.startsWith(it, ignoreCase = true) } &&
                isSha256(op.originalSha256) &&
                isSha256(op.sha256) &&
                op.size in 0..SupportFilesRequest.MAX_BYTES &&
                op.artifactUrl?.startsWith("https://") == true
        }

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
                if (item?.optString("op") == OP_REGMERGE) {
                    val op = parseRegistry(item)
                    if (!isValid(op)) applicable = false
                    ops += op
                    continue
                }
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
            if (ops.groupBy { (if (it.isRegistry) "reg:" else "") + it.path.replace('\\', '/').lowercase() }.any { it.value.size > 1 }) applicable = false
            return SupportPatch(
                patchsetId = patchsetId!!,
                summary = cardClean(json.cardText("summary"), MAX_SUMMARY),
                ops = ops,
                rerun = json.optBoolean("rerun", false),
                run = DebugRunParams.fromJson(json.optJSONObject("run")),
                applicable = applicable,
            )
        }

        private fun parseRegistry(item: JSONObject): Op {
            val hive = item.optString("hive").takeIf { item.has("hive") }
            val key = item.optString("key").takeIf { item.has("key") }
            val array = item.optJSONArray("values")
            val values = mutableListOf<RegValue>()
            var broken = array == null || array.length() > MAX_REG_VALUES
            for (i in 0 until minOf(array?.length() ?: 0, MAX_REG_VALUES)) {
                val value = array?.optJSONObject(i)
                val name = value?.opt("name") as? String
                if (value == null || name == null) {
                    broken = true
                    continue
                }
                val delete = value.optBoolean("delete", false)
                values += RegValue(
                    name = name,
                    type = (value.opt("type") as? String).takeIf { !delete || value.has("type") },
                    data = (value.opt("data") as? String).takeIf { !delete || value.has("data") },
                    delete = delete,
                )
            }
            return Op(
                path = "${hive ?: ""}\\${key ?: ""}",
                op = OP_REGMERGE,
                originalSha256 = "",
                sha256 = "",
                size = -1L,
                artifactKey = null,
                artifactUrl = null,
                hive = hive,
                key = key,
                values = if (broken) emptyList() else values,
            )
        }
    }
}
