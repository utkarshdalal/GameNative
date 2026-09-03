package app.gamenative.ui.screen.xr.windows

import com.winlator.container.Container

data class WindowsVrRuntimeConfig(
    val enabled: Boolean,
    val openCompositeEnabled: Boolean,
    val controlPort: Int = 38476,
    val protocolVersion: Int = 2,
    val runtimeDirectory: String = "C:\\gamenative-xr",
    val runtimeManifest: String = "C:\\gamenative-xr\\active_runtime.json",
    val transportEndpoint: String = "@gamenative-xr",
    val renderScalePercent: Int = 100,
) {
    companion object {
        fun from(container: Container): WindowsVrRuntimeConfig {
            return WindowsVrRuntimeConfig(
                enabled = container.getExtra("windowsVrEnabled", "true").toBoolean(),
                openCompositeEnabled = container.getExtra("windowsVrOpenCompositeEnabled", "false").toBoolean(),
                renderScalePercent = container.xrRenderScale.coerceIn(25, 100),
            )
        }
    }
}
