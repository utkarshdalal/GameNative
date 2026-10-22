package app.gamenative.utils

import android.content.res.AssetManager
import com.winlator.core.TarCompressorUtils
import timber.log.Timber
import java.io.File
import java.nio.file.Files

object AssetUtils {
    fun log() : Timber.Tree {
        return Timber.tag("AssetUtils")
    }

    /** Delete a tree even when an earlier payload left it read-only. */
    internal fun deleteTree(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .forEach {
                it.setWritable(true, false)
                it.setExecutable(true, false)
            }
        dir.deleteRecursively()
    }

    /**
     * Extracts component files.
     * Always extracts assets to target directories.
     *
     * @param extractionPairs List of pairs containing asset file name and target directory
     * @param assetManager AssetManager to access asset files
     * @param extractType Compression type (ZSTD or XZ)
     */
    fun extractComponentsWithVersionCheck(
        extractionPairs: List<Pair<String, File>>,
        assetManager: AssetManager,
        extractType: TarCompressorUtils.Type
    ) {
        for ((assetFile, targetDir) in extractionPairs) {
            log().i("Extracting $assetFile to ${targetDir.absolutePath}")
            val tempDir = File(targetDir.parentFile, "${targetDir.name}.tmp")
            deleteTree(tempDir)
            tempDir.mkdirs()

            val success = TarCompressorUtils.extract(
                extractType,
                assetManager,
                assetFile,
                tempDir
            )

            if (success) {
                deleteTree(targetDir)
                if (!tempDir.renameTo(targetDir)) {
                    log().e("Failed to promote extracted dir for $assetFile")
                    deleteTree(tempDir)
                    continue
                }
                log().i("Successfully extracted $assetFile")
            } else {
                deleteTree(tempDir)
                log().e("Failed to extract $assetFile")
            }
        }
    }
}
