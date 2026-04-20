package com.winlator.xserver;

import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;

import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XInput2Extension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    private boolean isGrabbed = false;
    private XClient grabbingClient = null;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private boolean simulateTouchScreen = false;

    public XServer(ScreenInfo screenInfo) {
        Log.d("XServer", "Creating xServer " + screenInfo);
        this.screenInfo = screenInfo;
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            int minX = 0, minY = 0;
            int maxX = screenInfo.width - 1, maxY = screenInfo.height - 1;
            short clampedX = 0, clampedY = 0;

            // ClipCursor
            Rect confinement = grabManager.getConfinementBounds();
            if (confinement != null) {
                minX = Math.max(minX, confinement.left);
                minY = Math.max(minY, confinement.top);
                maxX = Math.min(maxX, confinement.right - 1);
                maxY = Math.min(maxY, confinement.bottom - 1);

                clampedX = (short) Mathf.clamp(pointer.getX() + dx, minX, maxX);
                clampedY = (short) Mathf.clamp(pointer.getY() + dy, minY, maxY);

                pointer.setPosition(clampedX, clampedY);
            } else {
                // Legacy path for old Proton builds with broken ClipCursor
                // Do NOT remove without confirming no regressions with:
                // - Games that rely on XWarpPointer (see WindowRequests.java:warpPointer)
                // - Games that call ClipCursor and happen to work well with this workaround
                short softMarginX = (short)(screenInfo.width * 0.05f);
                short softMarginY = (short)(screenInfo.height * 0.05f);
                short x = (short)Mathf.clamp(pointer.getX() + dx, -softMarginX, screenInfo.width - 1 + softMarginX);
                short y = (short)Mathf.clamp(pointer.getY() + dy, -softMarginY, screenInfo.height - 1 + softMarginY);

                pointer.setPosition(x, y);

                clampedX = x;
                clampedY = y;

                if (x < 0) {
                    clampedX = 0;
                }
                else if (x > screenInfo.width - 1) {
                    clampedX = (short) (screenInfo.width - 1);
                }
                if (y < 0) {
                    clampedY = 0;
                }
                else if (y > screenInfo.height - 1) {
                    clampedY = (short) (screenInfo.height - 1);
                }

                pointer.setX(clampedX);
                pointer.setY(clampedY);
            }

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            xi.emitRawMotion(2, dx, dy);
        }
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            xi.emitRawButton(2, buttonCode.code(), true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            xi.emitRawButton(2, buttonCode.code(), false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.getId(), keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.getId());
        }
    }

    private void registerExtension(Extension ext, int[] nextEventId, int[] nextErrorId) {
        if (ext.getNumEvents() > 0) {
            ext.setFirstEventId((byte) nextEventId[0]);
            nextEventId[0] += ext.getNumEvents();
        }
        if (ext.getNumErrors() > 0) {
            ext.setFirstErrorId((byte) nextErrorId[0]);
            nextErrorId[0] += ext.getNumErrors();
        }
        extensions.put(ext.getMajorOpcode(), ext);
    }

    private void setupExtensions() {
        int[] nextEventId = {64};
        int[] nextErrorId = {128};

        registerExtension(new BigReqExtension(),    nextEventId, nextErrorId);
        registerExtension(new MITSHMExtension(),    nextEventId, nextErrorId);
        registerExtension(new DRI3Extension(),      nextEventId, nextErrorId);
        registerExtension(new PresentExtension(),   nextEventId, nextErrorId);
        registerExtension(new SyncExtension(),      nextEventId, nextErrorId);
        registerExtension(new XInput2Extension(),   nextEventId, nextErrorId);
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        if (this.isGrabbed) {
            return this.grabbingClient == client;
        }
        return false;
    }

}
