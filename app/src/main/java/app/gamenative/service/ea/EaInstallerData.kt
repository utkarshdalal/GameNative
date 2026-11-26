package app.gamenative.service.ea

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A game's DiP manifest (`__Installer/installerdata.xml`): version, launchers and touchup installer.
 * Port of Maxima's core/manifest/dip.rs (ArmchairDevelopers/Maxima, GPL-3.0).
 */
data class EaInstallerData(
    val version: String?,
    val launchers: List<Launcher>,
    val touchupPath: String?,
    val touchupParameters: String?,
    val contentIds: List<String>,
    val gameTitle: String?,
) {
    data class Launcher(val path: String, val trial: Boolean, val parameters: String)

    /** First non-trial launcher, as Maxima picks it. */
    val executable: String?
        get() = launchers.firstOrNull { !it.trial }?.path

    companion object {
        const val RELATIVE_PATH = "__Installer/installerdata.xml"

        private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val DIP_ROOT = Regex("<DiPManifest[\\s>/]", RegexOption.IGNORE_CASE)
        private val GAME_VERSION = Regex("<gameVersion\\b[^>]*?\\bversion\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
        private val RUNTIME = Regex("<runtime\\b[^>]*>(.*?)</runtime\\s*>", OPTIONS)
        private val LAUNCHER = Regex("<launcher\\b[^>]*>(.*?)</launcher\\s*>", OPTIONS)
        private val TOUCHUP = Regex("<touchup\\b[^>]*>(.*?)</touchup\\s*>", OPTIONS)
        private val CONTENT_ID = Regex("<contentID>\\s*([^<\\s]+)\\s*</contentID>")
        private val GAME_TITLE = Regex("<gameTitle[^>]*>([^<]+)</gameTitle>")
        private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
        private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

        fun read(installDir: File): EaInstallerData? = file(installDir)?.let { parse(it.readBytes()) }

        /** The installer data file, matched without regard to case as Windows does. */
        fun file(installDir: File): File? {
            var current = installDir
            for (part in RELATIVE_PATH.split('/')) {
                current = current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            }
            return current.takeIf { it.isFile }
        }

        fun parse(bytes: ByteArray): EaInstallerData? {
            val xml = decode(bytes) ?: return null
            if (!DIP_ROOT.containsMatchIn(xml)) return null

            val version = GAME_VERSION.find(xml)?.groupValues?.get(2)?.let(::unescape)?.trim()?.ifEmpty { null }
            val runtime = RUNTIME.find(xml)?.groupValues?.get(1).orEmpty()
            val launchers = LAUNCHER.findAll(runtime).mapNotNull { match ->
                val body = match.groupValues[1]
                val path = element(body, "filePath")?.ifEmpty { null } ?: return@mapNotNull null
                val trial = element(body, "trial")?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: false
                Launcher(path, trial, element(body, "parameters").orEmpty())
            }.toList()
            val touchup = TOUCHUP.find(xml)?.groupValues?.get(1)

            return EaInstallerData(
                version = version,
                launchers = launchers,
                touchupPath = touchup?.let { element(it, "filePath") }?.trimStart('/')?.ifEmpty { null },
                touchupParameters = touchup?.let { element(it, "parameters") },
                contentIds = CONTENT_ID.findAll(xml).map { it.groupValues[1] }.distinct().toList(),
                gameTitle = GAME_TITLE.find(xml)?.groupValues?.get(1)?.let(::unescape),
            )
        }

        /** "[HKEY_...\\Install Dir]Game\\x.exe" -> "Game\\x.exe"; leading slashes removed; uses '\\' separators. */
        fun relativePath(raw: String): String {
            val close = raw.indexOf(']')
            val path = if (raw.startsWith("[") && close > 0) raw.substring(close + 1) else raw
            return path.trim().replace('/', '\\').trimStart('\\')
        }

        /**
         * Touchup arguments with {locale} and "{installLocation}" substituted, as dip.rs collect_touchup_args does.
         * A drive root stays X:\ (a bare "X:" names the drive's current folder), with the backslash
         * doubled inside quotes so it does not escape the closing quote.
         */
        fun touchupArguments(parameters: String, locale: String, installLocationWindows: String): String {
            val trimmed = installLocationWindows.replace('/', '\\').trimEnd('\\')
            val location = if (trimmed.length == 2 && trimmed[1] == ':') "$trimmed\\" else trimmed
            val quoted = if (location.endsWith('\\')) "$location\\" else location
            return parameters.split(" ").joinToString(" ") { arg ->
                arg.replace("{locale}", locale)
                    .replace("\"{installLocation}\"", "\"$quoted\"")
                    .replace("{installLocation}", location)
            }
        }

        private fun element(body: String, name: String): String? {
            val match = Regex("<$name\\b[^>]*>(.*?)</$name\\s*>", OPTIONS).find(body)
                ?: return if (Regex("<$name\\b[^>]*/>", RegexOption.IGNORE_CASE).containsMatchIn(body)) "" else null
            val raw = match.groupValues[1]
            val text = if (CDATA.containsMatchIn(raw)) CDATA.replace(raw) { it.groupValues[1] } else unescape(raw)
            return text.trim()
        }

        private fun unescape(text: String): String = ENTITY.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x", ignoreCase = true)) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                }
            }
        }

        private fun decode(bytes: ByteArray): String? {
            if (bytes.size >= 2) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if (b0 == 0xFF && b1 == 0xFE) return strict(bytes, 2, Charsets.UTF_16LE)
                if (b0 == 0xFE && b1 == 0xFF) return strict(bytes, 2, Charsets.UTF_16BE)
            }
            val utf8Start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            strict(bytes, utf8Start, Charsets.UTF_8)?.let { if (it.indexOf('\u0000') < 0) return it }
            val bigEndian = bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] != 0.toByte()
            return strict(bytes, 0, if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE)?.trimStart('\uFEFF')
        }

        private fun strict(bytes: ByteArray, offset: Int, charset: Charset): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
