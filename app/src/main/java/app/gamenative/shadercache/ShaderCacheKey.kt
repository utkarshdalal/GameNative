package app.gamenative.shadercache

import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ModDllOverrides
import com.winlator.container.Container
import com.winlator.core.KeyValueSet
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Identifies a shared shader pool: the game. Fossilize entries are content-addressed, so one pool
 * per game holds the pipelines of every DXVK and vkd3d version.
 *
 * game:  steam:<appid>, gog:<id>, epic:<appName>, amazon:<product uuid>, resolved from the container id
 *        (Epic and Amazon container ids are local database ids); custom games use
 *        custom:<sha256 of the exe size in decimal, a newline, then the first 1 MiB of the exe>.
 *
 * The translation layer versions are sent with an upload only to describe it:
 * dxvk:  dxvk-<version> from dxwrapperConfig "version": the leading version number after an optional
 *        "dxvk-" / "v" (2.7.1-1-gplasync-0 -> dxvk-2.7.1); anything else is kept lowercased.
 * vkd3d: vkd3d-<version> from dxwrapperConfig "vkd3dVersion": the leading version number and any
 *        letter suffix after an optional "vkd3d-proton-" / "vkd3d-" / "v" (vkd3d-proton-2.14.1 ->
 *        vkd3d-2.14.1, 3.0b -> vkd3d-3.0b).
 */
data class ShaderCacheKey(val game: String) {

    val fileName: String
        get() = game.replace(UNSAFE_FILE_CHARS, "_")

    companion object {
        private const val CUSTOM_HASH_BYTES = 1 shl 20
        private const val CUSTOM_GAME_PREFIX = "CUSTOM_GAME_"
        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")
        private val DXVK_VERSION = Regex("^(?:dxvk-?)?v?(\\d+\\.\\d+(?:\\.\\d+)?)")
        private val VKD3D_VERSION = Regex("^(?:vkd3d-?)?(?:proton-?)?v?(\\d+\\.\\d+(?:\\.\\d+)?[a-z]*)")
        private val STORE_GAME_ID = Regex("[0-9A-Za-z_-]{1,64}")
        private val STORE_PREFIXES = listOf(
            "STEAM_" to "steam",
            "GOG_" to "gog",
            "EPIC_" to "epic",
            "AMAZON_" to "amazon",
        )

        fun of(container: Container): ShaderCacheKey? = gameKey(container)?.let(::ShaderCacheKey)

        /** The dxvk and vkd3d versions [container] runs, as described above. */
        fun layerVersions(container: Container): Pair<String, String> {
            val config = KeyValueSet(container.dxWrapperConfig)
            return dxvkKey(config.get("version")) to vkd3dKey(config.get("vkd3dVersion"))
        }

        /** The game key for a store game id that is already the stable one (for Amazon, see [amazonGameId]). */
        fun storeGame(store: String, gameId: String): String? =
            if (STORE_GAME_ID.matches(gameId)) "$store:$gameId" else null

        fun amazonGameId(productId: String): String = productId.substringAfterLast('.')

        fun dxvkKey(version: String?): String = "dxvk-" + normalize(version, DXVK_VERSION)

        fun vkd3dKey(version: String?): String = "vkd3d-" + normalize(version, VKD3D_VERSION)

        private fun normalize(version: String?, pattern: Regex): String {
            val clean = version.orEmpty().trim().lowercase(Locale.ROOT)
            return pattern.find(clean)?.groupValues?.get(1) ?: clean.ifEmpty { "none" }
        }

        fun isCustomGame(container: Container): Boolean = container.id.startsWith(CUSTOM_GAME_PREFIX)

        private fun gameKey(container: Container): String? {
            if (isCustomGame(container)) return customGameKey(container)
            return storeGameKey(container.id.substringBefore('('))
        }

        internal fun storeGameKey(id: String, stableId: (String, String) -> String? = ::stableStoreId): String? {
            for ((prefix, store) in STORE_PREFIXES) {
                if (!id.startsWith(prefix)) continue
                val gameId = stableId(store, id.removePrefix(prefix)) ?: return null
                return storeGame(store, gameId)
            }
            return null
        }

        private fun stableStoreId(store: String, localId: String): String? = when (store) {
            "epic" -> localId.toIntOrNull()?.let { EpicService.getEpicGameOf(it) }?.appName
            "amazon" -> localId.toIntOrNull()?.let { AmazonService.getAmazonGameByAppId(it) }?.productId?.let(::amazonGameId)
            else -> localId
        }

        private fun customGameKey(container: Container): String? {
            val drives = Container.drivesIterator(container.drives).asSequence().associate { it[0] to File(it[1]) }
            val exe = ModDllOverrides.resolveExecutable(
                container.executablePath,
                drives,
                File(container.rootDir, ".wine/drive_c"),
            ) ?: return null
            return customExeKey(exe)
        }

        internal fun customExeKey(exe: File): String {
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
