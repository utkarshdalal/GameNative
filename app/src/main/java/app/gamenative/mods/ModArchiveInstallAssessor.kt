package app.gamenative.mods

import java.util.Locale

enum class ModArchiveInstallConfidence {
    HIGH,
    POST_INSTALL_REVIEW,
    REVIEW_PLACEMENT,
    MANUAL_ROOT_REVIEW,
}

data class ModArchiveInstallAssessment(
    val confidence: ModArchiveInstallConfidence,
    val reasons: List<String>,
    val postInstallTasks: List<String> = emptyList(),
) {
    val allowsAutomaticPlacement: Boolean
        get() = confidence == ModArchiveInstallConfidence.HIGH ||
            confidence == ModArchiveInstallConfidence.POST_INSTALL_REVIEW

    val queueMessage: String
        get() = when (confidence) {
            ModArchiveInstallConfidence.HIGH -> "Imported"
            ModArchiveInstallConfidence.POST_INSTALL_REVIEW -> "Imported; review post-install steps"
            ModArchiveInstallConfidence.REVIEW_PLACEMENT -> "Needs placement review"
            ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW -> "Needs manual/root review"
        }
}

object ModArchiveInstallAssessor {
    private val postInstallKeywords = listOf(
        "bodyslide",
        "outfit studio",
        "nemesis",
        "fnis",
        "dyndolod",
        "texgen",
        "synthesis",
        "xedit",
        "sseedit",
        "fo4edit",
        "fnvedit",
        "loot",
        "skyproc",
        "patcher",
    )
    private val manualRootNames = setOf(
        "d3d11.dll",
        "dxgi.dll",
        "enbseries.ini",
        "enblocal.ini",
        "skse64_loader.exe",
        "skse64_steam_loader.dll",
        "f4se_loader.exe",
        "f4se_steam_loader.dll",
        "nvse_loader.exe",
    )
    private val rootReviewExtensions = setOf("exe", "msi", "bat", "cmd", "ps1", "dll", "asi", "ini", "toml")
    private val rootAllowedNames = setOf("readme.txt", "readme.md", "license.txt", "changelog.txt")
    private val optionRootPrefixes = listOf(
        "00",
        "01",
        "02",
        "03",
        "option",
        "optional",
        "choose",
        "manual",
        "patch",
        "compat",
        "bodyslide",
        "cbbe",
        "unp",
        "3ba",
    )
    private val ignoredChoiceRoots = setOf("fomod", "meta", "__macosx")

    fun assess(
        gameName: String,
        modName: String,
        fileName: String,
        entries: List<ModArchiveEntry>,
        gameDomain: String = "",
        modId: Long = 0L,
        fileId: Long = 0L,
    ): ModArchiveInstallAssessment {
        val policy = NexusModAutomationPolicy.match(gameDomain, modId, fileId, modName, fileName)
        if (entries.isEmpty()) {
            return ModArchiveInstallAssessment(
                ModArchiveInstallConfidence.REVIEW_PLACEMENT,
                policy.reasons + "Archive contains no visible files",
                policy.postInstallTasks,
            )
        }

        val paths = entries.map { normalizePath(it.path) }.filter { it.isNotBlank() }
        val manualReasons = manualRootReasons(paths, policy.allowedRootNames)
        if (manualReasons.isNotEmpty()) {
            return ModArchiveInstallAssessment(
                ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW,
                policy.reasons + manualReasons,
                policy.postInstallTasks,
            )
        }

        if (policy.confidence == ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW) {
            return ModArchiveInstallAssessment(policy.confidence, policy.reasons, policy.postInstallTasks)
        }

        if (paths.any { it.endsWith("fomod/ModuleConfig.xml", ignoreCase = true) }) {
            return ModArchiveInstallAssessment(
                ModArchiveInstallConfidence.REVIEW_PLACEMENT,
                policy.reasons + "FOMOD installer choices need review",
                policy.postInstallTasks,
            )
        }

        val choiceRoots = ambiguousChoiceRoots(paths)
        if (choiceRoots.size >= 2) {
            return ModArchiveInstallAssessment(
                ModArchiveInstallConfidence.REVIEW_PLACEMENT,
                policy.reasons + "Archive has multiple option-style folders: ${choiceRoots.take(3).joinToString(", ")}",
                policy.postInstallTasks,
            )
        }

        if (ModPlacementPresetDetector.detect(gameName, entries).isEmpty()) {
            return ModArchiveInstallAssessment(
                ModArchiveInstallConfidence.REVIEW_PLACEMENT,
                policy.reasons + "Archive layout did not match a known placement preset",
                policy.postInstallTasks,
            )
        }

        val identity = "$modName $fileName".lowercase(Locale.US)
        val postInstallHits = postInstallKeywords.filter { it in identity }
        val tasks = (policy.postInstallTasks + postInstallTasks(identity, postInstallHits)).distinct()
        val policyConfidence = policy.confidence
        if (policyConfidence == ModArchiveInstallConfidence.REVIEW_PLACEMENT) {
            return ModArchiveInstallAssessment(policyConfidence, policy.reasons, tasks)
        }
        if (policyConfidence == ModArchiveInstallConfidence.POST_INSTALL_REVIEW || postInstallHits.isNotEmpty()) {
            return ModArchiveInstallAssessment(
                ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                policy.reasons + "May require post-install review: ${tasks.take(2).joinToString(", ")}",
                tasks,
            )
        }

        return ModArchiveInstallAssessment(
            ModArchiveInstallConfidence.HIGH,
            policy.reasons + "Archive matched a known placement preset",
            tasks,
        )
    }

    private fun manualRootReasons(paths: List<String>, allowedRootNames: Set<String>): List<String> = buildList {
        paths.forEach { path ->
            val lower = path.lowercase(Locale.US)
            val name = lower.substringAfterLast('/')
            val rootFile = '/' !in lower
            val extension = name.substringAfterLast('.', "")
            when {
                rootFile && (name in rootAllowedNames || name in allowedRootNames) -> Unit
                rootFile && name in manualRootNames -> add("Root-level runtime file: $name")
                rootFile && extension in rootReviewExtensions -> add("Root-level executable/script: $name")
                lower.startsWith("enbseries/") -> add("ENB preset folder at game root")
            }
        }
    }.distinct().take(4)

    private fun postInstallTasks(identity: String, hits: List<String>): List<String> = buildList {
        if ("bodyslide" in identity || "outfit studio" in identity) add("Run BodySlide if the mod provides generated meshes")
        if ("nemesis" in identity) add("Run Nemesis after installing animation changes")
        if ("fnis" in identity) add("Run FNIS after installing animation changes")
        if ("dyndolod" in identity || "texgen" in identity) add("Run TexGen/DynDOLOD after load order is final")
        if ("synthesis" in identity) add("Run Synthesis patchers after load order is final")
        if ("xedit" in identity || "sseedit" in identity || "fo4edit" in identity || "fnvedit" in identity) add("Review xEdit patcher instructions")
        if ("loot" in identity) add("Review plugin order; LOOT metadata is not run inside GN")
        if ("patcher" in identity && isEmpty()) add("Run or review the mod patcher after install")
        if (isEmpty() && hits.isNotEmpty()) add("Review post-install tool instructions")
    }

    private fun ambiguousChoiceRoots(paths: List<String>): List<String> {
        val roots = paths
            .mapNotNull { it.substringBefore('/').takeIf { root -> root != it } }
            .map { it.lowercase(Locale.US) }
            .filter { it !in ignoredChoiceRoots }
            .distinct()
        return roots.filter { root ->
            optionRootPrefixes.any { prefix -> root.startsWith(prefix) }
        }
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")
}
