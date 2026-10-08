package app.gamenative.service.rockstar

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Cloud-save fields of a game's `title.rgl` (RGLM v1: 80-byte header, then a 16-byte IV and an
 * AES-256-CBC payload under an all-zero key holding launcher-style lenient JSON).
 */
data class RockstarTitleMetadata(
    val titleId: String,
    val rosTitleId: Int,
    val gamePlatform: String?,
    val appdataFolderName: String?,
    val cloudSaveRoot: String?,
    val cloudSaveFolder: String?,
    val cloudSaveFiles: List<String>,
    val cloudSaveCompression: Boolean,
    val platformCloud: Map<String, Boolean>,
) {
    val saveFolderName: String? get() = cloudSaveFolder ?: appdataFolderName

    fun cloudEnabled(platform: String = gamePlatform ?: "default"): Boolean =
        platformCloud[platform] ?: platformCloud["default"] ?: false

    companion object {
        private const val FILE_NAME = "title.rgl"
        private const val HEADER = 80
        private const val MAX_SIZE = 1024 * 1024
        private const val MAX_DEPTH = 3
        private val MAGIC = byteArrayOf(82, 71, 76, 77)

        fun find(gameDir: File): File? {
            if (!gameDir.isDirectory) return null
            var level = listOf(gameDir)
            repeat(MAX_DEPTH + 1) {
                level.firstNotNullOfOrNull(::titleIn)?.let { return it }
                level = level.flatMap { dir ->
                    dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                }
                if (level.isEmpty()) return null
            }
            return null
        }

        fun parse(file: File): RockstarTitleMetadata? = runCatching {
            if (!file.isFile || file.length() > MAX_SIZE) return null
            parse(file.readBytes())
        }.getOrNull()

        fun parse(bytes: ByteArray): RockstarTitleMetadata? = runCatching {
            decrypt(bytes)?.let { fromJson(it) }
        }.getOrNull()

        private fun titleIn(dir: File): File? =
            dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.equals(FILE_NAME, true) && hasMagic(it) }

        private fun hasMagic(file: File): Boolean = runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && magic.contentEquals(MAGIC)
            }
        }.getOrDefault(false)

        private fun le32(data: ByteArray, offset: Int): Long =
            (0 until 4).fold(0L) { acc, i -> acc or ((data[offset + i].toLong() and 0xff) shl (8 * i)) }

        private fun decrypt(data: ByteArray): String? {
            if (data.size < HEADER + 32 || data.size > MAX_SIZE) return null
            if (!data.copyOfRange(0, 4).contentEquals(MAGIC) || le32(data, 4) != 1L) return null
            val length = le32(data, 8)
            if (length < 32 || length % 16 != 0L || length != (data.size - HEADER).toLong()) return null
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(ByteArray(32), "AES"),
                IvParameterSpec(data.copyOfRange(HEADER, HEADER + 16)),
            )
            val clear = cipher.doFinal(data, HEADER + 16, data.size - HEADER - 16)
            var end = clear.size
            val pad = clear.last().toInt() and 0xff
            if (pad in 1..16 && (clear.size - pad until clear.size).all { (clear[it].toInt() and 0xff) == pad }) {
                end -= pad
            } else {
                while (end > 0 && clear[end - 1].toInt() == 0) end--
            }
            return String(clear, 0, end, Charsets.UTF_8).removePrefix("\uFEFF")
        }

        private fun fromJson(text: String): RockstarTitleMetadata? {
            val json = JSONObject(normalize(text) ?: return null)
            val titleId = json.string("titleId") ?: return null
            val rosTitleId = (json.opt("rosTitleId") as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
            val files = json.optJSONArray("cloudSaveFiles")?.let { array ->
                (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val platformCloud = json.optJSONObject("platforms")?.let { platforms ->
                platforms.keys().asSequence().mapNotNull { name ->
                    (platforms.optJSONObject(name)?.opt("cloud") as? Boolean)?.let { name to it }
                }.toMap()
            }.orEmpty()
            return RockstarTitleMetadata(
                titleId = titleId,
                rosTitleId = rosTitleId,
                gamePlatform = json.string("gamePlatform"),
                appdataFolderName = json.string("appdataFolderName"),
                cloudSaveRoot = json.string("cloudSaveRoot"),
                cloudSaveFolder = json.string("cloudSaveFolder"),
                cloudSaveFiles = files,
                cloudSaveCompression = json.opt("cloudSaveCompression") as? Boolean ?: false,
                platformCloud = platformCloud,
            )
        }

        private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }

        internal fun normalize(input: String): String? {
            val out = StringBuilder(input.length)
            var quoted = false
            var depth = 0
            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    quoted -> {
                        out.append(c)
                        if (c == '\\' && i + 1 < input.length) out.append(input[++i])
                        else if (c == '"') quoted = false
                    }
                    c == '"' -> { quoted = true; out.append(c) }
                    c == '/' && i + 1 < input.length && input[i + 1] == '/' -> {
                        while (i + 1 < input.length && input[i + 1] != '\n') i++
                        out.append(' ')
                    }
                    c == '/' && i + 1 < input.length && input[i + 1] == '*' -> {
                        val end = input.indexOf("*/", i + 2)
                        if (end < 0) return null
                        i = end + 1
                        out.append(' ')
                    }
                    else -> {
                        if (c == '{' || c == '[') { if (++depth > 64) return null }
                        if (c == '}' || c == ']') { if (depth == 0) return null; depth-- }
                        out.append(c)
                    }
                }
                i++
            }
            if (quoted || depth != 0) return null
            quoted = false
            i = 0
            while (i < out.length) {
                val c = out[i]
                if (quoted) {
                    if (c == '\\') i++ else if (c == '"') quoted = false
                } else if (c == '"') {
                    quoted = true
                } else if (c == ',') {
                    var next = i + 1
                    while (next < out.length && out[next] in " \t\r\n") next++
                    if (next < out.length && (out[next] == ']' || out[next] == '}')) out.setCharAt(i, ' ')
                }
                i++
            }
            return out.toString()
        }
    }
}
