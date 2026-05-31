package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

/**
 * Legacy OpenGL ES X-server view. Used for containers that rely on the VirGL
 * passthrough, which needs an EGL context shared from the X server's renderer.
 *
 * The Vulkan-backed default lives in {@link XServerView}.
 */
@SuppressLint("ViewConstructor")
public class XServerViewGL extends GLSurfaceView implements XServerRendererView {
    private final GLRenderer renderer;
    private final XServer xServer;
    private int frameRateLimit = 0;

    public XServerViewGL(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        this.xServer = xServer;
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public XServer getxServer() {
        return xServer;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        requestRender();
    }
}
