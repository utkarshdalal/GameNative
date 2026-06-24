package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceControl;
import app.gamenative.R;
import android.graphics.Rect;
import timber.log.Timber;

import com.winlator.widget.FrameRating;
import com.winlator.widget.XServerRendererView;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

public class ASurfaceRenderer implements WindowManager.OnWindowModificationListener,
        Pointer.OnPointerMotionListener,
        XServerRenderer {
    static { System.loadLibrary("asurface_renderer"); }

    public final XServerView xServerView;
    private final XServer xServer;
    public final ViewTransformation viewTransformation = new ViewTransformation();
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private String forceFullscreenWMClass = null;
    private boolean containerCursorVisible = true;
    private boolean gameCursorVisible = true;
    private final Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private boolean surfaceInitialized = false;
    private int renderListSize = 0;
    private Rect cachedDesktopDst = null;
    private int cachedDesktopSrcW = 0, cachedDesktopSrcH = 0;
    private Window desktopWindow = null;
    private final ArrayList<RenderableWindow> renderList = new ArrayList<>();
    private static class RenderableWindow {
        public Drawable content;
        public Window window;
        public int rootX, rootY;
        public boolean isDesktopChild;
        public boolean isDesktopWindow;
        public void set(Drawable c, Window w, int x, int y, boolean dc, boolean dw) {
            content = c;
            window = w;
            rootX = x;
            rootY = y;
            isDesktopChild = dc;
            isDesktopWindow = dw;
        }
    }

    public ASurfaceRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            Context context = xServerView.getContext();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPremultiplied = false;
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    public interface ScanoutFrameListener {
        void onFrameLatched(int windowId, int serial, long presentTimeNs);
    }

    private final java.util.HashMap<Integer, WindowSurface> windowSurfaces = new java.util.HashMap<>();

    private static class WindowGeometry {
        final Rect src = new Rect();
        final Rect dst = new Rect();
    }

    private static class WindowSurface {
        int width, height;
        int zOrder;
        boolean visible = false;
        final Rect lastSrc = new Rect();
        final Rect lastDst = new Rect();
    }

    private volatile ScanoutFrameListener scanoutFrameListener = null;
    public void setScanoutFrameListener(ScanoutFrameListener listener) {
        this.scanoutFrameListener = listener;
    }

    @androidx.annotation.Keep
    public void onScanoutFrameComplete(long packed) {
        int windowId = (int)((packed >>> 32) & 0xFFFFFFFFL);
        int serial   = (int)(packed          & 0xFFFFFFFFL);
        long presentTimeNs = System.nanoTime();
        ASurfaceRenderer.ScanoutFrameListener listener = scanoutFrameListener;
        if (listener != null) {
            listener.onFrameLatched(windowId, serial, presentTimeNs);
        }
    }

    private int pendingPresentSerial = 0;
    public void setPendingPresentSerial(int serial) {
        pendingPresentSerial = serial;
    }
    private int consumePresentSerial() { int s = pendingPresentSerial; pendingPresentSerial = 0; return s; }

    private native boolean nativeInit(Surface surface, int screenWidth, int screenHeight);
    private native void nativeDestroy();
    private native void nativeInitScanout();
    private native boolean nativeReattachSurface(Surface surface);
    private native void nativeDestroyScanout();
    private native void nativeSetWindowBuffer(long contentId, long ahbPtr, int fenceFd, long windowId, long serial);
    private native void nativeScanoutSetCursorVisibility(boolean visible);
    private native void nativeRegisterWindowSC(long contentId, String debugName);
    private native void nativeUnregisterWindowSC(long contentId);
    private native void nativeScanoutSetCursorImage(java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(short x, short y, short hotX, short hotY, boolean cursorVisible);
    private native void nativeScanoutSetDst(int x, int y, int w, int h);
    private native void nativeSetSfCallbackTarget(Object rendererRef);
    private native void nativeBeginTransaction();
    private native void nativeApplyTransaction();
    private native void nativeUpdateWindow(long contentId, boolean visible, int zOrder,
            int srcL, int srcT, int srcR, int srcB,
            int dstL, int dstT, int dstR, int dstB);

    private WindowSurface getOrCreateWindowSurface(int contentId, int w, int h, String debugName)
    {
        WindowSurface ws = windowSurfaces.get(contentId);
        if (ws != null && (ws.width != w || ws.height != h)) {
            windowSurfaces.remove(contentId);
            ws = null;
        }
        if (ws == null) {
            try {
                ws = new WindowSurface();
                ws.width = w;
                ws.height = h;
                nativeRegisterWindowSC(contentId, debugName);
                windowSurfaces.put(contentId, ws);
            } catch (Exception e) {
                Timber.e("getOrCreateWindowSurface failed: %s", e);
                return null;
            }
        }
        return ws;
    }

    private Window findDesktopWindow() {
        if (desktopWindow != null && desktopWindow.attributes.isMapped()) {
            return desktopWindow;
        }
        desktopWindow = null;
        for (Window child : xServer.windowManager.rootWindow.getChildren()) {
            if (!child.attributes.isOverrideRedirect() && "explorer.exe".equals(child.getClassName())) {
                desktopWindow = child;
                break;
            }
        }
        return desktopWindow;
    }

    private boolean isDesktopChild(Window window) {
        Window desktop = findDesktopWindow();
        if (desktop == null) return false;
        Window parent = window.getParent();
        while (parent != null) {
            if (parent == desktop) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private boolean adjustRectLT(Rect src, Rect dst) {
        final int originalDstW = dst.width();
        final int originalDstH = dst.height();

        if (originalDstW <= 0 || originalDstH <= 0) {
            return false;
        }

        if (dst.left < 0) {
            int clip = -dst.left;
            int srcClip = (int)(((long) clip * src.width()) / originalDstW);
            src.left += srcClip;
            dst.left = 0;
        }

        if (dst.top < 0) {
            int clip = -dst.top;
            int srcClip = (int)(((long) clip * src.height()) / originalDstH);
            src.top += srcClip;
            dst.top = 0;
        }

        return src.right > src.left && src.bottom > src.top &&
                dst.right > dst.left && dst.bottom > dst.top;
    }

    private void computeLetterboxRect(int srcW, int srcH, int dstW, int dstH, Rect outRect) {
        float srcAspect = (float) srcW / srcH;
        float dstAspect = (float) dstW / dstH;
        int scaledW, scaledH;
        if (srcAspect > dstAspect) {
            scaledW = dstW;
            scaledH = (int)(dstW / srcAspect);
        } else {
            scaledH = dstH;
            scaledW = (int)(dstH * srcAspect);
        }
        int left   = (dstW - scaledW) / 2;
        int top    = (dstH - scaledH) / 2;
        outRect.set(left, top, left + scaledW, top + scaledH);
    }

    public void onSurfaceCreated(Surface surface) {
        if (surfaceInitialized) {
            boolean ok = nativeReattachSurface(surface);
            if (!ok) {
                surfaceInitialized = false;
                nativeDestroy();
            } else {
                updateScene();
                return;
            }
        }
        surfaceInitialized = nativeInit(surface, xServer.screenInfo.width, xServer.screenInfo.height);
        if (surfaceInitialized) {
            nativeSetSfCallbackTarget(this);
            updateTransform();
            nativeInitScanout();
            sendCursorToNative(lastCursor);
            updateScene(); // creates WindowSurface SCs
            resubmitAllBuffers(); // pushes buffers into the freshly created SCs
        }
    }

    private void resubmitAllBuffers() {
        try (XLock xl = xServer.lock(
                XServer.Lockable.WINDOW_MANAGER,
                XServer.Lockable.DRAWABLE_MANAGER)) {
            resubmitBuffersForWindow(xServer.windowManager.rootWindow);
        }
    }

    private void resubmitBuffersForWindow(Window window) {
        if (window != xServer.windowManager.rootWindow && window.attributes.isMapped() && window.getContent() != null) {
            if (windowSurfaces.containsKey(window.id)) {
                pushCpuImageToNative(window.id, window.getContent());
            }
        }
        for (Window child : window.getChildren()) resubmitBuffersForWindow(child);
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        updateTransform();
    }

    public void onSurfaceDestroyed() {
        if (surfaceInitialized) {
            nativeDestroyScanout();
            nativeDestroy();
            surfaceInitialized = false;
        }
        windowSurfaces.clear();
        cachedDesktopDst = null;
    }

    private void updateTransform() {
        nativeScanoutSetDst(viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
    }

    public void updateScene() {
        renderListSize = 0;
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(xServer.windowManager.rootWindow,
                    xServer.windowManager.rootWindow.getX(),
                    xServer.windowManager.rootWindow.getY());
        }
        pushRenderList();
    }

    private void collectWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            RenderableWindow rw;
            if (renderList.size() <= renderListSize) {
                rw = new RenderableWindow();
                renderList.add(rw);
            } else {
                rw = renderList.get(renderListSize);
            }
            renderListSize++;
            rw.set(window.getContent(), window, x, y, isDesktopChild(window), window == findDesktopWindow());
        }
        for (Window child : window.getChildren()) {
            collectWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void pushRenderList() {
        Timber.d("pushRenderList");
        nativeBeginTransaction();
        HashSet<Integer> visibleIds = new HashSet<>();
        for (int i = 0; i < renderListSize; i++) {
            RenderableWindow rw = renderList.get(i);
            if (rw.content == null) continue;

            int contentId = rw.window.id;
            visibleIds.add(contentId);
            String debugName = rw.window.getClassName();

            if (debugName == null || debugName.isEmpty()) debugName = "(x11_window)";
            WindowGeometry geom = new WindowGeometry();

            boolean geometryOk = computeWindowRect(
                    rw.rootX, rw.rootY,
                    rw.content.width, rw.content.height,
                    rw.isDesktopWindow, rw.isDesktopChild,
                    geom
            );

            if (unviewableWMClasses != null) {
                String wc = rw.window.getClassName();
                boolean skip = false;
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;
            }

            WindowSurface ws = getOrCreateWindowSurface(contentId, rw.content.width, rw.content.height, debugName);
            int srcW = rw.content.width;
            int srcH = rw.content.height;
            if (rw.content.getTexture() instanceof GPUImage g) {
                int ahbW = g.getWidth();
                int ahbH = g.getHeight();
                if (ahbW > 0 && ahbH > 0) {
                    srcW = ahbW;
                    srcH = ahbH;
                }
            }

            boolean needsUpdate = !ws.visible || ws.zOrder != i
                    || !geom.dst.equals(ws.lastDst)
                    || !geom.src.equals(ws.lastSrc);

            Timber.d(" [renderList i=%d] id=%d cls='%s' rootX=%d rootY=%d contentW=%d contentH=%d" +
                            " srcW=%d srcH=%d" +
                            " isDesktop=%b isDesktopChild=%b parentId=%d" +
                            " dst=[%d,%d,%d,%d] src=[%d,%d,%d,%d] needsUpdate=%b geometryOk=%b",
                    i, rw.window.id,
                    rw.window.getClassName(),
                    rw.rootX, rw.rootY,
                    rw.content.width, rw.content.height,
                    srcW, srcH,
                    rw.isDesktopWindow, rw.isDesktopChild,
                    rw.window.getParent() != null ? rw.window.getParent().id : -1,
                    geom.dst.left, geom.dst.top, geom.dst.right, geom.dst.bottom,
                    geom.src.left, geom.src.top, geom.src.right, geom.src.bottom,
                    needsUpdate, geometryOk);

            if (geometryOk && needsUpdate) {
                ws.visible = true;
                ws.zOrder = i;
                ws.lastDst.set(geom.dst);
                ws.lastSrc.set(geom.src);

                nativeUpdateWindow(
                        contentId, true, i,
                        geom.src.left, geom.src.top, geom.src.right, geom.src.bottom,
                        geom.dst.left, geom.dst.top, geom.dst.right, geom.dst.bottom
                );
            }
        }

        for (Map.Entry<Integer, WindowSurface> entry : windowSurfaces.entrySet()) {
            if (!visibleIds.contains(entry.getKey())) {
                WindowSurface ws = entry.getValue();
                if (ws.visible) {
                    ws.visible = false;
                    nativeUpdateWindow(entry.getKey(), false, ws.zOrder,
                            0, 0, 0, 0,
                            0, 0, 0, 0);
                }
            }
        }

        nativeApplyTransaction();
    }

    private void sendCursorToNative(Cursor cursor) {
        if (!containerCursorVisible) return;
        Drawable cd = cursor != null ? cursor.cursorImage : rootCursorDrawable;
        if (cursor != null && !cursor.isVisible()) return;
        if (cd == null || cd.getBuffer() == null) return;
        synchronized (cd.renderLock) {
            ByteBuffer buf = cd.getBuffer();
            nativeScanoutSetCursorImage(buf, cd.width, cd.height,
                    (short)(buf.capacity() / (cd.height * 4)));
        }
    }

    private void pushCpuImageToNative(int windowId, Drawable drawable) {
        if (drawable == null) return;
        Window pw = xServer.inputDeviceManager.getPointWindow();
        Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
        if (cursor != null && gameCursorVisible != cursor.isVisible()) {
            gameCursorVisible = cursor.isVisible();
            nativeScanoutSetCursorVisibility(containerCursorVisible && gameCursorVisible);
        }
        synchronized (drawable.renderLock) {
            if (drawable.getTexture() instanceof GPUImage g) {
                long ahbPtr = g.getScanoutHardwareBufferPtr();
                if (ahbPtr != 0) {
                    int fence = -1;
                    boolean isFallback = (ahbPtr == g.getHardwareBufferPtr());

                    if (isFallback) {
                        fence = g.unlock();
                    }

                    nativeSetWindowBuffer(windowId, ahbPtr, fence, 0, 0);
                    if (hudRef != null) hudRef.update();

                    if (isFallback) {
                        g.lock();
                    }
                }
            }
        }
    }

    private void pushGpuImageToNative(int windowId, Drawable drawable, int xSerial) {
        if (drawable == null) return;
        Window pw = xServer.inputDeviceManager.getPointWindow();
        Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
        if (cursor != null && gameCursorVisible != cursor.isVisible()) {
            gameCursorVisible = cursor.isVisible();
            nativeScanoutSetCursorVisibility(containerCursorVisible && gameCursorVisible);
        }
        synchronized (drawable.renderLock) {
            if (drawable.getTexture() instanceof GPUImage g) {
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    nativeSetWindowBuffer(windowId, ahbPtr, -1, windowId, xSerial);
                    if (hudRef != null) hudRef.update();
                }
            }
        }
    }

    private boolean computeWindowRect(int rootX, int rootY, int w, int h,
                                      boolean isDesktopWindow, boolean isDesktopChild,
                                      WindowGeometry out) {
        out.src.set(0, 0, w, h);

        if (isDesktopWindow && rootX == 0 && rootY == 0) {
            Timber.d("  computeWindowRect -> FULLSCREEN branch (isDesktopWindow=%b rootX=%d rootY=%d)",
                    isDesktopWindow, rootX, rootY);

            computeLetterboxRect(w, h, surfaceWidth, surfaceHeight, out.dst);
            if (cachedDesktopDst == null) cachedDesktopDst = new Rect();
            cachedDesktopDst.set(out.dst);
            cachedDesktopSrcW = w;
            cachedDesktopSrcH = h;
        } else if (isDesktopChild && cachedDesktopDst != null &&
                cachedDesktopSrcW > 0 && cachedDesktopSrcH > 0) {
            Timber.d("  computeWindowRect -> DESKTOP_CHILD branch cachedDst=[%d,%d,%d,%d] srcW=%d srcH=%d",
                    cachedDesktopDst.left, cachedDesktopDst.top,
                    cachedDesktopDst.right, cachedDesktopDst.bottom,
                    cachedDesktopSrcW, cachedDesktopSrcH);

            float scaleX = (float) cachedDesktopDst.width()  / cachedDesktopSrcW;
            float scaleY = (float) cachedDesktopDst.height() / cachedDesktopSrcH;
            int dstL = cachedDesktopDst.left + (int)(rootX * scaleX);
            int dstT = cachedDesktopDst.top  + (int)(rootY * scaleY);
            int dstR = dstL + (int)(w * scaleX);
            int dstB = dstT + (int)(h * scaleY);
            out.dst.set(dstL, dstT, dstR, dstB);
        }

        return adjustRectLT(out.src, out.dst);
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        // Timber.d("onUpdateWindowContent id=%s", window.id);
        Drawable drawable = window.getContent();
        if (drawable == null || !window.attributes.isMapped()) return;
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
        }

        pushGpuImageToNative(window.id, drawable, consumePresentSerial());
    }

    @Override
    public void onPointerMove(short x, short y) {
        Window pw = xServer.inputDeviceManager.getPointWindow();
        Cursor cursor = pw != null ? pw.attributes.getCursor() : null;

        if (cursor != null) {
            if (cursor != lastCursor) {
                lastCursor = cursor;
                sendCursorToNative(cursor);
            }

            short hotX = (short) cursor.hotSpotX;
            short hotY = (short) cursor.hotSpotY;

            nativeScanoutSetCursorPos(x, y, hotX, hotY, containerCursorVisible && gameCursorVisible);
        }
    }

    @Override
    public void onMapWindow(Window window) {
        Timber.d("onMapWindow id=%s", window.id);
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
        }
        Drawable content = window.getContent();
        if (content != null && content.width > 0 && content.height > 0) {
            if (!(content.getTexture() instanceof GPUImage)) {
                GPUImage g = new GPUImage(content.width, content.height);
                if (g.getHardwareBufferPtr() != 0) {
                    content.setTexture(g);
                }
            }
            if (content.getTexture() instanceof GPUImage) {
                content.setOnDrawListener(() -> {
                    if (windowSurfaces.containsKey(window.id)) { pushCpuImageToNative(window.id, content); }
                });
            }
        }
        updateScene();
    }

    @Override
    public void onUnmapWindow(Window window) {
        Timber.d("onUnmapWindow id=%s", window.id);
        Drawable content = window.getContent();
        if (content != null) content.setOnDrawListener(null);
        windowSurfaces.remove(window.id);
        nativeUnregisterWindowSC(window.id);
        updateScene();
    }

    @Override
    public void onDestroyWindow(Window window) {
        Timber.d("onDestroyWindow id=%s", window.id);
        if (window == desktopWindow) desktopWindow = null;
        Drawable content = window.getContent();
        if (content != null) content.setOnDrawListener(null);
        windowSurfaces.remove(window.id);
        nativeUnregisterWindowSC(window.id);
        updateScene();
    }

    @Override public void onChangeWindowZOrder(Window window) {
        Timber.d("onChangeWindowZOrder id=%s", window.id);
        updateScene();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        Timber.d("onUpdateWindowGeometry id=%s resized=%b", window.id, resized);
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
        }
        if (resized) {
            windowSurfaces.remove(window.id);
            nativeUnregisterWindowSC(window.id);
            Drawable newContent = window.getContent();
            if (newContent != null && newContent.width > 0 && newContent.height > 0) {
                newContent.setOnDrawListener(() -> {
                    if (windowSurfaces.containsKey(window.id)) pushCpuImageToNative(window.id, newContent);
                });
            }
        }
        updateScene();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        Timber.d("onUpdateWindowAttributes id=%s", window.id);
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            Window pw = xServer.inputDeviceManager.getPointWindow();
            if (pw == window) {
                lastCursor = window.attributes.getCursor();
                sendCursorToNative(lastCursor);
            }
        }
    }

    public void setCursorVisible(boolean visible) {
        containerCursorVisible = visible;
        if (visible) {
            sendCursorToNative(lastCursor);
        } else {
            nativeScanoutSetCursorVisibility(false);
        }
    }

    private boolean isFullscreenWindow(Window window) {
        if (forceFullscreenWMClass != null) {
            String wc = window.getClassName();
            return wc != null && wc.contains(forceFullscreenWMClass);
        }
        return false;
    }

    private FrameRating hudRef = null;
    @Override
    public void setFrameRating(FrameRating fr) { hudRef = fr; }
    @Override
    public String getForceFullscreenWMClass() { return forceFullscreenWMClass; }
    @Override
    public void setForceFullscreenWMClass(String wmClass) { this.forceFullscreenWMClass = wmClass; }
    @Override
    public void setOnFrameRenderedListener(Runnable r) {  }
    @Override
    public XServerRendererView getRendererView() { return xServerView; }
    @Override
    public boolean isFullscreen() { return false; }
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }
}
