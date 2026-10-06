package app.gamenative.ui.screen.xr.windows

import com.winlator.container.Container

data class WindowsVrRuntimeConfig(
    val enabled: Boolean,
    val openCompositeEnabled: Boolean,
    val controlPort: Int = 38476,
    // 3: the game may start before the headset session exists.
    val protocolVersion: Int = 3,
    val runtimeDirectory: String = "C:\\gamenative-xr",
    val runtimeManifest: String = "C:\\gamenative-xr\\active_runtime.json",
    val transportEndpoint: String = "@gamenative-xr",
    val renderScalePercent: Int = 100,
    val refreshRateHz: Int = 72,
) {
    companion object {
        fun from(container: Container): WindowsVrRuntimeConfig {
            return WindowsVrRuntimeConfig(
                enabled = container.getExtra("windowsVrEnabled", "false").toBoolean(),
                openCompositeEnabled = container.getExtra("windowsVrOpenCompositeEnabled", "false").toBoolean(),
                renderScalePercent = container.xrRenderScale.coerceIn(25, 100),
                refreshRateHz = container.xrRefreshRate.coerceIn(60, 120),
            )
        }
    }
}
