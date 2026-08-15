package com.winlator.xserver;

import java.util.concurrent.ConcurrentHashMap;

// FPS limiter for clients that present via full-drawable SHM blits instead of
// the Present extension. The caller suspends the client's socket reads for the
// returned delay, which blocks the game inside its own per-frame XSync; the
// blitted frame is still displayed immediately.
public class ShmFramePacer {
    private static volatile int frameRateLimit = 0;

    private static class Timing { long nextNs; }
    private static final ConcurrentHashMap<Integer, Timing> timings = new ConcurrentHashMap<>();

    public static void setFrameRateLimit(int limit) {
        frameRateLimit = Math.max(0, limit);
    }

    // Returns how long (ns) the caller should suspend this client's reads, or 0.
    public static long framePresented(int drawableId) {
        int fps = frameRateLimit;
        Timing t = timings.computeIfAbsent(drawableId, k -> new Timing());
        if (fps <= 0) {
            t.nextNs = 0;
            return 0;
        }
        long now = System.nanoTime();
        if (t.nextNs < now) t.nextNs = now;
        long delay = t.nextNs - now;
        t.nextNs += 1_000_000_000L / fps;
        return delay;
    }
}
