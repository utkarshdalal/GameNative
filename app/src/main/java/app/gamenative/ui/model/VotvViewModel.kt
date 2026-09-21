package app.gamenative.ui.model

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.ShooterModeConfig
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameImporter
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.Container
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
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
        // Re-checked on every visit to this screen (not just at import time) — prepareContainerDefaults
        // is a no-op once its version marker is current, but this lets a container created under an
        // older default (see VOTV_DEFAULTS_VERSION) get migrated the next time the user opens the app.
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
     * Applies the VOTV defaults (dual-screen mode, shooter-mode stick controls, physical-button
     * keybindings) once per container, gated on [VOTV_DEFAULTS_VERSION] so a bump here fixes up
     * containers created under an older default without re-stomping any manual tweaks the user
     * makes afterwards (via ShooterModeSettingsDialog / the physical-controller binding screen,
     * both reachable from XServerScreen's own runtime menu, same as in the full GameNative app).
     */
    private fun prepareContainerDefaults(appId: String) {
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val appliedVersion = container.getExtra(EXTRA_DEFAULTS_VERSION, "0").toIntOrNull() ?: 0
        if (appliedVersion >= VOTV_DEFAULTS_VERSION) return

        container.setExternalDisplayMode(Container.EXTERNAL_DISPLAY_MODE_HYBRID)
        container.setExternalDisplaySwap(false)

        // VOTV has no meaningful native gamepad support, so the sticks are only useful once
        // translated to keyboard/mouse: left stick -> WASD, right stick -> mouse-look.
        container.setShooterMode(true)
        container.setShooterConfig(
            ShooterModeConfig(
                movementType = ShooterModeConfig.MOVEMENT_GAMEPAD_LEFT_STICK,
                lookType = ShooterModeConfig.LOOK_GAMEPAD_RIGHT_STICK,
                sprintBinding = ShooterModeConfig.SPRINT_BINDING_SHIFT,
            ).toJson(),
        )

        ensureVotvControlsProfile(container)

        container.putExtra(EXTRA_DEFAULTS_VERSION, VOTV_DEFAULTS_VERSION.toString())
        container.saveData()
    }

    /**
     * Creates (once) a VOTV-specific controls profile with every physical button bound straight
     * to a keyboard/mouse key instead of the default raw-gamepad passthrough (which VOTV can't
     * read). No on-screen touch elements are added — this device already has physical buttons,
     * and touch fallback lives in the bottom-screen hub instead.
     *
     * Default mapping:
     *  A=E (interact)  B=Esc (back/pause)  X=F (flashlight)  Y=Tab (inventory/PDA)
     *  L1=Q  R1=C  L2=right-click  R2=left-click
     *  L3=Ctrl (crouch)  R3=Shift (sprint)
     *  D-pad=1/2/3/4 (hotbar)  Start=Esc  Select=Tab  Home=GameNative quick menu
     */
    private fun ensureVotvControlsProfile(container: Container) {
        val manager = InputControlsManager(context)
        val existingId = container.getExtra("profileId", "").toIntOrNull()
        val existing = existingId?.let { manager.getProfile(it) }
        val profile = if (existing != null && existing.name == VOTV_PROFILE_NAME) {
            existing
        } else {
            manager.createProfile(VOTV_PROFILE_NAME).also {
                container.putExtra("profileId", it.id.toString())
            }
        }

        val controller = profile.getController("*") ?: profile.addController("*")
        controller.getControllerBindings().toList().forEach(controller::removeControllerBinding)

        val bindings = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to Binding.KEY_E,
            KeyEvent.KEYCODE_BUTTON_B to Binding.KEY_ESC,
            KeyEvent.KEYCODE_BUTTON_X to Binding.KEY_F,
            KeyEvent.KEYCODE_BUTTON_Y to Binding.KEY_TAB,
            KeyEvent.KEYCODE_BUTTON_L1 to Binding.KEY_Q,
            KeyEvent.KEYCODE_BUTTON_R1 to Binding.KEY_C,
            KeyEvent.KEYCODE_BUTTON_L2 to Binding.MOUSE_RIGHT_BUTTON,
            KeyEvent.KEYCODE_BUTTON_R2 to Binding.MOUSE_LEFT_BUTTON,
            KeyEvent.KEYCODE_BUTTON_THUMBL to Binding.KEY_CTRL_L,
            KeyEvent.KEYCODE_BUTTON_THUMBR to Binding.KEY_SHIFT_L,
            KeyEvent.KEYCODE_DPAD_UP to Binding.KEY_1,
            KeyEvent.KEYCODE_DPAD_RIGHT to Binding.KEY_2,
            KeyEvent.KEYCODE_DPAD_DOWN to Binding.KEY_3,
            KeyEvent.KEYCODE_DPAD_LEFT to Binding.KEY_4,
            KeyEvent.KEYCODE_BUTTON_START to Binding.KEY_ESC,
            KeyEvent.KEYCODE_BUTTON_SELECT to Binding.KEY_TAB,
            KeyEvent.KEYCODE_BUTTON_MODE to Binding.OPEN_NAVIGATION_MENU,
        )
        for ((keyCode, binding) in bindings) {
            controller.addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(keyCode)
                    setBinding(binding)
                },
            )
        }

        profile.save()
    }

    private companion object {
        const val VOTV_PROFILE_NAME = "VOTV Controller"
        const val EXTRA_DEFAULTS_VERSION = "votvDefaultsVersion"
        const val VOTV_DEFAULTS_VERSION = 2
    }
}
