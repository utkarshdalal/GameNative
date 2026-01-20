package app.gamenative.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.winlator.core.FileUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * Handles migration of legacy container formats.
 */
object ContainerMigrator {
    private const val LATEST_CONTAINER_MIGRATION_VERSION = 1
    private val mainHandler = Handler(Looper.getMainLooper())
    private inline fun postMain(crossinline block: () -> Unit) = mainHandler.post { block() }

    /**
     * Creates a container migration version file to track completed migrations
     */
    private fun createContainerMigrationVersionFile(context: Context, version: Int) {
        val imageFs = ImageFs.find(context)
        val configDir = imageFs.configDir
        configDir.mkdirs()
        val versionFile = File(configDir, ".container_migration_version")
        try {
            versionFile.createNewFile()
            FileUtils.writeString(versionFile, version.toString())
            Timber.i("Created container migration version file: $version")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create container migration version file")
        }
    }

    /**
     * Gets the current container migration version, returns 0 if not found
     */
    private fun getContainerMigrationVersion(context: Context): Int {
        val imageFs = ImageFs.find(context)
        val versionFile = File(imageFs.configDir, ".container_migration_version")
        return if (versionFile.exists()) {
            try {
                FileUtils.readLines(versionFile)[0].toInt()
            } catch (e: Exception) {
                Timber.e(e, "Failed to read container migration version")
                0
            }
        } else {
            0
        }
    }

    /**
     * Checks if container migration is needed by comparing versions
     */
    fun isContainerMigrationNeeded(context: Context): Boolean {
        val currentVersion = getContainerMigrationVersion(context)
        return currentVersion < LATEST_CONTAINER_MIGRATION_VERSION
    }

    /**
     * Migrates legacy numeric container directories to platform-prefixed format.
     * Legacy: xuser-12345/ -> New: xuser-STEAM_12345/
     * Runs only once based on version tracking like ImageFsInstaller
     */
    fun migrateLegacyContainersIfNeeded(
        context: Context,
        onProgressUpdate: ((currentContainer: String, migratedContainers: Int, totalContainers: Int) -> Unit)? = null,
        onComplete: ((migratedCount: Int) -> Unit)? = null,
    ) {
        try {
            // Check if migration is needed based on version
            if (!isContainerMigrationNeeded(context)) {
                Timber.i("Container migration not needed, already at version $LATEST_CONTAINER_MIGRATION_VERSION")
                postMain {
                    onComplete?.invoke(0)
                }
                return
            }

            val imageFs = ImageFs.find(context)
            val homeDir = File(imageFs.rootDir, "home")

            // Find all legacy numeric container directories
            val legacyContainers = homeDir.listFiles()?.filter { file ->
                file.isDirectory() &&
                    file.name != ImageFs.USER &&
                    // Skip active symlink
                    file.name.startsWith("${ImageFs.USER}-") &&
                    // Must have xuser- prefix
                    file.name.removePrefix("${ImageFs.USER}-").matches(Regex("\\d+")) &&
                    // Numeric ID after prefix
                    File(file, ".container").exists() // Has container config
            } ?: emptyList()

            val totalContainers = legacyContainers.size
            var migratedContainers = 0

            Timber.i("Found $totalContainers legacy containers to migrate")

            for (legacyDir in legacyContainers) {
                val legacyId = legacyDir.name.removePrefix("${ImageFs.USER}-") // Remove xuser- prefix
                val newContainerId = "STEAM_$legacyId"
                val newDir = File(homeDir, "${ImageFs.USER}-$newContainerId") // WITH xuser- prefix

                postMain {
                    onProgressUpdate?.invoke(legacyId, migratedContainers, totalContainers)
                }

                try {
                    // Handle naming conflicts
                    var finalContainerId = newContainerId
                    var finalNewDir = newDir
                    var counter = 1

                    while (finalNewDir.exists()) {
                        finalContainerId = "STEAM_$legacyId($counter)"
                        finalNewDir = File(homeDir, "${ImageFs.USER}-$finalContainerId") // WITH xuser- prefix
                        counter++
                    }

                    // Rename directory
                    if (legacyDir.renameTo(finalNewDir)) {
                        // Update container config
                        updateContainerConfig(finalNewDir, finalContainerId)

                        // Update active symlink if this was the active container
                        val activeSymlink = File(homeDir, ImageFs.USER)
                        if (activeSymlink.exists() && activeSymlink.canonicalPath.endsWith(legacyId)) {
                            activeSymlink.delete()
                            FileUtils.symlink("./${ImageFs.USER}-$finalContainerId", activeSymlink.path)
                            Timber.i("Updated active symlink to point to $finalContainerId")
                        }

                        migratedContainers++
                        Timber.i("Migrated container $legacyId -> $finalContainerId")
                    } else {
                        Timber.e("Failed to rename container directory: $legacyId")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error migrating container $legacyId")
                }
            }

            // Mark migration as complete regardless of success/failure
            createContainerMigrationVersionFile(context, LATEST_CONTAINER_MIGRATION_VERSION)

            postMain {
                onComplete?.invoke(migratedContainers)
            }

            Timber.i("Container migration completed: $migratedContainers containers migrated")
        } catch (e: Exception) {
            Timber.e(e, "Error during container migration")
            // Still mark as complete to avoid repeated attempts
            createContainerMigrationVersionFile(context, LATEST_CONTAINER_MIGRATION_VERSION)
            postMain {
                onComplete?.invoke(0)
            }
        }
    }

    private fun updateContainerConfig(containerDir: File, newContainerId: String) {
        try {
            val configFile = File(containerDir, ".container")
            val configContent = FileUtils.readString(configFile)
            val data = JSONObject(configContent)
            data.put("id", newContainerId)
            FileUtils.writeString(configFile, data.toString())
            Timber.i("Updated container config ID to $newContainerId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update container config for $newContainerId")
        }
    }
}
