package com.winlator.widget;

import android.content.Context;

import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.XServer;

/**
 * Renderer-agnostic view methods that the X server and shared infra need.
 * Implemented by {@link XServerView} (Vulkan/SurfaceView) and
 * {@link XServerViewGL} (legacy GLSurfaceView, used for the VirGL path).
 */
public interface XServerRendererView {
    Context getContext();
    void queueEvent(Runnable r);
    void requestRender();
    void setFrameRateLimit(int limit);
    void onResume();
    void onPause();
    XServer getxServer();
    XServerRenderer getRenderer();
}
