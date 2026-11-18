package app.gamenative.data

import app.gamenative.db.serializers.OsEnumSetSerializer
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import java.util.EnumSet
import kotlinx.serialization.Serializable

@Serializable
data class LaunchInfo(
    val executable: String,
    val workingDir: String,
    val description: String,
    val type: String,
    @Serializable(with = OsEnumSetSerializer::class)
    val configOS: EnumSet<OS>,
    val configArch: OSArch,
    val arguments: String = "",
    val option: Int = -1,
    val betaKey: String = "",
)

/**
 * Keeps the entries Steam offers on the installed branch ([installedBranch] returns null or empty for "public").
 * An entry with no BetaKey is offered on every branch; a BetaKey can list several branches separated by commas.
 * A list without any BetaKey is returned as is, without asking for the branch, and so is a list the filter
 * would empty.
 */
fun List<LaunchInfo>.forBranch(installedBranch: () -> String?): List<LaunchInfo> {
    if (none { it.betaKey.isNotBlank() }) return this
    val branch = installedBranch()?.takeIf { it.isNotBlank() } ?: "public"
    val eligible = filter { entry ->
        entry.betaKey.isBlank() ||
            entry.betaKey.split(',').any { it.trim().equals(branch, ignoreCase = true) }
    }
    return eligible.ifEmpty { this }
}
