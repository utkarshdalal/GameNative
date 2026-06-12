package app.gamenative.utils

import android.content.res.AssetManager
import com.winlator.core.TarCompressorUtils
import timber.log.Timber
import java.io.File

object AssetUtils {
    fun log() : Timber.Tree {
        return Timber.tag("AssetUtils")
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
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            val success = TarCompressorUtils.extract(
                extractType,
                assetManager,
                assetFile,
                tempDir
            )

            if (success) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!tempDir.renameTo(targetDir)) {
                    log().e("Failed to promote extracted dir for $assetFile")
                    tempDir.deleteRecursively()
                    continue
                }
                log().i("Successfully extracted $assetFile")
            } else {
                tempDir.deleteRecursively()
                log().e("Failed to extract $assetFile")
            }
        }
    }
}
