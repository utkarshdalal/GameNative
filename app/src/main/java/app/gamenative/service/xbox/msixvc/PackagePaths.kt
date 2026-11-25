// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.io.File
import java.util.Locale

/** Maps package paths ('\' or '/' separated) to files under an install directory. */
object PackagePaths {
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    private const val FORBIDDEN = "<>:\"|?*"

    /** Validated relative components of a package path. */
    fun components(packagePath: String): List<String> {
        fun reject(reason: String): Nothing = throw MsixvcException.UnsafePath(packagePath, reason)
        if (packagePath.isEmpty()) reject("empty path")
        if (packagePath.startsWith("\\") || packagePath.startsWith("/")) reject("absolute path")
        if (packagePath.length >= 2 && packagePath[1] == ':') reject("drive letter")
        val parts = packagePath.split('\\', '/')
        for (p in parts) {
            if (p.isEmpty()) reject("empty component")
            if (p == "." || p == "..") reject("relative component")
            if (p.any { it < ' ' || FORBIDDEN.indexOf(it) >= 0 }) reject("invalid character")
            if (p.endsWith('.') || p.endsWith(' ')) reject("trailing dot or space")
            val stem = p.substringBefore('.').trimEnd().uppercase(Locale.ROOT)
            if (stem in RESERVED) reject("reserved name")
        }
        return parts
    }

    fun resolve(root: File, packagePath: String): File {
        val parts = components(packagePath)
        val file = File(root, parts.joinToString(File.separator))
        val rootCanon = root.canonicalFile
        val parentCanon = file.parentFile!!.canonicalFile
        if (parentCanon != rootCanon && !parentCanon.path.startsWith(rootCanon.path + File.separator)) {
            throw MsixvcException.UnsafePath(packagePath, "resolves outside the install directory")
        }
        return file
    }
}
