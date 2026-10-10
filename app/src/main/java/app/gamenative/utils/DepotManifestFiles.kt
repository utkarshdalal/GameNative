package app.gamenative.utils

import `in`.dragonbra.javasteam.types.DepotManifest
import timber.log.Timber
import java.io.File

/**
 * The native engine's manifest store, and the only place that knows its layout.
 *
 * `.DepotDownloader/completed/<depot>_<gid>.manifest` is what the game directory IS: those file
 * names are the installed record (which depots, at which gid), and a manifest only lands there once
 * the engine has finished writing that depot. `.DepotDownloader/target/<depot>_<gid>.manifest` is
 * what the run in flight is installing: written when the manifest was fetched, promoted into
 * `completed/` on success, and deliberately not reused by a later run — a resume is a new press, so
 * it re-reads what the CDN serves now instead of finishing a build that may have been superseded
 * while the download sat paused.
 *
 * VERIFY reads `completed/`, which is why it can never end up verifying against a build that is not
 * the installed one.
 */
object DepotManifestFiles {
    private const val CONFIG_DIR = ".DepotDownloader"
    private const val COMPLETED_DIR = "completed"
    private const val TARGET_DIR = "target"
    private const val MANIFEST_SUFFIX = ".manifest"

    fun completedDir(appDirPath: String): File = File(appDirPath, "$CONFIG_DIR/$COMPLETED_DIR")

    fun targetDir(appDirPath: String): File = File(appDirPath, "$CONFIG_DIR/$TARGET_DIR")

    /**
     * The manifest for `(depotId, gid)`: the completed copy when it exists (the installed build),
     * otherwise the path the in-flight target would occupy. The returned file is not created here, so
     * callers that only care whether the manifest is on disk still check `isFile`.
     */
    fun manifestFile(appDirPath: String, depotId: Int, gid: Long): File {
        val name = "$depotId" + "_" + "${gid.toULong()}$MANIFEST_SUFFIX"
        val completed = File(completedDir(appDirPath), name)
        return if (completed.isFile) completed else File(targetDir(appDirPath), name)
    }

    /**
     * The installed record: `depotId -> gid` from `completed/`'s file names.
     *
     * The engine keeps exactly one manifest per depot there — promotion replaces the previous one — so
     * a second file only appears if an interrupted promotion left it behind: the newest wins, rather
     * than the depot dropping out of the record.
     */
    fun installedManifests(appDirPath: String): Map<Int, ULong> {
        val byDepot = mutableMapOf<Int, Pair<ULong, Long>>()
        val duplicates = mutableListOf<Int>()
        completedDir(appDirPath).listFiles()?.forEach { file ->
            val parsed = parseName(file.name) ?: return@forEach
            val (depotId, gid) = parsed
            val current = byDepot[depotId]
            if (current == null) {
                byDepot[depotId] = gid to file.lastModified()
            } else {
                duplicates += depotId
                if (file.lastModified() > current.second) byDepot[depotId] = gid to file.lastModified()
            }
        }
        if (duplicates.isNotEmpty()) {
            Timber.w(
                "installedManifests: multiple completed manifests for depot(s) " +
                    "${duplicates.distinct().sorted()} — using the newest",
            )
        }
        return byDepot.mapValues { (_, newest) -> newest.first }
    }

    /**
     * One-time upgrade from the layout this engine used before the split: a flat `.DepotDownloader/`
     * holding every manifest ever fetched, plus `depot.config` as the pointer to the installed gid per
     * depot.
     *
     * The record is now `completed/`'s file names, so the recorded manifests are moved there — their
     * gids come from the pointer, so no re-walk is needed — and the rest of the flat cache is dropped:
     * nothing reads it any more, and a stale copy of an installed build sitting next to the record is
     * exactly the ambiguity the split removes. Runs at most once (it deletes `depot.config`), and does
     * nothing on an install that already used this layout.
     */
    fun migrateLegacyLayout(appDirPath: String): Int {
        val configDir = File(appDirPath, CONFIG_DIR)
        val pointer = File(configDir, "depot.config")
        if (!pointer.isFile) return 0
        val recorded = runCatching {
            val text = pointer.readText()
            val block = text.substringAfter("\"installedManifestIDs\"", "")
                .substringAfter('{', "").substringBefore('}')
            Regex("\"(\\d+)\"\\s*:\\s*(\\d+)").findAll(block).mapNotNull { match ->
                val depotId = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val gid = match.groupValues[2].toULongOrNull() ?: return@mapNotNull null
                depotId to gid
            }.toList()
        }.getOrDefault(emptyList())

        val completed = completedDir(appDirPath).apply { mkdirs() }
        var migrated = 0
        recorded.forEach { (depotId, gid) ->
            val source = File(configDir, "${depotId}_${gid}$MANIFEST_SUFFIX")
            val destination = File(completed, source.name)
            if (!source.isFile || destination.isFile) return@forEach
            if (source.renameTo(destination)) {
                migrated++
            } else {
                Timber.w("manifest store: could not move ${source.name} into completed/")
            }
        }
        // The pointer and what is left of the flat cache have no reader any more.
        val leftOver = configDir.listFiles { file -> file.isFile && file.name.endsWith(MANIFEST_SUFFIX) }
        leftOver?.forEach { it.delete() }
        pointer.delete()
        Timber.i(
            "manifest store: moved $migrated of ${recorded.size} installed manifest(s) into " +
                "completed/ and dropped ${leftOver?.size ?: 0} cached manifest(s)",
        )
        return migrated
    }

    fun decryptFilenames(file: File, depotKey: ByteArray): Boolean = runCatching {
        val manifest = DepotManifest.loadFromFile(file.absolutePath) ?: return false
        if (!manifest.filenamesEncrypted) return false
        if (!manifest.decryptFilenames(depotKey)) {
            Timber.w("Could not decrypt filenames in ${file.name}")
            return false
        }
        manifest.saveToFile(file.absolutePath)
        Timber.i("Decrypted filenames in ${file.name}")
        true
    }.getOrElse { e ->
        Timber.w(e, "Could not decrypt filenames in ${file.name}")
        false
    }

    /** `"<depot>_<gid>.manifest"` -> `(depotId, gid)`. */
    private fun parseName(name: String): Pair<Int, ULong>? {
        val stem = name.removeSuffix(MANIFEST_SUFFIX).takeIf { it != name } ?: return null
        val separator = stem.indexOf('_')
        if (separator <= 0) return null
        val depotId = stem.substring(0, separator).toIntOrNull() ?: return null
        val gid = stem.substring(separator + 1).toULongOrNull() ?: return null
        return depotId to gid
    }
}
