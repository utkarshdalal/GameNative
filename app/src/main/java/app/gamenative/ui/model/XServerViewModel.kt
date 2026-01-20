package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import app.gamenative.ui.data.XServerState
import com.winlator.core.KeyValueSet
import com.winlator.core.WineInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

@HiltViewModel
class XServerViewModel @Inject constructor() : ViewModel() {
    private val _xServerState = MutableStateFlow(XServerState())
    val xServerState: StateFlow<XServerState> = _xServerState.asStateFlow()

    // fun setEnvVars(envVars: EnvVars) {
    //     _xServerState.update { currentState ->
    //         currentState.copy(envVars = envVars)
    //     }
    // }

    fun setDxwrapper(dxwrapper: String) {
        _xServerState.update { currentState ->
            currentState.copy(dxwrapper = dxwrapper)
        }
    }

    fun setDxwrapperConfig(dxwrapperConfig: KeyValueSet?) {
        _xServerState.update { currentState ->
            Timber.i("Setting dxwrapperConfig to $dxwrapperConfig")
            currentState.copy(dxwrapperConfig = dxwrapperConfig)
        }
    }

    // fun setShortcut(shortcut: Shortcut?) {
    //     _xServerState.update { currentState ->
    //         currentState.copy(shortcut = shortcut)
    //     }
    // }

    fun setScreenSize(screenSize: String) {
        _xServerState.update { currentState ->
            currentState.copy(screenSize = screenSize)
        }
    }

    fun setWineInfo(wineInfo: WineInfo) {
        _xServerState.update { currentState ->
            currentState.copy(wineInfo = wineInfo)
        }
    }

    fun setGraphicsDriver(graphicsDriver: String) {
        _xServerState.update { currentState ->
            currentState.copy(graphicsDriver = graphicsDriver)
        }
    }

    fun setAudioDriver(audioDriver: String) {
        _xServerState.update { currentState ->
            currentState.copy(audioDriver = audioDriver)
        }
    }
}
