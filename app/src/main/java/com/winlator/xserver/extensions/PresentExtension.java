package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.widget.XServerView;
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
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL_DEFAULT_US = 1_000_000 / 60;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    // FPS limiter: delays PresentIdleNotify/PresentCompleteNotify to create
    // back-pressure on the game's render loop. Without this the game ignores the
    // Android-side display throttle and renders at full speed regardless.
    private volatile int frameRateLimit = 0;
    private volatile long targetIntervalUs = 0L;
    private long lastScheduledUst = 0L; // guarded by scheduleLock
    private final Object scheduleLock = new Object();
    // Incremented on every setFrameRateLimit call so in-flight lambdas can detect
    // that the limit changed and fire immediately instead of stalling the game.
    private final AtomicInteger limitGeneration = new AtomicInteger(0);
    private final ScheduledExecutorService presentScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PresentExt-FpsLimiter");
            t.setDaemon(true);
            return t;
        });

    public void setFrameRateLimit(int limit) {
        synchronized (scheduleLock) {
            frameRateLimit = Math.max(0, limit);
            targetIntervalUs = frameRateLimit > 0 ? 1_000_000L / frameRateLimit : 0L;
            lastScheduledUst = 0L; // reset pacing watermark on every change
        }
        limitGeneration.incrementAndGet(); // invalidate any in-flight scheduled notifies
    }

    public void close() {
        presentScheduler.shutdownNow();
    }

    private long nextScheduledUst(long nowUst) {
        synchronized (scheduleLock) {
            // Use the later of (last watermark + interval) or now so that already-late
            // frames are not penalised by an extra full interval of delay.
            long next = Math.max(lastScheduledUst + targetIntervalUs, nowUst);
            lastScheduledUst = next;
            return next;
        }
    }

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 2; }

    @Override
    public int getNumErrors() { return 0; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content = window.getContent();
        if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        // Copy pixels immediately so the game's buffer is up-to-date on the XServer side.
        synchronized (content.renderLock) {
            content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
        }

        // PresentIdleNotify / PresentCompleteNotify are what actually pace the game's
        // render loop. Delaying them here creates real back-pressure: the game must wait
        // for IdleNotify before it can reuse a pixmap buffer, so it will naturally render
        // no faster than the configured limit regardless of how many swapchain images it has.
        long targetInterval = this.targetIntervalUs;
        long nowUst = System.nanoTime() / 1000;

        if (targetInterval <= 0L) {
            // No limit — fire immediately as before.
            long msc = nowUst / FAKE_INTERVAL_DEFAULT_US;
            sendIdleNotify(window, pixmap, serial, idleFence);
            sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, nowUst, msc);
        } else {
            final int capturedGen = limitGeneration.get();
            long scheduledUst = nextScheduledUst(nowUst);
            long delayUs = scheduledUst - nowUst;

            if (delayUs <= 1_000L) {
                long msc = scheduledUst / FAKE_INTERVAL_DEFAULT_US;
                sendIdleNotify(window, pixmap, serial, idleFence);
                sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, scheduledUst, msc);
            } else {
                final Window finalWindow = window;
                final Pixmap finalPixmap = pixmap;
                final int finalSerial = serial;
                final int finalIdleFence = idleFence;
                final long finalScheduledUst = scheduledUst;
                long delayNs = delayUs * 1_000L;
                presentScheduler.schedule(() -> {
                    try {
                        if (limitGeneration.get() == capturedGen) {
                            long msc = finalScheduledUst / FAKE_INTERVAL_DEFAULT_US;
                            sendIdleNotify(finalWindow, finalPixmap, finalSerial, finalIdleFence);
                            sendCompleteNotify(finalWindow, finalSerial, Kind.PIXMAP, Mode.COPY,
                                    finalScheduledUst, msc);
                        } else {
                            // Limit changed while this frame was queued — fire immediately
                            // so the game is not stalled at the old cadence.
                            long ustNow = System.nanoTime() / 1000;
                            sendIdleNotify(finalWindow, finalPixmap, finalSerial, finalIdleFence);
                            sendCompleteNotify(finalWindow, finalSerial, Kind.PIXMAP, Mode.COPY,
                                    ustNow, ustNow / FAKE_INTERVAL_DEFAULT_US);
                        }
                    } catch (Exception ignored) {
                        // Client may have disconnected before the scheduled notify fired.
                    }
                }, delayNs, TimeUnit.NANOSECONDS);
            }
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content = window.getContent();
            final Texture oldTexture = content.getTexture();
            XServerView xServerView = client.xServer.getRenderer().xServerView;
            Objects.requireNonNull(oldTexture);
            xServerView.queueEvent(() -> VortekRendererComponent.destroyTexture(oldTexture));
            content.setTexture(new GPUImage(content.width, content.height));
        }

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
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
