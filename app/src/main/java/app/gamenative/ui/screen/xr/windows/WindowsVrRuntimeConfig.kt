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
        const val EXTRA_ENABLED = "windowsVrEnabled"
        const val EXTRA_OPEN_COMPOSITE_ENABLED = "windowsVrOpenCompositeEnabled"

        fun from(container: Container): WindowsVrRuntimeConfig {
            return WindowsVrRuntimeConfig(
                enabled = container.getExtra(EXTRA_ENABLED, "true").toBoolean(),
                openCompositeEnabled = container.getExtra(EXTRA_OPEN_COMPOSITE_ENABLED, "false").toBoolean(),
                renderScalePercent = container.xrRenderScale.coerceIn(25, 100),
            )
        }

        fun setEnabled(container: Container, enabled: Boolean) {
            container.putExtra(EXTRA_ENABLED, enabled.toString())
        }

        fun setOpenCompositeEnabled(container: Container, enabled: Boolean) {
            container.putExtra(EXTRA_OPEN_COMPOSITE_ENABLED, enabled.toString())
        }
    }
}
