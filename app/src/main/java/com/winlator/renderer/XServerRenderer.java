package com.winlator.renderer;

import com.winlator.widget.FrameRating;
import com.winlator.widget.XServerRendererView;

/**
 * Shared surface used by the X server and call sites that don't care whether
 * the compositor is the Vulkan-backed {@link VulkanRenderer} or the legacy
 * OpenGL {@link GLRenderer} (only kept alive for the VirGL passthrough path).
 */
public interface XServerRenderer {
    boolean isFullscreen();
    void setCursorVisible(boolean visible);
    String getForceFullscreenWMClass();
    void setForceFullscreenWMClass(String wmClass);
    void setUnviewableWMClasses(String... classes);
    void setOnFrameRenderedListener(Runnable r);
    void setFrameRating(FrameRating fr);
    XServerRendererView getRendererView();
}
