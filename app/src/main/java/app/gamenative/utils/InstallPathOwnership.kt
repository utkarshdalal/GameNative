package app.gamenative.utils

import app.gamenative.data.GameSource
import java.nio.file.Paths

/** A catalog entry that may claim an on-disk install path. */
internal data class InstallPathOwner(
    val source: GameSource,
    val stableId: String,
    val installPath: String,
)

/**
 * Legacy installs only have a generic completion marker. Only use that marker to recover install
 * state when the path maps to exactly one catalog identity for the store.
 */
internal fun hasUniqueInstallPathOwner(
    target: InstallPathOwner,
    candidates: Iterable<InstallPathOwner>,
): Boolean {
    val targetPath = normalizeInstallPath(target.installPath) ?: return false
    val matchingOwners = candidates.filter { candidate ->
        candidate.source == target.source && normalizeInstallPath(candidate.installPath) == targetPath
    }
    return matchingOwners.singleOrNull()?.let { owner ->
        owner.stableId == target.stableId
    } == true
}

private fun normalizeInstallPath(path: String): String? {
    if (path.isBlank()) return null
    return runCatching { Paths.get(path).toAbsolutePath().normalize().toString() }.getOrNull()
}
