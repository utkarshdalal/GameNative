package app.gamenative.ui.screen.xr

import com.winlator.renderer.VulkanXrFrameBridge
import timber.log.Timber

/**
 * [VulkanXrFrameBridge] implementation for the default (dxvk/Vulkan) renderer path — unlike
 * [DirectGLBridge], there's no buffer allocation or import to do here at all: VulkanRenderer
 * already turns the game's rendered frame into an AHardwareBuffer on every real frame (see
 * VulkanRenderer.onUpdateWindowContentDirect's nativeUpdateWindowContentAHB call — this is the
 * method PresentExtension actually routes real DXVK/Vulkan frame presents to, NOT
 * onUpdateWindowContent), so this just forwards that same raw pointer to the native OpenXR
 * session, which imports it the same way
 * [DirectGLBridge]'s shared buffer is imported (see xr_immersive.cpp's
 * importSharedBufferIfNeeded — it doesn't care which renderer produced the buffer).
 *
 * [onFrame] fires on essentially every frame a Vulkan-rendered window updates — there's no
 * "only when some other condition is met" gate here (an earlier version assumed one tied to
 * VulkanRenderer's "zero-copy scanout" path, which turned out to be unreachable dead code —
 * see VulkanXrFrameBridge's kdoc).
 */
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
