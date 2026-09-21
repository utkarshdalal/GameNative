package app.gamenative.ui.model

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameImporter
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Minimal, VOTV-only counterpart to [LibraryViewModel]'s custom-game import flow: manages
 * exactly one imported game (persisted as [PrefManager.votvGameAppId]) instead of a whole
 * library, so the VOTV launcher variant doesn't pull in library-wide loading it doesn't need.
 */
@HiltViewModel
class VotvViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class ImportState(
        val isImporting: Boolean = false,
        val progress: CustomGameImporter.Progress? = null,
        val error: String? = null,
    )

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _gameAppId = MutableStateFlow(PrefManager.votvGameAppId)
    val gameAppId: StateFlow<String?> = _gameAppId.asStateFlow()

    init {
        // Re-applied on every visit to this screen (not just at import time) so a container
        // created before the display-mode fields were introduced, or before this default
        // changed, gets fixed up too.
        _gameAppId.value?.let { appId ->
            viewModelScope.launch(Dispatchers.IO) { prepareContainerDefaults(appId) }
        }
    }

    fun importGame(uri: Uri) {
        if (_importState.value.isImporting) return
        _importState.value = ImportState(isImporting = true)
        viewModelScope.launch(Dispatchers.IO) {
            var lastShown = 0L
            val result = CustomGameImporter.importFromTreeUri(context, uri, deleteSource = false) { progress ->
                if (progress.copiedBytes - lastShown > 8_000_000L) {
                    lastShown = progress.copiedBytes
                    _importState.value = ImportState(isImporting = true, progress = progress)
                }
            }
            result.onSuccess { path ->
                val libraryItem = CustomGameScanner.createLibraryItemFromFolder(path)
                if (libraryItem == null) {
                    Timber.tag("VotvViewModel").w("Imported folder is not a valid game: $path")
                    _importState.value = ImportState(error = context.getString(R.string.votv_import_error_generic))
                    return@onSuccess
                }
                PrefManager.votvGameAppId = libraryItem.appId
                _gameAppId.value = libraryItem.appId
                prepareContainerDefaults(libraryItem.appId)
                _importState.value = ImportState()
            }.onFailure {
                Timber.tag("VotvViewModel").e(it, "Import failed")
                _importState.value = ImportState(error = context.getString(R.string.votv_import_error_failed))
            }
        }
    }

    fun clearImportedGame() {
        PrefManager.votvGameAppId = null
        _gameAppId.value = null
    }

    fun consumeError() {
        _importState.value = _importState.value.copy(error = null)
    }

    /**
     * Defaults the container to hybrid external display mode with swap OFF, so the dual-screen
     * hub works with no settings UI: the game stays on this activity's own screen (untouched)
     * and the hub renders on the second display via ExternalDisplayInputController, instead of
     * moving the game's surface between displays. There's no settings screen yet for the user to
     * have overridden this, so it's safe (and needed, to fix up already-created containers) to
     * re-apply unconditionally rather than gating on first creation.
     */
    private fun prepareContainerDefaults(appId: String) {
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        container.setExternalDisplayMode(Container.EXTERNAL_DISPLAY_MODE_HYBRID)
        container.setExternalDisplaySwap(false)
        container.saveData()
    }
}
