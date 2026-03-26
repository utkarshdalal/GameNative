package app.gamenative.data

import app.gamenative.enums.PathType
import app.gamenative.utils.SteamUtils
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.pathString
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.text.replace

/**
 * @param timestamp the value in milliseconds, since the epoch (1970-01-01T00:00:00Z)
 */
@Serializable
data class UserFileInfo(
    val root: PathType,
    val path: String,
    val filename: String,
    val timestamp: Long,
    val sha: ByteArray,
    val cloudRoot: PathType = root,
    val cloudPath: String = path,
) {
    // "." and blank path both mean "root of path type" per Steam manifest.
    val prefix: String
        get() {
            val pathForPrefix = when {
                cloudPath.isBlank() || cloudPath == "." -> ""
                else -> cloudPath
            }
            return Paths.get("%${cloudRoot.name}%$pathForPrefix").pathString
                .replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
                .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())
        }

    // Bare placeholder (%GameInstall%) expects no slash before filename; path with folder uses Paths.get.
    val prefixPath: String
        get() = when {
            cloudPath.isBlank() || cloudPath == "." -> "$prefix$filename"
            else -> Paths.get(prefix, filename).pathString
        }.replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
            .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())

    val substitutedPath: String
        get() = path
            .replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
            .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())
            .replace("\\", File.separator)

    fun getAbsPath(prefixToPath: (String) -> String): Path {
        return Paths.get(prefixToPath(root.toString()), substitutedPath, filename)
    }
}
