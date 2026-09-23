package app.gamenative.ui.screen.xr

import com.winlator.renderer.VulkanXrFrameBridge
import timber.log.Timber

/** [VulkanXrFrameBridge] implementation for the default (dxvk/Vulkan) renderer path — unlike [DirectGLBridge], there's no buffer allocation or import to. */
class DirectVulkanBridge(
    private val onFrame: (ahbPtr: Long, width: Int, height: Int) -> Unit,
) : VulkanXrFrameBridge {
    private var announced = false

    override fun onScanoutBuffer(ahbPtr: Long, width: Int, height: Int) {
        if (!announced) {
            announced = true
            Timber.i(
                "Immersive: VulkanRenderer AHardwareBuffer observed (%dx%d) — direct-render path active, PixelCopy of the game layer stopped",
                width,
                height,
            )
        }
        onFrame(ahbPtr, width, height)
    }
}
