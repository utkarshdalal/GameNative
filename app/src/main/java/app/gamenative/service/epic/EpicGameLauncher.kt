package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.data.EpicGameToken
import timber.log.Timber
import java.io.File

/**
 * Helper functionality for launching Epic Games with correct execution params for online verification
 *
 * Handles:
 * - Getting authentication tokens before launch
 * - Building Epic Games Services command-line parameters
 * - Managing ownership token files for DRM-protected games
 */
object EpicGameLauncher {

    /**
     * Build launch parameters for an Epic game
     *
     * Returns a list of command-line arguments to pass to the game executable
     * for Epic Games Services authentication
     *
    */
    suspend fun buildLaunchParameters(
        context: Context,
        game: EpicGame,
        offline: Boolean = false,
        languageCode: String = "en-US"
    ): Result<List<String>> {
        return try {
            val params = mutableListOf<String>()

            // Do offline play if offline.
            if (offline) {
                if (game.canRunOffline) {
                    Timber.tag("EPIC").i("Launching ${game.appName} in offline mode (no authentication)")
                    return Result.success(params)
                } else {
                    Timber.tag("EPIC").w("${game.appName} cannot run offline, will attempt online launch")
                }
            }

            Timber.tag("EPIC").d("Launching ${game.appName} online, getting game launch token...")

            val tokenResult = EpicAuthManager.getGameLaunchToken(
                context = context,
                namespace = game.namespace,
                catalogItemId = game.catalogId,
                requiresOwnershipToken = game.requiresOT
            )

            if (tokenResult.isFailure) {
                return Result.failure(tokenResult.exceptionOrNull() ?: Exception("Failed to get launch token"))
            }

            val gameToken: EpicGameToken? = tokenResult.getOrNull()

            if (gameToken == null) {
                Timber.tag("EPIC").w("Game Token is null for ${game.appName}")
                return Result.failure(Exception("Game token is null for ${game.appName}"))
            }

            Timber.tag("EPIC").d("Got Game Token for ${game.appName}")

            // Save ownership token to temp file if present
            val ownershipTokenPath = if (gameToken.ownershipToken != null) {
                saveOwnershipTokenToFile(context, game.namespace, game.catalogId, gameToken.ownershipToken)
            } else {
                null
            }

            Timber.tag("EPIC").i("Game launch token obtained for ${game.appName}")

            // Authentication parameters
            params.add("-AUTH_LOGIN=unused")
            params.add("-AUTH_PASSWORD=${gameToken?.authCode ?: "0"}")
            params.add("-AUTH_TYPE=exchangecode")
            params.add("-epicapp=${game.appName}")
            params.add("-epicenv=Prod")

            // Epic Portal flag
            params.add("-EpicPortal")

            // User information parameters
            val displayName = "GameNativeUser" //! We should adjust this later and use the user's real displayName later
            val accountId = gameToken?.accountId ?: "0"

            params.add("-epicusername=$displayName")
            params.add("-epicuserid=$accountId")
            params.add("-epiclocale=$languageCode")
            params.add("-epicsandboxid=${game.namespace}")

            // Ownership token for DRM-protected games
            if (ownershipTokenPath != null) {
                params.add("-epicovt=$ownershipTokenPath")
                Timber.tag("EPIC").d("Added ownership token path: $ownershipTokenPath")
            }

            // Additional command-line parameters from game metadata
            // This would come from game.metadata.customAttributes.AdditionalCommandLine -- We should take this into account if need be
            // TODO: Do a follow-up to include additional parameters where required for some games

            Timber.tag("EPIC").d("Built ${params.size} launch parameters for ${game.appName}")
            Result.success(params)
        } catch (e: Exception) {
            Timber.e(e, "Failed to build launch parameters")
            Result.failure(e)
        }
    }

    /**
     * Save ownership token bytes to temp file
     * File path format: {temp_dir}/{namespace}{catalogItemId}.ovt
     *
     * @return Absolute path to the saved token file
     */
    private fun saveOwnershipTokenToFile(
        context: Context,
        namespace: String,
        catalogItemId: String,
        ownershipTokenHex: String
    ): String {
        val tempDir = File(context.cacheDir, "epic_tokens")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val tokenFile = File(tempDir, "$namespace$catalogItemId.ovt")

        // Convert hex string back to bytes
        val tokenBytes = ownershipTokenHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        tokenFile.writeBytes(tokenBytes)

        Timber.tag("EPIC").d("Ownership token saved to: ${tokenFile.absolutePath}")
        return tokenFile.absolutePath
    }

    /**
     * Clean up temporary ownership token files after game exits
     */
    fun cleanupOwnershipTokens(context: Context) {
        try {
            val tempDir = File(context.cacheDir, "epic_tokens")
            if (tempDir.exists() && tempDir.isDirectory) {
                tempDir.listFiles()?.forEach { file ->
                    if (file.extension == "ovt") {
                        file.delete()
                        Timber.tag("EPIC").d("Deleted ownership token file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup ownership token files")
        }
    }
}
