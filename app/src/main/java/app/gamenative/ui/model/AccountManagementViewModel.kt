package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.service.GOG.GOGLibraryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class AccountManagementViewModel @Inject constructor(
    private val gogLibraryManager: GOGLibraryManager,
) : ViewModel() {
    fun syncGOGLibraryAsync(context: Context, clearExisting: Boolean = true, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            try {
                // Clear existing games and start background sync
                if (clearExisting) {
                    gogLibraryManager.clearLibrary()
                }

                // Start background sync and check if it was successful
                val syncStartResult = gogLibraryManager.startBackgroundSync(context, clearExisting)

                if (syncStartResult.isSuccess) {
                    // Sync started successfully, return current game count
                    val gameCount = gogLibraryManager.getLocalGameCount()
                    onResult(Result.success(gameCount))
                } else {
                    // Sync failed to start, return the error
                    onResult(Result.failure(syncStartResult.exceptionOrNull() ?: Exception("Failed to start sync")))
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception during GOG sync start")
                onResult(Result.failure(e))
            }
        }
    }
}
