package app.gamenative.optimize

import app.gamenative.ui.screen.support.WineRegistryText
import com.winlator.container.Container
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

internal object OptimizeFiles {

    const val FPS_LIMITER_ENABLED_EXTRA = "fpsLimiterEnabled"
    const val FPS_LIMITER_TARGET_EXTRA = "fpsLimiterTarget"

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.gnopt.tmp")
        try {
            tmp.writeBytes(bytes)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun userHive(container: Container): File = File(container.rootDir, ".wine/user.reg")

    /** The guest's HOME is the imagefs home/xuser link, which points at the active container's root. */
    fun drirc(container: Container): File = File(container.rootDir, ".drirc")

    fun readHive(file: File): String = file.readText(Charsets.ISO_8859_1)

    fun writeHive(file: File, text: String) = atomicWrite(file, text.toByteArray(Charsets.ISO_8859_1))

    /** The game's detected anti-cheat names; null when the game files have not been scanned yet. */
    fun anticheat(container: Container): List<String>? {
        val file = File(container.rootDir, "file_detection.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()).optJSONArray("anticheat").strings() }.getOrNull()
    }

    private fun parseInteger(data: String): Long {
        val t = data.trim()
        return if (t.startsWith("0x", ignoreCase = true)) java.lang.Long.parseUnsignedLong(t.substring(2), 16) else t.toLong()
    }

    /**
     * Encodes a lever registry value: dword and qword data are decimal (or 0x-prefixed hex) integers, sz is text,
     * binary_f64le is a decimal number stored as 8 little-endian IEEE-754 bytes typed REG_DWORD (`hex(4):`), the way Unity
     * PlayerPrefs writes doubles.
     */
    fun encodeRegistryValue(type: String, data: String): String = when (type) {
        "dword" -> {
            val v = parseInteger(data)
            require(v in Int.MIN_VALUE.toLong()..0xFFFFFFFFL) { "dword out of range" }
            WineRegistryText.encodeValue("dword", java.lang.Long.toHexString(v and 0xFFFFFFFFL))
        }
        "qword" -> WineRegistryText.encodeValue("qword", java.lang.Long.toHexString(parseInteger(data)))
        "sz" -> WineRegistryText.encodeValue("string", data)
        "binary_f64le" -> {
            val bits = java.lang.Double.doubleToRawLongBits(data.trim().toDouble())
            val hex = (0 until 8).joinToString("") { "%02x".format((bits ushr (8 * it)) and 0xff) }
            WineRegistryText.encodeValue("binary_dword", hex)
        }
        else -> throw IllegalArgumentException("unsupported registry type $type")
    }

    private fun hexBytes(canonical: String, prefix: String): List<Int>? {
        if (!canonical.startsWith(prefix)) return null
        return canonical.substring(prefix.length).split(',').filter { it.isNotEmpty() }.map { it.toIntOrNull(16) ?: return null }
    }

    private fun littleEndian(bytes: List<Int>): Long = bytes.foldIndexed(0L) { i, acc, b -> acc or (b.toLong() shl (8 * i)) }

    /** A user.reg value as lever data of [type] (the inverse of [encodeRegistryValue]); null when it is stored as another type. */
    fun decodeRegistryValue(type: String, raw: String): String? {
        val c = WineRegistryText.canonical(raw)
        return when (type) {
            "dword" -> c.takeIf { it.startsWith("dword:") }?.substring(6)?.toLongOrNull()?.toString()
            "qword" -> hexBytes(c, "hex(b):")?.takeIf { it.size == 8 }?.let { java.lang.Long.toUnsignedString(littleEndian(it)) }
            "sz" -> c.takeIf { it.startsWith("sz:") }?.substring(3)
            "binary_f64le" -> hexBytes(c, "hex(4):")?.takeIf { it.size == 8 }?.let { java.lang.Double.longBitsToDouble(littleEndian(it)).toString() }
            else -> null
        }
    }
}
