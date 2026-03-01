package app.gamenative.ui.util

import android.content.Context
import android.widget.Toast
import app.gamenative.R
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * UI-friendly helpers for platform authentication flows that can be reused
 * from Settings, System Menu, or other composables.
 *
 * These helpers are intentionally non-Composable and rely only on Context,
 * CoroutineScope, and callbacks so they can be wired into different UIs.
 */

data class PlatformLogoutCallbacks(
    val onLoadingChange: (Boolean) -> Unit = {},
    val onSuccess: () -> Unit = {},
    val onError: (String?) -> Unit = {},
)

object PlatformAuthUiHelpers {

    fun logoutGog(
        context: Context,
        scope: CoroutineScope,
        callbacks: PlatformLogoutCallbacks = PlatformLogoutCallbacks(),
    ) {
        callbacks.onLoadingChange(true)
        scope.launch {
            try {
                Timber.d("[PlatformAuthUiHelpers][GOG] Starting logout...")
                val result = GOGService.logout(context)
                withContext(Dispatchers.Main) {
                    callbacks.onLoadingChange(false)
                    if (result.isSuccess) {
                        Timber.i("[PlatformAuthUiHelpers][GOG] Logout successful")
                        callbacks.onSuccess()
                        Toast.makeText(
                            context,
                            context.getString(R.string.gog_logout_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[PlatformAuthUiHelpers][GOG] Logout failed")
                        val message = context.getString(
                            R.string.gog_logout_failed,
                            error?.message ?: "Unknown error",
                        )
                        callbacks.onError(message)
                        Toast.makeText(
                            context,
                            message,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[PlatformAuthUiHelpers][GOG] Exception during logout")
                withContext(Dispatchers.Main) {
                    callbacks.onLoadingChange(false)
                    val message = context.getString(
                        R.string.gog_logout_failed,
                        e.message ?: "Unknown error",
                    )
                    callbacks.onError(message)
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun logoutEpic(
        context: Context,
        scope: CoroutineScope,
        callbacks: PlatformLogoutCallbacks = PlatformLogoutCallbacks(),
    ) {
        callbacks.onLoadingChange(true)
        scope.launch {
            try {
                Timber.d("[PlatformAuthUiHelpers][Epic] Starting logout...")
                val result = EpicService.logout(context)
                withContext(Dispatchers.Main) {
                    callbacks.onLoadingChange(false)
                    if (result.isSuccess) {
                        Timber.i("[PlatformAuthUiHelpers][Epic] Logout successful")
                        callbacks.onSuccess()
                        Toast.makeText(
                            context,
                            context.getString(R.string.epic_logout_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[PlatformAuthUiHelpers][Epic] Logout failed")
                        val message = context.getString(
                            R.string.epic_logout_failed,
                            error?.message ?: "Unknown",
                        )
                        callbacks.onError(message)
                        Toast.makeText(
                            context,
                            message,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[PlatformAuthUiHelpers][Epic] Exception during logout")
                withContext(Dispatchers.Main) {
                    callbacks.onLoadingChange(false)
                    val message = context.getString(
                        R.string.epic_logout_failed,
                        e.message ?: "Unknown",
                    )
                    callbacks.onError(message)
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun logoutAmazon(
        context: Context,
        scope: CoroutineScope,
        callbacks: PlatformLogoutCallbacks = PlatformLogoutCallbacks(),
    ) {
        callbacks.onLoadingChange(true)
        scope.launch {
            try {
                Timber.d("[PlatformAuthUiHelpers][Amazon] Starting logout...")
                val result = AmazonService.logout(context)
                withContext(Dispatchers.Main) {
                    callbacks.onLoadingChange(false)
                    if (result.isSuccess) {
                        Timber.i("[PlatformAuthUiHelpers][Amazon] Logout successful")
                        callbacks.onSuccess()
                        Toast.makeText(
                            context,
                            context.getString(R.string.amazon_logout_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[PlatformAuthUiHelpers][Amazon] Logout failed")
                        val message = context.getString(
                            R.string.amazon_logout_failed,
                            error?.message ?: "Unknown",
                        )
                        callbacks.onError(message)
                        Toast.makeText(
                            context,
                            message,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[PlatformAuthUiHelpers][Amazon] Exception during logout")
                withContext(Dispatchers.Main) {
                    callbacks.onLoadingChange(false)
                    val message = context.getString(
                        R.string.amazon_logout_failed,
                        e.message ?: "Unknown",
                    )
                    callbacks.onError(message)
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}

