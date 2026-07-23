package com.winlator.renderer;

/**
 * Null except for the Meta Quest immersive path (see ImmersiveXrActivity's DirectVulkanBridge).
 * VulkanRenderer already turns the game's rendered frame into an AHardwareBuffer on every frame
 * (see onUpdateWindowContentDirect's nativeUpdateWindowContentAHB call) — this hook observes
 * that exact same buffer pointer, at the exact same call site, so the OpenXR session can import
 * it too instead of falling back to PixelCopy screen-scraping. VulkanRenderer's own behavior is
 * unchanged whether or not a bridge is attached.
 *
 * Two things had to be found and fixed before this actually fired for a real game, in order:
 * 1. Named onScanoutBuffer for historical reasons — it was originally attached to
 *    VulkanRenderer's "zero-copy scanout" path (nativeScanoutSetBuffer), which turned out to be
 *    unreachable: {@code Drawable.isDirectScanout()} is hardcoded false and never overridden
 *    anywhere in this codebase, so that branch never executes.
 * 2. Moved to the AHB-upload branch instead (nativeUpdateWindowContentAHB) — but in the WRONG
 *    method at first ({@code onUpdateWindowContent}, called via the generic WindowManager
 *    listener path). Confirmed via logging that method is never even invoked for a real
 *    DXVK/Vulkan game at all: {@code PresentExtension}'s pixmapPresent routes VulkanRenderer
 *    specifically to {@code onUpdateWindowContentDirect} for every frame present instead — that
 *    is the one actually-live call site this hook now lives in.
 */
public interface VulkanXrFrameBridge {
    /**
     * ahbPtr is the same raw AHardwareBuffer* (as a jlong) VulkanRenderer is handing to
     * nativeUpdateWindowContentAHB for on-screen presentation — read-only, do not release/free it.
     * Called on every real frame update for a Vulkan-rendered window (see this interface's kdoc
     * for why "scanout" in the name is no longer literally accurate).
     */
    void onScanoutBuffer(long ahbPtr, int width, int height);
}
