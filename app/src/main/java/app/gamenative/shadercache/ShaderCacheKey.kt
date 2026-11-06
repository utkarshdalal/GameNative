package app.gamenative.shadercache

import app.gamenative.utils.ModDllOverrides
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Identifies a shared shader pool: the game plus the translation layers whose SPIR-V it records.
 *
 * game:  steam:<appid>, gog:<id>, epic:<id>, amazon:<id> from the container id; custom games use
 *        custom:<sha256 of the exe size in decimal, a newline, then the first 1 MiB of the exe>.
 * dxvk:  dxvk-<version> from dxwrapperConfig "version", lowercased, with a leading "dxvk-" and a
 *        trailing "-gplasync" / "-async" removed (2.6.1-gplasync -> dxvk-2.6.1).
 * vkd3d: vkd3d-<version> from dxwrapperConfig "vkd3dVersion", lowercased, with a leading
 *        "vkd3d-proton-" or "vkd3d-" removed (2.14.1 -> vkd3d-2.14.1).
 */
data class ShaderCacheKey(val game: String, val dxvk: String, val vkd3d: String) {

    val fileName: String
        get() = "$game-$dxvk-$vkd3d".replace(UNSAFE_FILE_CHARS, "_")

    companion object {
        private const val CUSTOM_HASH_BYTES = 1 shl 20
        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")
        private val DXVK_SUFFIX = Regex("-(gplasync|async)$")
        private val STORE_PREFIXES = listOf(
            "STEAM_" to "steam",
            "GOG_" to "gog",
            "EPIC_" to "epic",
            "AMAZON_" to "amazon",
        )

        fun of(container: Container): ShaderCacheKey? {
            val game = gameKey(container) ?: return null
            val config = KeyValueSet(container.dxWrapperConfig)
            return ShaderCacheKey(game, dxvkKey(config.get("version")), vkd3dKey(config.get("vkd3dVersion")))
        }

        fun dxvkKey(version: String?): String =
            "dxvk-" + clean(version).removePrefix("dxvk-").replace(DXVK_SUFFIX, "").ifEmpty { "none" }

        fun vkd3dKey(version: String?): String =
            "vkd3d-" + clean(version).removePrefix("vkd3d-proton-").removePrefix("vkd3d-").ifEmpty { "none" }

        private fun clean(version: String?): String = version.orEmpty().trim().lowercase(Locale.ROOT)

        private fun gameKey(container: Container): String? {
            val id = container.id.substringBefore('(')
            if (id.startsWith("CUSTOM_GAME_")) return customGameKey(container)
            for ((prefix, store) in STORE_PREFIXES) {
                if (!id.startsWith(prefix)) continue
                val gameId = id.removePrefix(prefix)
                return if (gameId.isNotEmpty() && gameId.all { it.isDigit() }) "$store:$gameId" else null
            }
            return null
        }

        private fun customGameKey(container: Container): String? {
            val drives = Container.drivesIterator(container.drives).asSequence().associate { it[0] to File(it[1]) }
            val exe = ModDllOverrides.resolveExecutable(
                container.executablePath,
                drives,
                File(container.rootDir, ".wine/drive_c"),
            ) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("${exe.length()}\n".toByteArray())
            exe.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = CUSTOM_HASH_BYTES
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            return "custom:" + digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
