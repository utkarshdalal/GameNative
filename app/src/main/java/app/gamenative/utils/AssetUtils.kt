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
     * Extracts component files with checksum verification.
     * Only extracts when the asset file checksum differs from the stored version.
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
            val versionFile = File(targetDir, ".$assetFile-version")

            val assetChecksum = computeAssetChecksum(assetManager, assetFile)
            val storedChecksum = if (versionFile.exists()) {
                versionFile.readText().trim()
            } else {
                ""
            }

            if (assetChecksum != storedChecksum || !targetDir.exists()) {
                log().i("Extracting $assetFile to ${targetDir.absolutePath} (checksum mismatch or directory missing)")
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                targetDir.mkdirs()

                val success = TarCompressorUtils.extract(
                    extractType,
                    assetManager,
                    assetFile,
                    targetDir
                )

                if (success) {
                    versionFile.writeText(assetChecksum)
                    log().i("Successfully extracted $assetFile")
                } else {
                    log().e("Failed to extract $assetFile")
                }
            } else {
                log().i("Skipping $assetFile (checksum matches, no extraction needed)")
            }
        }
    }

    /**
     * Computes SHA-256 checksum of an asset file.
     *
     * @param assetManager AssetManager to access the asset
     * @param assetFile Name of the asset file
     * @return Hex-encoded SHA-256 checksum string, or empty string on error
     */
    fun computeAssetChecksum(assetManager: AssetManager, assetFile: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            assetManager.open(assetFile).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            log().e(e, "Failed to compute checksum for $assetFile")
            ""
        }
    }
}
