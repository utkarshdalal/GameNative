package app.gamenative.shaders

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Catalog metadata generated at build time by tools/shaders/sync_slang_shaders.py.
 * The APK ships NO shader files ("nenhum shader deve vir instalado") — only this
 * manifest, so the full libretro/slang-shaders catalog is browsable instantly and
 * files are delivered on demand (see [ShaderPack]).
 */
@Serializable
data class CatalogSource(
    val repo: String = "",
    val ref: String = "",
    val commit: String = "",
    val generated: String = "",
    /** Total uncompressed size of the on-demand pack (dependency-closure union). */
    val packBytes: Long = 0,
)

@Serializable
data class ShaderFamily(
    val name: String,
    val count: Int,
)

@Serializable
data class ShaderPreset(
    val path: String,
    val family: String,
    val subfolder: String? = null,
    val passes: Int = 0,
    val bytes: Long = 0,
    /**
     * Every repo-relative file this preset needs (its own .slangp, .slang passes,
     * #include headers, LUT images, #reference presets). The app downloads ONLY these
     * files, on demand, reusing whatever is already cached (user decision 2026-08-12).
     */
    val deps: List<String> = emptyList(),
    /** Preset whose upstream closure has unresolved references (missing/escaping files). */
    val broken: Boolean = false,
)

@Serializable
data class ShaderCatalogData(
    val source: CatalogSource = CatalogSource(),
    val families: List<ShaderFamily> = emptyList(),
    /** Union of every file any preset depends on — the exact extraction whitelist. */
    val files: List<String> = emptyList(),
    val presets: List<ShaderPreset> = emptyList(),
)

/**
 * Parsed, queryable shader catalog. Immutable after construction; every lookup is
 * O(n)-ish over metadata only (no filesystem walks, no file reads) so the UI can
 * page and search freely without touching the pack.
 */
class ShaderCatalog private constructor(val data: ShaderCatalogData) {

    private val byPath: Map<String, ShaderPreset> = data.presets.associateBy { it.path }

    val presets: List<ShaderPreset> get() = data.presets
    val families: List<ShaderFamily> get() = data.families
    val packFiles: List<String> get() = data.files

    fun preset(path: String): ShaderPreset? = byPath[path]

    /** True when the preset is usable at all (its files exist in the repo). */
    fun isUsable(preset: ShaderPreset): Boolean = !preset.broken

    /** Subfolders that contain presets in [family], sorted. */
    fun subfolders(family: String): List<String> =
        data.presets.asSequence()
            .filter { it.family == family && !it.subfolder.isNullOrBlank() }
            .map { it.subfolder!! }
            .distinct()
            .sorted()
            .toList()

    fun presetsIn(family: String, subfolder: String? = null): List<ShaderPreset> =
        data.presets.filter { it.family == family && (subfolder == null || it.subfolder == subfolder) }

    /** Global search over friendly name, family and raw path (case-insensitive). */
    fun search(query: String): List<ShaderPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return data.presets.filter {
            friendlyName(it.path).lowercase().contains(q) ||
                it.family.lowercase().contains(q) ||
                it.path.lowercase().contains(q)
        }
    }

    /** First pageSize items of [items] starting at 0-based [page]. */
    fun page(items: List<ShaderPreset>, page: Int, pageSize: Int): List<ShaderPreset> {
        val from = (page * pageSize).coerceAtLeast(0)
        if (from >= items.size) return emptyList()
        return items.subList(from, minOf(from + pageSize, items.size))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ShaderCatalog =
            ShaderCatalog(json.decodeFromString<ShaderCatalogData>(text))

        fun load(context: Context): ShaderCatalog? = runCatching {
            context.assets.open("retroarch/catalog.json").bufferedReader().use { parse(it.readText()) }
        }.getOrNull()
    }
}

/** Friendly display name for a preset key, e.g. {@code crt/easymode.slangp} -> "Easymode". */
fun friendlyName(key: String): String {
    val base = key.substringAfterLast('/').substringBeforeLast('.')
    if (base.isBlank()) return key
    return base.split('_', '-', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
