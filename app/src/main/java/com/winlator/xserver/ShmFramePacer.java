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

    private static final int MAX_TRACKED_DRAWABLES = 128;

    public static void setFrameRateLimit(int limit) {
        frameRateLimit = Math.max(0, limit);
        if (frameRateLimit == 0) timings.clear();
    }

    // Returns how long (ns) the caller should suspend this client's reads, or 0.
    public static long framePresented(int drawableId) {
        int fps = frameRateLimit;
        if (fps <= 0) return 0;
        if (timings.size() > MAX_TRACKED_DRAWABLES) evictStalest(drawableId);
        Timing t = timings.computeIfAbsent(drawableId, k -> new Timing());
        long now = System.nanoTime();
        if (t.nextNs < now) t.nextNs = now;
        long delay = t.nextNs - now;
        t.nextNs += 1_000_000_000L / fps;
        return delay;
    }

    // Evicts the longest-idle entry (smallest nextNs), never the presenting
    // drawable, so an overflow frees one slot without resetting active pacing.
    private static void evictStalest(int presentingDrawableId) {
        Integer evict = null;
        long oldest = Long.MAX_VALUE;
        for (java.util.Map.Entry<Integer, Timing> e : timings.entrySet()) {
            if (e.getKey() == presentingDrawableId) continue;
            long nextNs = e.getValue().nextNs;
            if (nextNs < oldest) {
                oldest = nextNs;
                evict = e.getKey();
            }
        }
        if (evict != null) timings.remove(evict);
    }
}
