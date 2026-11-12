package app.gamenative.theme.sound

import app.gamenative.theme.ThemeZip
import java.io.File
import java.io.IOException

/** Unpacking and checking of sound pack zips and folders. Pure JVM. */
object SoundPackFiles {

    const val MAX_ENTRIES = 300
    const val MAX_UNPACKED_BYTES = 100L * 1024 * 1024

    val ALLOWED_EXTENSIONS = setOf("json", "wav", "mp3", "ogg", "m4a", "flac", "txt", "md", "png", "jpg")

    private val IGNORED_TOP = setOf("__MACOSX", ".DS_Store")

    /** Unpacks [zip] into [work] and returns the pack folder with only allowed files left in it. */
    fun unpack(zip: File, work: File): File {
        ThemeZip.extract(zip, work, MAX_ENTRIES, MAX_UNPACKED_BYTES)
        val root = findPackRoot(work) ?: throw IOException("No pack.json in the zip")
        prune(root)
        return root
    }

    /** [dir] itself when it holds `pack.json`, or its single top-level folder when that one does. */
    fun findPackRoot(dir: File): File? {
        if (File(dir, SoundPackManifest.FILE).isFile) return dir
        val kids = dir.listFiles()?.filter { it.name !in IGNORED_TOP && !it.name.startsWith("._") } ?: return null
        val dirs = kids.filter { it.isDirectory }
        if (dirs.size == 1 && kids.none { it.isFile && !it.name.startsWith(".") }) {
            return dirs[0].takeIf { File(it, SoundPackManifest.FILE).isFile }
        }
        return null
    }

    /** Deletes every file whose extension is not in [ALLOWED_EXTENSIONS], and macOS metadata. */
    fun prune(dir: File) {
        dir.walkBottomUp().forEach { f ->
            when {
                f == dir -> Unit
                f.isDirectory -> if (f.name in IGNORED_TOP || f.listFiles()?.isEmpty() == true) f.deleteRecursively()
                f.name.startsWith("._") || f.name in IGNORED_TOP -> f.delete()
                f.extension.lowercase() !in ALLOWED_EXTENSIONS -> f.delete()
            }
        }
    }

    /** Reads and checks a pack folder; a sound pack must play at least one [UiSound], a music pack must have menu music. */
    fun validate(dir: File): SoundPackManifest {
        val manifest = try {
            SoundPackManifest.load(dir)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("pack.json is not valid JSON", e)
        }
        if (manifest.music) {
            if (SteamSounds.menuMusic(dir, manifest) == null) throw IOException("Music pack has no ${SteamSounds.MENU_MUSIC}")
        } else if (SteamSounds.soundCount(dir, manifest) == 0) {
            throw IOException("Pack has no sounds this app plays")
        }
        return manifest
    }

    /** Folder name for a pack called [name]. */
    fun packId(name: String): String {
        var id = name.trim().map { if (it.isLetterOrDigit() || it in " ._-()") it else '_' }.joinToString("").trim().take(80)
        if (id.isEmpty()) id = "Pack"
        if (id.startsWith(".") || id.equals("state.json", ignoreCase = true)) id = "pack_$id"
        return id
    }
}
