package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.SparseArray;
import android.view.Choreographer;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.renderer.ASurfaceRenderer;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.PresentCompleteNotify;
import com.winlator.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class PresentExtension implements Extension, ASurfaceRenderer.ScanoutFrameListener {

    public static final byte MAJOR_OPCODE = -103;

    public static final int VSYNC_LIMIT = -1;

    private static final int    FAKE_INTERVAL_DEFAULT_US = 1_000_000 / 60;
    private static final long   FIRE_EARLY_NS            = 500_000L;   // 0.5 ms
    private static final long   MAX_BLOCK_NS             = 50_000_000L; // 50 ms

    private final AtomicLong lastDeadlineNs = new AtomicLong(0L);

    private volatile long targetIntervalNs = 0L;
    private volatile int  frameRateLimit   = 0;
    private final Object  pacingLock       = new Object();

    private HandlerThread choreographerThread;
    private Handler       choreographerHandler;
    private Choreographer vsyncChoreographer;
    private volatile boolean vsyncMode = false;
    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<PendingPresent>>
            vsyncQueue = new ConcurrentHashMap<>();

    private volatile boolean vsyncCallbackPosted = false;

    // SF-callback deferred presents (GPU scanout path)
    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<DeferredPresent>>
            deferredPresents = new ConcurrentHashMap<>();

    private volatile boolean sfCallbackActive = false;

    private static class DeferredPresent {
        final Window  window;
        final Pixmap  pixmap;
        final int     serial;
        final int     idleFence;
        final Mode    mode;
        final int     windowId;
        DeferredPresent(Window w, Pixmap p, int s, int f, Mode m, int wid) {
            window = w; pixmap = p; serial = s; idleFence = f; mode = m; windowId = wid;
        }
    }

    private static class PendingPresent {
        final Window window;
        final Pixmap pixmap;
        final int    serial;
        final int    idleFence;
        final long   deadlineNs;
        final Mode   mode;
        PendingPresent(Window w, Pixmap p, int s, int f, long d, Mode m) {
            window = w; pixmap = p; serial = s; idleFence = f; deadlineNs = d; mode = m;
        }
    }

    public enum Kind  { PIXMAP, MSC_NOTIFY }
    public enum Mode  { COPY, FLIP, SKIP }

    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static abstract class ClientOpcodes {
        static final byte QUERY_VERSION  = 0;
        static final byte PRESENT_PIXMAP = 1;
        static final byte SELECT_INPUT   = 3;
    }

    private static class Event {
        Window   window;
        XClient  client;
        int      id;
        Bitmask  mask;
    }

    @Override public String getName()         { return "Present"; }
    @Override public byte   getMajorOpcode()  { return MAJOR_OPCODE; }
    @Override public int    getNumEvents()    { return 2; }
    @Override public int    getNumErrors()    { return 0; }
    @Override public void   setFirstEventId(byte id) { this.firstEventId = id; }
    @Override public void   setFirstErrorId(byte id) { this.firstErrorId = id; }
    @Override public byte   getFirstEventId() { return firstEventId; }
    @Override public byte   getFirstErrorId() { return firstErrorId; }

    public void setFrameRateLimit(int limit) {
        synchronized (pacingLock) {
            frameRateLimit = limit;
            if (limit == VSYNC_LIMIT) {
                targetIntervalNs = 0L;
                vsyncMode = true;
                ensureChoreographerThread();
            } else {
                vsyncMode = false;
                targetIntervalNs = (limit > 0) ? 1_000_000_000L / limit : 0L;
            }
            lastDeadlineNs.set(0L);
        }
    }

    public int  getFrameRateLimit() { return frameRateLimit; }
    public boolean isVsyncMode()    { return vsyncMode; }

    private void ensureChoreographerThread() {
        if (choreographerThread != null && choreographerThread.isAlive()) return;
        choreographerThread = new HandlerThread("PresentExt-Vsync",
                android.os.Process.THREAD_PRIORITY_DISPLAY);
        choreographerThread.start();
        choreographerHandler = new Handler(choreographerThread.getLooper());
        choreographerHandler.post(() -> {
            vsyncChoreographer = Choreographer.getInstance();
            vsyncChoreographer.postFrameCallback(this::onVsyncFrame);
            vsyncCallbackPosted = true;
        });
    }

    private void onVsyncFrame(long frameTimeNs) {
        vsyncCallbackPosted = false;
        boolean anyPending = false;
        for (ConcurrentLinkedQueue<PendingPresent> queue : vsyncQueue.values()) {
            PendingPresent pp;
            while ((pp = queue.peek()) != null) {
                if (frameTimeNs >= pp.deadlineNs - FIRE_EARLY_NS) {
                    queue.poll();
                    firePresentEvents(pp.window, pp.pixmap, pp.serial,
                            pp.idleFence, frameTimeNs, Kind.PIXMAP, pp.mode);
                } else {
                    anyPending = true;
                    break;
                }
            }
        }
        if (vsyncMode || anyPending) {
            vsyncCallbackPosted = true;
            vsyncChoreographer.postFrameCallback(this::onVsyncFrame);
        }
    }

    private void enqueueVsync(int windowKey, PendingPresent pp) {
        vsyncQueue.computeIfAbsent(windowKey, k -> new ConcurrentLinkedQueue<>()).add(pp);
        // Ensure callback is running; safe to call from any thread since
        // Choreographer.postFrameCallback is thread-safe.
        if (!vsyncCallbackPosted && vsyncChoreographer != null) {
            vsyncCallbackPosted = true;
            vsyncChoreographer.postFrameCallback(this::onVsyncFrame);
        }
    }

    private long acquirePacingSlot() {
        long intervalNs = targetIntervalNs;
        if (intervalNs <= 0L) return System.nanoTime();

        long deadlineNs;
        synchronized (pacingLock) {
            long now  = System.nanoTime();
            long last = lastDeadlineNs.get();
            // Drift-resistant: if we're already behind, snap to now
            deadlineNs = (last + intervalNs > now) ? last + intervalNs : now;
            lastDeadlineNs.set(deadlineNs);
        }

        long waitNs = deadlineNs - System.nanoTime();
        if (waitNs > FIRE_EARLY_NS && waitNs <= MAX_BLOCK_NS) {
            try {
                long ms  = (waitNs - FIRE_EARLY_NS) / 1_000_000L;
                int  ns  = (int)((waitNs - FIRE_EARLY_NS) % 1_000_000L);
                Thread.sleep(ms, ns);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return deadlineNs;
    }

    @Override
    public void onFrameLatched(int windowId, int serial, long presentTimeNs) {
        sfCallbackActive = true;

        ConcurrentLinkedQueue<DeferredPresent> queue = deferredPresents.get(windowId);
        if (queue == null) return;

        DeferredPresent dp;
        while ((dp = queue.peek()) != null) {
            if (dp.serial <= serial || isSerialWrapAround(dp.serial, serial)) {
                queue.poll();
                firePresentEvents(dp.window, dp.pixmap, dp.serial,
                        dp.idleFence, presentTimeNs, Kind.PIXMAP, dp.mode);
            } else {
                break;
            }
        }
        if (queue.isEmpty()) deferredPresents.remove(windowId, queue);
    }

    private static boolean isSerialWrapAround(int queued, int latched) {
        return (queued - latched) > (1 << 30);
    }

    public void close() {
        // Stop vsync thread
        if (choreographerThread != null) {
            choreographerThread.quitSafely();
            choreographerThread = null;
            choreographerHandler = null;
            vsyncChoreographer = null;
        }
        // Drain deferred GPU-callback presents
        long nowNs = System.nanoTime();
        for (ConcurrentLinkedQueue<DeferredPresent> queue : deferredPresents.values()) {
            DeferredPresent dp;
            while ((dp = queue.poll()) != null) {
                firePresentEvents(dp.window, dp.pixmap, dp.serial,
                        dp.idleFence, nowNs, Kind.PIXMAP, dp.mode);
            }
        }
        deferredPresents.clear();
        // Drain vsync queue
        for (ConcurrentLinkedQueue<PendingPresent> queue : vsyncQueue.values()) {
            PendingPresent pp;
            while ((pp = queue.poll()) != null) {
                firePresentEvents(pp.window, pp.pixmap, pp.serial,
                        pp.idleFence, nowNs, Kind.PIXMAP, pp.mode);
            }
        }
        vsyncQueue.clear();
    }

    private void presentPixmap(XClient client, XInputStream inputStream,
                               XOutputStream outputStream)
            throws IOException, XRequestError {

        int windowId_x11 = inputStream.readInt();
        int pixmapId     = inputStream.readInt();
        int serial       = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId_x11);
        if (window == null) throw new BadWindow(windowId_x11);
        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content     = window.getContent();
        int contentDepth     = content.visual.depth;
        int pixmapDepth      = pixmap.drawable.visual.depth;
        boolean depthCompat  = (contentDepth == pixmapDepth) ||
                ((contentDepth == 24 || contentDepth == 32) &&
                        (pixmapDepth  == 24 || pixmapDepth  == 32));
        if (!depthCompat) throw new BadMatch();

        final XServerRenderer xr = client.xServer.getRenderer();
        final ASurfaceRenderer sc = (xr instanceof ASurfaceRenderer) ? (ASurfaceRenderer) xr : null;
        final VulkanRenderer vr   = (xr instanceof VulkanRenderer)   ? (VulkanRenderer) xr   : null;

        Mode mode = Mode.COPY;
        final int javaWindowId = System.identityHashCode(content);

        final long deadlineNs = vsyncMode ? System.nanoTime() : acquirePacingSlot();

        synchronized (content.renderLock) {
            if (sc != null) {
                content.setTexture(pixmap.drawable.getTexture());
                mode = Mode.FLIP;
                if (window.attributes.isMapped()) {
                    sc.setPendingPresentSerial(serial);
                    sc.onUpdateWindowContent(window);
                }
            }
            else if (vr != null && window.attributes.isMapped()) {
                mode = Mode.COPY;
                vr.onUpdateWindowContentDirect(window, pixmap.drawable, xOff, yOff);
            }
            // GLRenderer
            else {
                mode = Mode.COPY;
                content.copyArea((short)0, (short)0, xOff, yOff,
                        pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
            }
        }

        if (mode == Mode.FLIP && sc != null) {
            if (sfCallbackActive) {
                ConcurrentLinkedQueue<DeferredPresent> queue =
                        deferredPresents.computeIfAbsent(
                                javaWindowId, k -> new ConcurrentLinkedQueue<>());
                queue.add(new DeferredPresent(
                        window, pixmap, serial, idleFence, mode, javaWindowId));
            } else {
                firePresentEvents(window, pixmap, serial, idleFence,
                        deadlineNs, Kind.PIXMAP, mode);
            }
            return;
        }

        if (vsyncMode && vsyncChoreographer != null) {
            int key = System.identityHashCode(window);
            enqueueVsync(key, new PendingPresent(
                    window, pixmap, serial, idleFence, deadlineNs, mode));
        } else {
            firePresentEvents(window, pixmap, serial, idleFence,
                    deadlineNs, Kind.PIXMAP, mode);
        }
    }

    private void firePresentEvents(Window window, Pixmap pixmap, int serial,
                                   int idleFence, long ustNs, Kind kind, Mode mode) {
        long ustUs = ustNs / 1_000L;
        long msc   = ustUs / FAKE_INTERVAL_DEFAULT_US;
        sendIdleNotify(window, pixmap, serial, idleFence);
        sendCompleteNotify(window, serial, kind, mode, ustUs, msc);
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0 && syncExtension != null) syncExtension.setTriggered(idleFence);
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window
                        && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(
                            new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode,
                                    long ustUs, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window
                        && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(
                            new PresentCompleteNotify(event.id, window, serial, kind, mode, ustUs, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream,
                                     XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void selectInput(XClient client, XInputStream inputStream,
                             XOutputStream outputStream) throws IOException, XRequestError {
        XServerRenderer xr = client.xServer.getRenderer();
        if (xr instanceof ASurfaceRenderer) {
            ((ASurfaceRenderer) xr).setScanoutFrameListener(this);
        }

        int      eventId  = inputStream.readInt();
        int      windowId = inputStream.readInt();
        Bitmask  mask     = new Bitmask(inputStream.readInt());
        Window   window   = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content    = window.getContent();
            final Texture old   = content.getTexture();
            if (old != null && !(old instanceof GPUImage)) {
                XServerRenderer r = client.xServer.getRenderer();
                if (r != null) {
                    r.getRendererView().queueEvent(
                            () -> VortekRendererComponent.destroyTexture(old));
                }
            }
            if (!(content.getTexture() instanceof GPUImage)) {
                content.setTexture(new GPUImage(content.width, content.height));
            }
        }

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();
                if (!mask.isEmpty()) event.mask = mask;
                else                 events.remove(eventId);
            } else {
                event        = new Event();
                event.id     = eventId;
                event.window = window;
                event.client = client;
                event.mask   = mask;
                events.put(eventId, event);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream,
                              XOutputStream outputStream) throws IOException, XRequestError {
        if (syncExtension == null)
            syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(
                        XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
