package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import com.winlator.renderer.ASurfaceRenderer;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback, XServerRendererView {
    private XServerRenderer renderer;
    private final XServer xServer;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private int frameRateLimit = 0;

    public XServerView(Context context, XServer xServer, String selectedRenderer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        this.xServer = xServer;
        if (selectedRenderer.equalsIgnoreCase("vulkan")) {
            Drawable.DRAWABLE_ASR_MODE(false);
            renderer = new VulkanRenderer(this, xServer);
        } else if (selectedRenderer.equalsIgnoreCase("surfaceflinger")) {
            Drawable.DRAWABLE_ASR_MODE(true);
            renderer = new ASurfaceRenderer(this, xServer);
        }
    }

    public XServer getxServer() {
        return xServer;
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceCreated(holder.getSurface());
        }
        if (renderer instanceof ASurfaceRenderer scRenderer) {
            scRenderer.onSurfaceCreated(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceChanged(width, height);
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceDestroyed();
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceDestroyed();
        }
    }

    public void queueEvent(Runnable r) {
        eventExecutor.execute(r);
    }

    public void onPause() {}
    public void onResume() {}

    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.setFpsLimit(this.frameRateLimit);
        }
    }

    public void requestRender() {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.queueSceneUpdate();
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.updateScene();
        }
    }
}
