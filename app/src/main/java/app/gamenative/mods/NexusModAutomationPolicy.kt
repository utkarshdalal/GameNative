package app.gamenative.mods

import java.util.Locale

data class NexusModAutomationPolicyMatch(
    val confidence: ModArchiveInstallConfidence? = null,
    val reasons: List<String> = emptyList(),
    val postInstallTasks: List<String> = emptyList(),
    val allowedRootNames: Set<String> = emptySet(),
)

object NexusModAutomationPolicy {
    fun match(
        gameDomain: String,
        modId: Long,
        fileId: Long,
        modName: String,
        fileName: String,
    ): NexusModAutomationPolicyMatch {
        val domain = gameDomain.lowercase(Locale.US)
        val name = "$modName $fileName".lowercase(Locale.US)
        if (domain in setOf("skyrimspecialedition", "skyrim", "skyrimvr")) {
            skyrimPolicy(modId, fileId, name)?.let { return it }
        }
        if (domain in setOf("fallout4", "newvegas", "falloutnv")) {
            falloutPolicy(modId, name)?.let { return it }
        }
        return keywordPolicy(name)
    }

    private fun skyrimPolicy(modId: Long, fileId: Long, name: String): NexusModAutomationPolicyMatch? =
        when {
            modId == 30379L -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW,
                reasons = listOf("Known SKSE archive can contain game-root loader files"),
                postInstallTasks = listOf("Install SKSE root files manually if required"),
            )
            modId == 17230L && "part 2" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW,
                reasons = listOf("Known SSE Engine Fixes Part 2 contains game-root runtime files"),
            )
            modId == 17230L -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("Known SSE Engine Fixes Part 1 is an SKSE plugin"),
                postInstallTasks = listOf("Verify Engine Fixes Part 2/root files separately"),
            )
            modId == 32444L -> NexusModAutomationPolicyMatch(
                reasons = listOf("Known Address Library Data/SKSE plugin package"),
            )
            modId == 201L -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("Known BodySlide tool/data package"),
                postInstallTasks = listOf("Run BodySlide if installed outfits need generated meshes"),
            )
            modId == 3038L || "fnis" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("Known FNIS animation package"),
                postInstallTasks = listOf("Run FNIS after installing animation changes"),
            )
            "nemesis" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("Known Nemesis animation workflow"),
                postInstallTasks = listOf("Run Nemesis after installing animation changes"),
            )
            "dyndolod" in name || "texgen" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("Known generated LOD workflow"),
                postInstallTasks = listOf("Run TexGen/DynDOLOD after load order is final"),
            )
            else -> null
        }

    private fun falloutPolicy(modId: Long, name: String): NexusModAutomationPolicyMatch? =
        when {
            modId == 66347L -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("Known Stewie Tweaks package may include INI/config review"),
                postInstallTasks = listOf("Review Stewie Tweaks INI/config choices"),
            )
            "script extender" in name || "xnvse" in name || "nvse" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW,
                reasons = listOf("Script extender packages can require game-root files"),
            )
            else -> null
        }

    private fun keywordPolicy(name: String): NexusModAutomationPolicyMatch =
        when {
            "enb binaries" in name || "enbseries" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.MANUAL_ROOT_REVIEW,
                reasons = listOf("ENB binaries/presets can require game-root files"),
                postInstallTasks = listOf("Review ENB root-file instructions"),
            )
            "enb" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                reasons = listOf("ENB-related package may need ENB binaries or preset review"),
                postInstallTasks = listOf("Review ENB instructions before launch"),
            )
            "bodyslide" in name -> NexusModAutomationPolicyMatch(
                confidence = ModArchiveInstallConfidence.POST_INSTALL_REVIEW,
                postInstallTasks = listOf("Run BodySlide if the mod provides generated meshes"),
            )
            else -> NexusModAutomationPolicyMatch()
        }
}
