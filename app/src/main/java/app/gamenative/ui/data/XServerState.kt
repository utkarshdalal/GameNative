package app.gamenative.ui.data

import androidx.compose.runtime.saveable.mapSaver
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.core.DXVKHelper
import com.winlator.core.KeyValueSet
import com.winlator.core.WineInfo

data class XServerState(
    var winStarted: Boolean = false,
    val dxwrapper: String = Container.DEFAULT_DXWRAPPER,
    val dxwrapperConfig: KeyValueSet? = null,
    val screenSize: String = PluviaApp.getDefaultScreenSize(),
    val wineInfo: WineInfo = WineInfo.MAIN_WINE_VERSION,
    val graphicsDriver: String = Container.DEFAULT_GRAPHICS_DRIVER,
    val graphicsDriverVersion: String = "",
    val audioDriver: String = Container.DEFAULT_AUDIO_DRIVER,
) {
    companion object {
        val Saver = mapSaver(
            save = { state ->
                mapOf(
                    "winStarted" to state.winStarted,
                    "dxwrapper" to state.dxwrapper,
                    "dxwrapperConfig" to (state.dxwrapperConfig?.data ?: ""),
                    "screenSize" to state.screenSize,
                    "wineInfo" to state.wineInfo,
                    "graphicsDriver" to state.graphicsDriver,
                    "graphicsDriverVersion" to state.graphicsDriverVersion,
                    "audioDriver" to state.audioDriver,
                )
            },
            restore = { map ->
                XServerState(
                    winStarted = map["winStarted"] as Boolean,
                    dxwrapper = map["dxwrapper"] as String,
                    dxwrapperConfig = DXVKHelper.parseConfig(map["dxwrapperConfig"] as String),
                    screenSize = map["screenSize"] as String,
                    wineInfo = map["wineInfo"] as WineInfo,
                    graphicsDriver = map["graphicsDriver"] as String,
                    graphicsDriverVersion = map["graphicsDriverVersion"] as String? ?: "",
                    audioDriver = map["audioDriver"] as String,
                )
            },
        )
    }
}
