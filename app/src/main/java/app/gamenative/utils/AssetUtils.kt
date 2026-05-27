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
     * Extracts component files with version verification.
     * Only extracts when the asset file version differs from the stored version.
     * Version is extracted from filename pattern: component-name-VERSION.ext
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
            val versionFile = File(targetDir, ".version")

            val assetVersion = extractVersionFromFilename(assetFile)
            val storedVersion = runCatching {
                if (versionFile.exists()) {
                    versionFile.readText().trim()
                } else {
                    ""
                }
            }.getOrElse { err ->
                log().e(err, "Failed to read version file for $assetFile")
                ""
            }

            if (assetVersion != storedVersion || !targetDir.exists()) {
                log().i("Extracting $assetFile to ${targetDir.absolutePath} (version: $assetVersion, stored: $storedVersion)")
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
                    runCatching {
                        versionFile.writeText(assetVersion)
                    }.onFailure { err ->
                        log().e(err, "Failed to persist version for $assetFile")
                    }
                    log().i("Successfully extracted $assetFile (version: $assetVersion)")
                } else {
                    tempDir.deleteRecursively()
                    log().e("Failed to extract $assetFile")
                }
            } else {
                log().i("Skipping $assetFile (version matches: $assetVersion)")
            }
        }
    }

    /**
     * Extracts version from filename.
     * Expected format: component-name-VERSION.ext
     * Example: "pulseaudio-gamenative-20260527.tzst" -> "20260527"
     *
     * @param filename Name of the asset file
     * @return Version string extracted from filename, or the full filename if pattern doesn't match
     */
    private fun extractVersionFromFilename(filename: String): String {
        // Remove extension(s)
        val nameWithoutExt = filename.substringBeforeLast('.')

        // Extract version from pattern: name-version
        // Version is typically the last segment after the last dash
        val parts = nameWithoutExt.split('-')
        return if (parts.size >= 2) {
            parts.last()
        } else {
            // Fallback: use entire filename if pattern doesn't match
            filename
        }
    }
}
