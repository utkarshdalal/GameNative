package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import com.winlator.core.AppUtils;
import com.winlator.renderer.ASurfaceRenderer;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.gamenative.PluviaApp;
import app.gamenative.display.GameNativeDrm;
import timber.log.Timber;

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

    /** Gamescope mode: the gndrm daemon consumes this view's Surface (two producers are invalid), so surface callbacks drive GameNativeDrm's JNI lifecycle, not the X11 renderer. */
    private boolean gamescopeMode;

    public void setGamescopeMode(boolean gamescopeMode) {
        this.gamescopeMode = gamescopeMode;
    }

    public boolean isGamescopeMode() {
        return gamescopeMode;
    }

    private String scanoutLayoutOverride;

    public void setScanoutLayoutOverride(String scanoutLayoutOverride) {
        this.scanoutLayoutOverride = scanoutLayoutOverride;
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (isGamescopeMode()) {
            // Seed from the panel's real size (getWidth/Height may predate layout); surfaceChanged
            // replaces it with the real holder size. The container preset is only a last resort:
            // it's an internal desktop resolution, and seeding it starts the daemon at 720p.
            int[] defaultSize = AppUtils.parseScreenSize(PluviaApp.getNativeDisplaySize());
            if (defaultSize == null) defaultSize = AppUtils.parseScreenSize(PluviaApp.getDefaultScreenSize());
            if (defaultSize == null) {
                defaultSize = new int[2];
                defaultSize[0] = getWidth();
                defaultSize[1] = getHeight();
            }

            // A display power cycle destroys and re-creates this Surface while the session (and gamescope)
            // keeps running, so the daemon retargets the new window instead of restarting; it is only started
            // when none is running.
            int rc = GameNativeDrm.nativeRetargetSurface(holder.getSurface(), defaultSize[0], defaultSize[1]);
            if (rc == GameNativeDrm.E_NO_SESSION) {
                try {
                    if (scanoutLayoutOverride != null && !scanoutLayoutOverride.isEmpty())
                        Os.setenv("GNDRM_SCANOUT_LAYOUT", scanoutLayoutOverride, true);
                    else
                        Os.unsetenv("GNDRM_SCANOUT_LAYOUT");
                } catch (ErrnoException e) {
                    Timber.w(e, "Could not set GNDRM_SCANOUT_LAYOUT");
                }
                GameNativeDrm.nativeStart("gndrm", holder.getSurface(), defaultSize[0], defaultSize[1]);
            }
            return;
        }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceCreated(holder.getSurface());
        }
        if (renderer instanceof ASurfaceRenderer scRenderer) {
            scRenderer.onSurfaceCreated(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (isGamescopeMode()) {
            // Forward the real geometry: the daemon re-adopts the window (a size change on the same window is
            // a cheap geometry update) and keeps the retained frame on the layer.
            GameNativeDrm.nativeRetargetSurface(holder.getSurface(), width, height);
            return;
        }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceChanged(width, height);
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (isGamescopeMode()) {
            // A display power cycle destroys this Surface but must NOT end the session: the daemon, its
            // sockets and gamescope's connection stay alive, and the next surfaceCreated retargets the new
            // window.
            return;
        }
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
