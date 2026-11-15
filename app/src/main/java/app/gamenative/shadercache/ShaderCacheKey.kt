package app.gamenative.shadercache

import app.gamenative.PrefManager
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ModDllOverrides
import com.winlator.container.Container
import com.winlator.core.GPUHelper
import com.winlator.core.KeyValueSet
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Identifies the shared shader pools of a container: the game plus the version of each translation layer the container
 * loads, or null for a layer it does not load. Recordings made on one layer version are of little use on another, so
 * each (game, layer version) is a pool of its own ([pools]).
 *
 * game:  steam:<appid>, gog:<id>, epic:<appName>, amazon:<product uuid>, resolved from the container id
 *        (Epic and Amazon container ids are local database ids); custom games use
 *        custom:<sha256 of the exe size in decimal, a newline, then the first 1 MiB of the exe>.
 * dxvk:  dxvk-<major.minor.patch>, vkd3d: vkd3d-<major.minor.patch>, from the first "<n>.<n>[.<n>]" in the configured
 *        version (async-1.10.3 -> dxvk-1.10.3, 3.0b -> vkd3d-3.0.0), unless a recording showed which version the
 *        layer reports for this configuration ([withObserved]).
 */
data class ShaderCacheKey(val game: String, val dxvk: String?, val vkd3d: String?) {

    val pools: List<ShaderPool>
        get() = listOfNotNull(dxvk, vkd3d).map { ShaderPool(game, it) }

    /** Replaces the configured versions with those [observed] (see [recordObserved]) under the same [signature]. */
    internal fun withObserved(observed: String, signature: String): ShaderCacheKey {
        val json = runCatching { JSONObject(observed) }.getOrNull()?.takeIf { it.optString("wrapper") == signature }
            ?: return this
        return copy(
            dxvk = dxvk?.let { json.optString("dxvk").ifEmpty { it } },
            vkd3d = vkd3d?.let { json.optString("vkd3d").ifEmpty { it } },
        )
    }

    companion object {
        private const val CUSTOM_HASH_BYTES = 1 shl 20
        private const val CUSTOM_GAME_PREFIX = "CUSTOM_GAME_"
        private const val EXTRA_OBSERVED = "fossilizeLayers"
        private const val VKD3D_MIN_DXVK = "2.6.1-gplasync"
        private val VERSION = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
        private val VORTEK_LIKE_DRIVERS = setOf("vortek", "adreno", "sd-8-elite")
        private val STORE_GAME_ID = Regex("[0-9A-Za-z_-]{1,64}")
        private val STORE_PREFIXES = listOf(
            "STEAM_" to "steam",
            "GOG_" to "gog",
            "EPIC_" to "epic",
            "AMAZON_" to "amazon",
        )

        fun of(container: Container): ShaderCacheKey? = gameKey(container)?.let { of(it, container) }

        /** The key of [game] in [container], or with the default settings a new container gets when it is null. */
        fun of(game: String, container: Container?): ShaderCacheKey = if (container == null) {
            of(game, PrefManager.dxWrapper, PrefManager.dxWrapperConfig, PrefManager.graphicsDriver)
        } else {
            of(game, container.dxWrapper, container.dxWrapperConfig, container.graphicsDriver)
                .withObserved(container.getExtra(EXTRA_OBSERVED, ""), wrapperSignature(container))
        }

        /**
         * The key of [game] in a container with these settings: the layers its wrapper loads, at the versions the launch
         * extracts (a vkd3d container also loads a DXVK for D3D9-11).
         */
        fun of(
            game: String,
            dxWrapper: String,
            dxWrapperConfig: String,
            graphicsDriver: String,
            oldVulkan: () -> Boolean = { GPUHelper.vkGetApiVersionSafe() < GPUHelper.vkMakeVersion(1, 3, 0) },
        ): ShaderCacheKey {
            val config = KeyValueSet(dxWrapperConfig)
            return when (dxWrapper.substringBefore('-')) {
                "dxvk" -> ShaderCacheKey(game, dxvkKey(config.get("version")), null)
                "vkd3d" -> {
                    val configured = config.get("version", VKD3D_MIN_DXVK)
                    val dxvk = when {
                        graphicsDriver in VORTEK_LIKE_DRIVERS && oldVulkan() -> "1.10.3"
                        (triple(configured) ?: Triple(0, 0, 0)).let { (a, b, _) -> a > 2 || (a == 2 && b >= 1) } -> configured
                        else -> VKD3D_MIN_DXVK
                    }
                    ShaderCacheKey(game, dxvkKey(dxvk), vkd3dKey(config.get("vkd3dVersion")))
                }
                else -> ShaderCacheKey(game, null, null)
            }
        }

        /** Identifies the settings an observed layer version holds for. */
        fun wrapperSignature(container: Container): String {
            val config = KeyValueSet(container.dxWrapperConfig)
            return listOf(container.dxWrapper, config.get("version"), config.get("vkd3dVersion"), container.graphicsDriver)
                .joinToString("|")
        }

        /**
         * Stores the layer versions a recording made under [signature] reported, for the next [of], while [signature]
         * is still the container's.
         */
        fun recordObserved(container: Container, signature: String, dxvk: String?, vkd3d: String?) {
            if ((dxvk == null && vkd3d == null) || signature != wrapperSignature(container)) return
            val previous = runCatching { JSONObject(container.getExtra(EXTRA_OBSERVED, "")) }.getOrNull()
                ?.takeIf { it.optString("wrapper") == signature }
            val value = JSONObject()
                .put("wrapper", signature)
                .put("dxvk", dxvk ?: previous?.optString("dxvk").orEmpty())
                .put("vkd3d", vkd3d ?: previous?.optString("vkd3d").orEmpty())
                .toString()
            if (value == container.getExtra(EXTRA_OBSERVED, "")) return
            container.putExtra(EXTRA_OBSERVED, value)
            container.saveData()
        }

        /** The game key for a store game id that is already the stable one (for Amazon, see [amazonGameId]). */
        fun storeGame(store: String, gameId: String): String? =
            if (STORE_GAME_ID.matches(gameId)) "$store:$gameId" else null

        fun amazonGameId(productId: String): String = productId.substringAfterLast('.')

        fun dxvkKey(version: String?): String? = triple(version)?.let { "dxvk-" + it.toList().joinToString(".") }

        fun vkd3dKey(version: String?): String? = triple(version)?.let { "vkd3d-" + it.toList().joinToString(".") }

        /** The first "<n>.<n>[.<n>]" in [version], with a missing patch as 0. */
        private fun triple(version: String?): Triple<Int, Int, Int>? {
            val match = VERSION.find(version.orEmpty()) ?: return null
            val (major, minor, patch) = match.destructured
            return Triple(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: 0)
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

/** One shared shader pool: the recordings of [game] made on one layer version, such as dxvk-2.6.1. */
data class ShaderPool(val game: String, val layer: String) {

    val fileName: String
        get() = "$game-$layer".replace(UNSAFE_FILE_CHARS, "_")

    companion object {
        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")
        private val LAYER_FILE_SUFFIX = Regex("(?:dxvk|vkd3d)-\\d+\\.\\d.*")

        private fun safeName(game: String) = game.replace(UNSAFE_FILE_CHARS, "_")

        /**
         * Whether [fileName] is a pool file of [game] for any layer version, or one from when a game had a single
         * pool. Store game ids contain no dot, so neither the game's own name nor a layer version that follows it can
         * be mistaken for another game's.
         */
        fun isFileOf(game: String, fileName: String): Boolean {
            val name = safeName(game)
            if (fileName.substringBefore('.') == name) return true
            val rest = fileName.removePrefix("$name-")
            return rest != fileName && LAYER_FILE_SUFFIX.matches(rest)
        }
    }
}
