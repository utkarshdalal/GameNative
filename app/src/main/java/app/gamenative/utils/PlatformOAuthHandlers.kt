package app.gamenative.utils

import android.content.Context
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object PlatformOAuthHandlers {

    suspend fun handleGogAuthentication(
        context: Context,
        authCode: String,
        coroutineScope: CoroutineScope,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onSuccess: (Int) -> Unit,
        onDialogClose: () -> Unit,
    ) {
        onLoadingChange(true)
        onError(null)

        try {
            Timber.d("[PlatformOAuth]: Starting GOG authentication...")
            val result = GOGService.authenticateWithCode(context, authCode)

            if (result.isSuccess) {
                Timber.i("[PlatformOAuth]: GOG authentication successful!")
                GOGService.start(context)
                GOGService.triggerLibrarySync(context)
                onSuccess(0)
                onLoadingChange(false)
                onDialogClose()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Authentication failed"
                Timber.e("[PlatformOAuth]: GOG authentication failed: $error")
                onLoadingChange(false)
                onError(error)
            }
        } catch (e: Exception) {
            Timber.e(e, "[PlatformOAuth]: GOG authentication exception: ${e.message}")
            onLoadingChange(false)
            onError(e.message ?: "Authentication failed")
        }
    }

    suspend fun handleEpicAuthentication(
        context: Context,
        authCode: String,
        coroutineScope: CoroutineScope,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onSuccess: () -> Unit,
        onDialogClose: () -> Unit,
    ) {
        onLoadingChange(true)
        onError(null)

        try {
            Timber.d("[PlatformOAuth]: Starting Epic authentication...")
            val result = EpicService.authenticateWithCode(context, authCode)

            if (result.isSuccess) {
                Timber.i("[PlatformOAuth]: Epic authentication successful!")
                EpicService.start(context)
                EpicService.triggerLibrarySync(context)
                onSuccess()
                onLoadingChange(false)
                onDialogClose()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Authentication failed"
                Timber.e("[PlatformOAuth]: Epic authentication failed: $error")
                onLoadingChange(false)
                onError(error)
            }
        } catch (e: Exception) {
            Timber.e(e, "[PlatformOAuth]: Epic authentication exception: ${e.message}")
            onLoadingChange(false)
            onError(e.message ?: "Authentication failed")
        }
    }

    suspend fun handleAmazonAuthentication(
        context: Context,
        authCode: String,
        coroutineScope: CoroutineScope,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onSuccess: () -> Unit,
        onDialogClose: () -> Unit,
    ) {
        onLoadingChange(true)
        onError(null)

        try {
            Timber.d("[PlatformOAuth]: Starting Amazon authentication...")
            val result = AmazonService.authenticateWithCode(context, authCode)

            if (result.isSuccess) {
                Timber.i("[PlatformOAuth]: Amazon authentication successful!")
                AmazonService.start(context)
                AmazonService.triggerLibrarySync(context)
                onSuccess()
                onLoadingChange(false)
                onDialogClose()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Authentication failed"
                Timber.e("[PlatformOAuth]: Amazon authentication failed: $error")
                onLoadingChange(false)
                onError(error)
            }
        } catch (e: Exception) {
            Timber.e(e, "[PlatformOAuth]: Amazon authentication exception: ${e.message}")
            onLoadingChange(false)
            onError(e.message ?: "Authentication failed")
        }
    }
}
