package app.gamenative.data

import app.gamenative.enums.PathType
import app.gamenative.utils.SteamUtils
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SaveFilePattern(
    val root: PathType,
    val path: String,
    val pattern: String,
    val recursive: Int = 0,
    val uploadRoot: PathType = root,
    val uploadPath: String = path,
) {
    val prefix: String
        get() = SteamUtils.substituteSteamIdPlaceholders("%${root.name}%${path.replace("\\", "/")}")

    val substitutedPath: String
        get() = SteamUtils.substituteSteamIdPlaceholders(path).replace("\\", File.separator)
}
