package app.gamenative.mods

import java.util.Locale

object NexusCollectionPrioritySuggester {
    fun priorities(files: List<NexusCollectionFile>): Map<String, Int> {
        val ordered = files.sortedWith(compareBy<NexusCollectionFile> { it.position }.thenBy { it.modId }.thenBy { it.fileId })
        val base = ordered.mapIndexed { index, file -> key(file) to index }.toMap().toMutableMap()
        val byModId = ordered.groupBy { it.modId }

        ordered.forEach { file ->
            val dependencyPriority = file.dependencyModIds
                .flatMap { byModId[it].orEmpty() }
                .mapNotNull { base[key(it)] }
                .maxOrNull()
            if (dependencyPriority != null) {
                base[key(file)] = maxOf(base[key(file)] ?: 0, dependencyPriority + ordered.size)
            }
        }

        ordered.forEach { file ->
            if (looksLikePatch(file)) {
                base[key(file)] = (base[key(file)] ?: 0) + ordered.size / 2
            }
        }

        return ordered
            .sortedWith(compareBy<NexusCollectionFile> { base[key(it)] ?: 0 }.thenBy { it.position })
            .mapIndexed { index, file -> key(file) to index }
            .toMap()
    }

    private fun looksLikePatch(file: NexusCollectionFile): Boolean {
        val text = "${file.modName} ${file.fileName}".lowercase(Locale.US)
        return listOf("patch", "compat", "addon", "hotfix", "fixes", "bodyslide", "parallax").any { it in text }
    }

    private fun key(file: NexusCollectionFile): String =
        "${file.gameDomain}:${file.modId}:${file.fileId}:${file.position}"
}
