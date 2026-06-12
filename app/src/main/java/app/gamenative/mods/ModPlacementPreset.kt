package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import java.util.Locale

data class ModPlacementPreset(
    val id: String,
    val label: String,
    val description: String,
    val drafts: List<ModPlacementPresetDraft>,
)

data class ModPlacementPresetDraft(
    val sourceSubpath: String,
    val targetRelativePath: String,
    val mode: String = ModPlacementMode.OVERWRITE_COPY.name,
    val includeSourceDirectory: Boolean = false,
)

object ModPlacementPresetDetector {
    private val bethesdaContentDirs = setOf(
        "meshes",
        "textures",
        "scripts",
        "interface",
        "sound",
        "seq",
        "skse",
        "f4se",
        "sfse",
        "strings",
        "video",
        "music",
        "lodsettings",
        "calientetools",
        "nemesis_engine",
    )

    fun detect(gameName: String, entries: List<ModArchiveEntry>): List<ModPlacementPreset> {
        if (entries.isEmpty()) return emptyList()

        val normalizedEntries = entries.map { it.copy(path = normalizePath(it.path)) }
        val presets = mutableListOf<ModPlacementPreset>()

        detectBethesda(gameName, normalizedEntries)?.let(presets::add)
        detectBepInEx(normalizedEntries)?.let(presets::add)
        detectMelonLoader(normalizedEntries)?.let(presets::add)
        detectUnreal(normalizedEntries)?.let(presets::add)
        detectRedmod(gameName, normalizedEntries)?.let(presets::add)

        return presets.distinctBy { it.id }
    }

    private fun detectBethesda(
        gameName: String,
        entries: List<ModArchiveEntry>,
    ): ModPlacementPreset? {
        val game = BethesdaPluginManager.detectGame(gameName) ?: return null
        if (entries.hasFomodInstaller()) return null

        val dataPath = entries.findDirectoryPath("Data")
        val sources = entries.bethesdaSourceRoots()

        if (dataPath == null && sources.isEmpty()) return null

        val source = when {
            dataPath != null -> dataPath
            sources.isNotEmpty() -> ModPlacementSources.encode(sources)
            else -> ""
        }
        return ModPlacementPreset(
            id = "bethesda-data",
            label = "Bethesda Data folder",
            description = "For Skyrim, Fallout, Oblivion, Morrowind, and Starfield mods.",
            drafts = listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = source,
                    targetRelativePath = game.dataDirName,
                    includeSourceDirectory = dataPath == null && sources.any(::isTopLevelBethesdaContentDir),
                ),
            ),
        )
    }

    private fun detectBepInEx(entries: List<ModArchiveEntry>): ModPlacementPreset? {
        val source = entries.findDirectoryPath("BepInEx") ?: return null
        return ModPlacementPreset(
            id = "bepinex",
            label = "BepInEx mod",
            description = "Places BepInEx plugins, patchers, config, and related folders into BepInEx.",
            drafts = listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = source,
                    targetRelativePath = "BepInEx",
                    includeSourceDirectory = false,
                ),
            ),
        )
    }

    private fun detectMelonLoader(entries: List<ModArchiveEntry>): ModPlacementPreset? {
        val drafts = listOf("Mods", "UserData", "Plugins")
            .mapNotNull { name ->
                entries.findDirectoryPath(name)?.let { source ->
                    ModPlacementPresetDraft(
                        sourceSubpath = source,
                        targetRelativePath = name,
                        includeSourceDirectory = false,
                    )
                }
            }
        if (drafts.isEmpty()) return null
        return ModPlacementPreset(
            id = "melonloader",
            label = "MelonLoader mod",
            description = "Places MelonLoader Mods, Plugins, or UserData folders into the game folder.",
            drafts = drafts,
        )
    }

    private fun detectUnreal(entries: List<ModArchiveEntry>): ModPlacementPreset? {
        val contentPaks = entries.findDirectoryPath("Content/Paks")
        val paks = entries.findDirectoryPath("Paks")
        val loosePakFiles = entries
            .filterNot { it.directory }
            .map { it.path }
            .filter { path ->
                path.count { it == '/' } == 0 &&
                    listOf(".pak", ".ucas", ".utoc").any { path.endsWith(it, ignoreCase = true) }
            }

        val source = when {
            contentPaks != null -> contentPaks
            paks != null -> paks
            loosePakFiles.isNotEmpty() -> ModPlacementSources.encode(loosePakFiles)
            else -> return null
        }
        return ModPlacementPreset(
            id = "unreal-paks",
            label = "Unreal Engine Paks",
            description = "Places Pak, UCAS, and UTOC files into Content/Paks.",
            drafts = listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = source,
                    targetRelativePath = "Content/Paks",
                    includeSourceDirectory = false,
                ),
            ),
        )
    }

    private fun detectRedmod(
        gameName: String,
        entries: List<ModArchiveEntry>,
    ): ModPlacementPreset? {
        val looksLikeCyberpunk = gameName.lowercase(Locale.US).let { name ->
            name.contains("cyberpunk") || name.contains("redmod")
        }
        if (!looksLikeCyberpunk) return null
        val targets = listOf(
            "archive/pc/mod",
            "r6",
            "red4ext",
            "bin/x64/plugins",
            "mods",
        )
        val drafts = targets.mapNotNull { target ->
            entries.findDirectoryPath(target)?.let { source ->
                ModPlacementPresetDraft(
                    sourceSubpath = source,
                    targetRelativePath = target,
                    includeSourceDirectory = false,
                )
            }
        }
        if (drafts.isEmpty()) return null
        return ModPlacementPreset(
            id = "redmod",
            label = "Cyberpunk / REDmod",
            description = "Places archive, r6, red4ext, bin plugin, or REDmod folders into the game.",
            drafts = drafts,
        )
    }

    private fun List<ModArchiveEntry>.findDirectoryPath(vararg suffixes: String): String? {
        val normalizedSuffixes = suffixes.map(::normalizePath)
        return asSequence()
            .map { it.path }
            .flatMap { path ->
                normalizedSuffixes.asSequence().mapNotNull { suffix ->
                    when {
                        path.equals(suffix, ignoreCase = true) -> path
                        path.endsWith("/$suffix", ignoreCase = true) -> path
                        path.startsWith("$suffix/", ignoreCase = true) -> suffix
                        path.contains("/$suffix/", ignoreCase = true) -> {
                            path.substringBefore("/$suffix/") + "/$suffix"
                        }
                        else -> null
                    }
                }
            }
            .firstOrNull()
    }

    private fun List<ModArchiveEntry>.hasFomodInstaller(): Boolean =
        any { normalizePath(it.path).endsWith("fomod/ModuleConfig.xml", ignoreCase = true) }

    private fun List<ModArchiveEntry>.bethesdaSourceRoots(): List<String> {
        val roots = mapNotNull { entry ->
            val path = normalizePath(entry.path)
            val segments = path.split('/').filter { it.isNotBlank() }
            val contentIndex = segments.indexOfFirst { it.lowercase(Locale.US) in bethesdaContentDirs }
            when {
                contentIndex == 0 -> segments.first()
                contentIndex > 0 -> segments.take(contentIndex).joinToString("/")
                !entry.directory && path.endsWithBethesdaDataFile() -> {
                    if (segments.size == 1) path else segments.dropLast(1).joinToString("/")
                }
                else -> null
            }
        }.distinct()

        return roots.filterNot { source ->
            roots.any { other -> source != other && source.startsWith("$other/", ignoreCase = true) }
        }
    }

    private fun isTopLevelBethesdaContentDir(source: String): Boolean {
        val normalized = normalizePath(source)
        return normalized.isNotBlank() &&
            !normalized.contains("/") &&
            normalized.lowercase(Locale.US) in bethesdaContentDirs
    }

    private fun String.endsWithBethesdaDataFile(): Boolean =
        listOf(".esp", ".esm", ".esl", ".bsa", ".ba2").any { endsWith(it, ignoreCase = true) }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")
}
