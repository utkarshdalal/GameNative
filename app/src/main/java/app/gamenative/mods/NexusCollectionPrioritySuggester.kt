package app.gamenative.mods

import java.util.Locale

object NexusCollectionPrioritySuggester {
    fun priorities(files: List<NexusCollectionFile>): Map<String, Int> {
        return NexusCollectionFileOrdering.orderedFiles(files, preferPatches = true)
            .mapIndexed { index, file -> NexusCollectionFileOrdering.key(file) to index }
            .toMap()
    }
}

internal object NexusCollectionFileOrdering {
    fun orderedFiles(files: List<NexusCollectionFile>, preferPatches: Boolean): List<NexusCollectionFile> {
        val ordered = files.sortedWith(
            compareBy<NexusCollectionFile> { it.position }
                .thenBy { it.gameDomain }
                .thenBy { it.modId }
                .thenBy { it.fileId },
        )
        if (ordered.size < 2) return ordered

        val fileByKey = ordered.associateBy(::key)
        val originalIndexByKey = ordered.mapIndexed { index, file -> key(file) to index }.toMap()
        val byDomainModId = ordered.groupBy { domainModKey(it.gameDomain, it.modId) }
        val dependenciesByKey = ordered.associate { file ->
            val fileKey = key(file)
            val dependencyKeys = file.dependencyModIds
                .flatMap { dependencyModId -> byDomainModId[domainModKey(file.gameDomain, dependencyModId)].orEmpty() }
                .map(::key)
                .filter { it != fileKey }
                .toCollection(linkedSetOf())
            fileKey to dependencyKeys
        }
        val dependentsByKey = mutableMapOf<String, MutableSet<String>>()
        dependenciesByKey.forEach { (fileKey, dependencyKeys) ->
            dependencyKeys.forEach { dependencyKey ->
                dependentsByKey.getOrPut(dependencyKey) { linkedSetOf() } += fileKey
            }
        }

        val rankByKey = ordered.mapIndexed { index, file ->
            key(file) to index + if (preferPatches && looksLikePatch(file)) ordered.size / 2 else 0
        }.toMap()
        val remainingDependenciesByKey = dependenciesByKey.mapValues { (_, dependencies) -> dependencies.toMutableSet() }.toMutableMap()
        val available = remainingDependenciesByKey
            .filterValues { it.isEmpty() }
            .keys
            .toMutableSet()
        val emitted = linkedSetOf<String>()
        val result = mutableListOf<NexusCollectionFile>()
        val keyComparator = compareBy<String> { rankByKey[it] ?: Int.MAX_VALUE }
            .thenBy { originalIndexByKey[it] ?: Int.MAX_VALUE }
            .thenBy { it }

        while (available.isNotEmpty()) {
            val nextKey = available.minWith(keyComparator)
            available -= nextKey
            if (!emitted.add(nextKey)) continue
            fileByKey[nextKey]?.let(result::add)
            dependentsByKey[nextKey].orEmpty().forEach { dependentKey ->
                val remainingDependencies = remainingDependenciesByKey[dependentKey] ?: return@forEach
                remainingDependencies -= nextKey
                if (remainingDependencies.isEmpty() && dependentKey !in emitted) {
                    available += dependentKey
                }
            }
        }

        if (result.size < ordered.size) {
            result += ordered
                .filter { key(it) !in emitted }
                .sortedWith(compareBy<NexusCollectionFile> { rankByKey[key(it)] ?: Int.MAX_VALUE }.thenBy { originalIndexByKey[key(it)] ?: Int.MAX_VALUE })
        }
        return result
    }

    fun key(file: NexusCollectionFile): String =
        "${file.gameDomain}:${file.modId}:${file.fileId}:${file.position}"

    private fun domainModKey(gameDomain: String, modId: Long): String =
        "${gameDomain.lowercase(Locale.US)}:$modId"

    private fun looksLikePatch(file: NexusCollectionFile): Boolean {
        val text = "${file.modName} ${file.fileName}".lowercase(Locale.US)
        return listOf("patch", "compat", "addon", "hotfix", "fixes", "bodyslide", "parallax").any { it in text }
    }
}
