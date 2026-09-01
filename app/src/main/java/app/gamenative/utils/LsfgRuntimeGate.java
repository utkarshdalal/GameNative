package app.gamenative.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Lightweight bridge from lsfg-vk's atomic stats.txt state to the X Present
 * scheduler. The gate fails closed: GameNative keeps its normal frame limiter
 * until the native layer has published a fresh, ready LSFG swapchain context.
 */
public final class LsfgRuntimeGate {
    private static final long STATS_FRESHNESS_MS = 2_000L;
    private static final long POLL_INTERVAL_NS = 100_000_000L;

    private static volatile File statsFile;
    private static volatile long nextPollNs;
    private static volatile boolean cachedReady;

    private LsfgRuntimeGate() {}

    public static synchronized void configure(File containerRoot) {
        File next = containerRoot == null
                ? null
                : new File(containerRoot, ".config/lsfg-vk/stats.txt");
        if (next == null ? statsFile == null : next.equals(statsFile)) return;
        statsFile = next;
        cachedReady = false;
        nextPollNs = 0L;
    }

    public static boolean isGenerationReady() {
        final long nowNs = System.nanoTime();
        if (nowNs < nextPollNs) return cachedReady;

        synchronized (LsfgRuntimeGate.class) {
            final long lockedNowNs = System.nanoTime();
            if (lockedNowNs < nextPollNs) return cachedReady;
            nextPollNs = lockedNowNs + POLL_INTERVAL_NS;
            cachedReady = readReadyState(statsFile);
            return cachedReady;
        }
    }

    private static boolean readReadyState(File file) {
        if (file == null || !file.isFile()) return false;
        final long ageMs = System.currentTimeMillis() - file.lastModified();
        if (ageMs < 0L || ageMs > STATS_FRESHNESS_MS) return false;

        boolean active = false;
        boolean generationReady = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("active=1")) active = true;
                else if (line.equals("generation_ready=1")) generationReady = true;
            }
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        return active && generationReady;
    }
}
