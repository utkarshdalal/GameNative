package com.winlator.renderer;

/** Optional collaborator for {@link GLRenderer}, set only by the Meta Quest immersive launch path (see app.gamenative.ui.screen.xr) — null (the default). */
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
