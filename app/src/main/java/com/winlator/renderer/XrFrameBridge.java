package com.winlator.renderer;

/**
 * Optional collaborator for {@link GLRenderer}, set only by the Meta Quest immersive launch path
 * (see app.gamenative.ui.screen.xr) — null (the default) for every other launch, on every other
 * platform, with zero behavior change to GLRenderer's normal rendering.
 *
 * The point of this is to let the game render directly into a GPU buffer that the immersive
 * session's OpenXR quad can sample from, instead of screen-scraping the finished frame back off
 * the SurfaceView (PixelCopy) — which was measurably the dominant cost of that pipeline (a GPU
 * readback plus multiple full-frame CPU memory copies, every frame). GLRenderer keeps rendering
 * exactly the same draw calls either way; only the framebuffer it targets changes.
 */
public interface XrFrameBridge {
    /**
     * Called at the very start of drawScene(), before any GL state is touched for this frame.
     * Implementations bind their own target framebuffer (wrapping a shared GPU buffer) here.
     * Returns the [width, height] GLRenderer should render at this frame, or null if the bridge
     * isn't ready yet (e.g. the shared buffer hasn't been set up) — GLRenderer falls back to
     * rendering into its own surface as normal for that frame.
     */
    int[] beginFrame();

    /** Called right after GLRenderer finishes issuing draw calls for this frame (still with the
     * bridge's framebuffer bound) — implementations do any per-frame teardown/flush here. */
    void endFrame();
}
