package com.winlator.renderer;

import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global FPS diagnostics: counts frames from every presentation path and
 * reports to logcat every 10 seconds. Use GN_FRAME_LOG = 1
 *
 * Upstream splits compositing into THREE renderers; each has a hook below:
 *  - GLRenderer          (OpenGL / VirGL passthrough) → recordGLDrawFrame()
 *  - VulkanRenderer      (Vulkan compositor + zero-copy scanout) → recordVkUpdate()/recordVkContent*()
 *  - ASurfaceRenderer    (SurfaceFlinger direct scanout) → recordSurfaceFlingerPresent()
 *
 * Cross-cutting frame producers / pacers also reported:
 *  - X Present protocol  (PresentExtension.presentPixmap) — back-pressure paced; recordXPresent()
 *  - Vortek native       (VortekRendererComponent) — native Vulkan source; recordVortekPresent()
 *
 * NOTE FOR MAINTAINERS: every call to a record/update method below is the
 * ONLY footprint this logger has in the renderer code. Search "[FramePacingLogger]"
 * to find every anchor. Removing an anchor just drops that path from the report.
 */
public final class FramePacingLogger {
    private static final String TAG = "FramePacing";
    private static final long REPORT_INTERVAL_MS = 10_000L; // 10 seconds
    // Hold off the very first report so it doesn't spam logcat while the game is
    // still opening (heavy I/O). First report lands ~INITIAL_DELAY + INTERVAL after launch.
    private static final long INITIAL_DELAY_MS = 10_000L; // 10 seconds

    // ── Per-path frame counters ──────────────────────────────────────────────
    private static final AtomicInteger cntXPresent        = new AtomicInteger(0);
    private static final AtomicInteger cntXPresentScanout = new AtomicInteger(0);
    private static final AtomicInteger cntXPresentCopy    = new AtomicInteger(0);
    private static final AtomicInteger cntVkAHB           = new AtomicInteger(0);
    private static final AtomicInteger cntVkScanout       = new AtomicInteger(0);
    private static final AtomicInteger cntVkPixels        = new AtomicInteger(0);
    private static final AtomicInteger cntGLDraw          = new AtomicInteger(0);
    private static final AtomicInteger cntVkSkipped       = new AtomicInteger(0);
    private static final AtomicInteger cntGLSkipped       = new AtomicInteger(0);
    private static final AtomicInteger cntVortek          = new AtomicInteger(0);
    private static final AtomicInteger cntVkUpdate        = new AtomicInteger(0);
    // SurfaceFlinger (ASurfaceRenderer) — the 3rd renderer; SurfaceControl scanout submissions.
    private static final AtomicInteger cntSfGpu           = new AtomicInteger(0);
    private static final AtomicInteger cntSfCpu           = new AtomicInteger(0);

    // ── Limit state (updated by each subsystem) ──────────────────────────────
    private static volatile int  presentExtLimit   = 0;   // 0 = unlimited
    private static volatile int  vkScanoutHint     = 0;   // SurfaceControl rate hint; 0 = cleared
    private static volatile boolean limiterEnabled = false;
    private static volatile int  limiterTarget     = 0;

    // ── Launch-time env-var state (set when the Wine process is spawned) ─────
    private static volatile String launchDxvkFrameRate   = "NOT SET";
    private static volatile String launchVkd3dFrameLimit = "NOT SET";
    private static volatile String launchMangoHudConfig  = "NOT SET";

    // ── Session/device identification (set at launch) ───────────────────────
    private static volatile String sessionGameName    = "UNKNOWN";
    private static volatile String sessionGameExe     = "UNKNOWN";
    private static volatile String sessionDevice      = "UNKNOWN";
    private static volatile String sessionGpu         = "UNKNOWN";
    private static volatile String sessionDxWrapper   = "UNKNOWN";
    private static volatile String sessionDxvkVersion = "NOT SET";
    private static volatile String sessionVkd3dVersion = "NOT SET";
    private static volatile String sessionDriver      = "UNKNOWN";
    private static volatile String sessionDriverVersion = "UNKNOWN";

    // Display panel. We hold the system Display so each report can read the LIVE
    // refresh rate — Display.getRefreshRate() is a cached field read (not a probe),
    // so it is effectively free. maxRefreshHz is computed once at launch.
    private static volatile android.view.Display sessionDisplay = null;
    private static volatile float displayMaxRefreshHz = 0f;

    // Authoritative on-screen FPS as measured by FrameRating (the value the
    // Performance HUD shows and which matches the device's own fps counter). This
    // is the real present rate of the topmost window, not a renderer submission count.
    private static volatile float measuredFps    = 0f;
    private static volatile float measuredAvgFps = 0f;

    // ── Background reporter ──────────────────────────────────────────────────
    private static volatile Thread sReportThread = null;
    private static volatile boolean reporterStarted = false;

    // ── Enable flag — off by default; set via GN_FRAME_LOG=1 env var ────────
    private static volatile boolean enabled = false;

    private FramePacingLogger() {}

    /**
     * Enable or disable the frame pacing logger.
     * When disabled the reporter thread is stopped and all record/log calls
     * become no-ops. Called from XServerScreen when GN_FRAME_LOG=1 is set.
     */
    public static synchronized void setEnabled(boolean enable) {
        enabled = enable;
        if (!enable) {
            // Disabling (e.g. a new game launched without GN_FRAME_LOG) must stop the
            // reporter AND wipe the previous game's session so nothing stale lingers.
            stopReporter();
            resetSession();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // Self-starting: any record* call spins up the 10 s reporter if not already
    // running, so we get data even without an explicit notifyLaunch() hook.
    private static void maybeStartReporter() {
        if (!enabled) return;
        if (reporterStarted) return;
        ensureReporterRunning();
    }

    /** Call once when the game process is about to be launched. */
    public static synchronized void notifyLaunch(
            String dxvkFrameRate, String vkd3dFrameLimit, String mangoHudConfig) {
        if (!enabled) return;
        // Fresh launch: clear any leftover session/counters from a previous game that
        // ran in this same process, then record this game's launch-time env vars.
        resetSession();
        launchDxvkFrameRate   = dxvkFrameRate  != null && !dxvkFrameRate.isEmpty()
                                ? dxvkFrameRate   : "NOT SET";
        launchVkd3dFrameLimit = vkd3dFrameLimit != null && !vkd3dFrameLimit.isEmpty()
                                ? vkd3dFrameLimit : "NOT SET";
        launchMangoHudConfig  = mangoHudConfig  != null && !mangoHudConfig.isEmpty()
                                ? mangoHudConfig  : "NOT SET";
        ensureReporterRunning();
        Log.i(TAG, "FramePacingLogger: game launching — DXVK_FRAME_RATE=" + launchDxvkFrameRate
                + "  VKD3D_FRAME_LIMIT=" + launchVkd3dFrameLimit
                + "  MANGOHUD_CONFIG=" + launchMangoHudConfig);
    }

    /**
     * Record the session / device identification for the current run.
     * Logged on launch and repeated at the top of every periodic report so the
     * captured logcat is self-describing.
     *
     * @param gameName     human-readable game / container name
     * @param gameExe      launch executable name (may differ from the game name)
     * @param device       device manufacturer + model
     * @param gpu          GPU renderer string
     * @param dxWrapper    active DX wrapper type (dxvk / vkd3d / wined3d …)
     * @param dxvkVersion  active DXVK version (empty when the wrapper is not DXVK)
     * @param vkd3dVersion active VKD3D version (empty when the wrapper is not VKD3D)
     * @param driver       graphics driver / wrapper layer name
     * @param driverVersion concrete driver version (e.g. the adrenotools driver build)
     */
    public static synchronized void notifyDeviceInfo(
            String gameName, String gameExe, String device, String gpu,
            String dxWrapper, String dxvkVersion, String vkd3dVersion,
            String driver, String driverVersion) {
        if (!enabled) return;
        sessionGameName    = orDefault(gameName,    "UNKNOWN");
        sessionGameExe     = orDefault(gameExe,     "UNKNOWN");
        sessionDevice      = orDefault(device,      "UNKNOWN");
        sessionGpu         = orDefault(gpu,         "UNKNOWN");
        sessionDxWrapper   = orDefault(dxWrapper,   "UNKNOWN");
        sessionDxvkVersion = orDefault(dxvkVersion, "not used");
        sessionVkd3dVersion = orDefault(vkd3dVersion, "not used");
        sessionDriver      = orDefault(driver,      "UNKNOWN");
        sessionDriverVersion = orDefault(driverVersion, "UNKNOWN");
        Log.i(TAG, "Game: " + sessionGameName + "  |  Exe: " + sessionGameExe
                + "  |  Device: " + sessionDevice + "  |  GPU: " + sessionGpu);
        Log.i(TAG, "Wrapper: " + sessionDxWrapper + "  |  DXVK: " + sessionDxvkVersion
                + "  |  VKD3D: " + sessionVkd3dVersion + "  |  Driver: " + sessionDriver
                + "  |  Driver ver: " + sessionDriverVersion);
    }

    private static String orDefault(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    /**
     * Record the display panel for the current session so the report can show the
     * live vs. max refresh rate (useful for judder diagnosis: a present rate that
     * doesn't evenly divide the panel Hz produces uneven frame pacing).
     * Held as a reference so each report reads getRefreshRate() live (free read).
     */
    public static synchronized void notifyDisplayInfo(android.view.Display display) {
        if (!enabled) return;
        sessionDisplay = display;
        float maxHz = 0f;
        if (display != null) {
            try {
                for (android.view.Display.Mode m : display.getSupportedModes()) {
                    if (m.getRefreshRate() > maxHz) maxHz = m.getRefreshRate();
                }
                if (maxHz <= 0f) maxHz = display.getRefreshRate();
            } catch (Throwable ignored) { /* keep 0 = unknown */ }
        }
        displayMaxRefreshHz = maxHz;
    }

    /**
     * Called by FrameRating each reading with the authoritative on-screen FPS the
     * Performance HUD displays (smoothed present rate of the topmost app window).
     * This is the number the user sees in the HUD / device counter — show it so the
     * report can be cross-checked against the renderer submission counts.
     */
    public static void updateMeasuredFps(float currentFps, float avgFps) {
        measuredFps    = currentFps;
        measuredAvgFps = avgFps;
    }

    // ── Frame recording API ──────────────────────────────────────────────────

    /**
     * Called by PresentExtension for each PRESENT_PIXMAP request handled.
     * @param isScanout true when routed to the zero-copy scanout path
     * @param isCopy    true when content is blitted into the compositor
     */
    public static void recordXPresent(boolean isScanout, boolean isCopy) {
        maybeStartReporter();
        cntXPresent.incrementAndGet();
        if (isScanout) cntXPresentScanout.incrementAndGet();
        else if (isCopy) cntXPresentCopy.incrementAndGet();
    }

    /** Called by VortekRendererComponent each time a Vortek-rendered frame is
     *  pushed to the window (native Vulkan present that bypasses X Present). */
    public static void recordVortekPresent() {
        maybeStartReporter();
        cntVortek.incrementAndGet();
    }

    /**
     * Called by ASurfaceRenderer for each SurfaceControl buffer submission —
     * this is the 3rd renderer (SurfaceFlinger direct scanout).
     * @param isGpu true = GPU/AHB image path; false = CPU/AHB fallback path
     */
    public static void recordSurfaceFlingerPresent(boolean isGpu) {
        maybeStartReporter();
        if (isGpu) cntSfGpu.incrementAndGet();
        else       cntSfCpu.incrementAndGet();
    }

    /**
     * Called by VulkanRenderer.onUpdateWindowContent — the COMMON compositor sink
     * for every source (X Present, Vortek forceUpdate, etc.). Comparing this rate
     * against recordXPresent tells us whether frames originate from X Present.
     */
    public static void recordVkUpdate() {
        maybeStartReporter();
        cntVkUpdate.incrementAndGet();
    }

    /**
     * Called by VulkanRenderer for each direct AHB content update.
     * @param isScanout true if the buffer is routed to the zero-copy scanout surface
     */
    public static void recordVkContentAHB(boolean isScanout) {
        maybeStartReporter();
        if (isScanout) cntVkScanout.incrementAndGet();
        else           cntVkAHB.incrementAndGet();
    }

    /** Called by VulkanRenderer for each pixel/software-blit content update. */
    public static void recordVkContentPixels() {
        maybeStartReporter();
        cntVkPixels.incrementAndGet();
    }

    /** Called by VulkanRenderer when a content update is skipped by the runtime FPS limiter. */
    public static void recordVkContentSkipped() {
        cntVkSkipped.incrementAndGet();
    }

    /** Called by GLRenderer.onDrawFrame() on every EGL swap. */
    public static void recordGLDrawFrame() {
        maybeStartReporter();
        cntGLDraw.incrementAndGet();
    }

    /** Called by GLRenderer when a render request is skipped by the runtime FPS limiter. */
    public static void recordGLDrawSkipped() {
        cntGLSkipped.incrementAndGet();
    }

    // ── Limit-state update API ───────────────────────────────────────────────

    /** Updated by PresentExtension whenever setFrameRateLimit is called. */
    public static void updatePresentExtLimit(int limit) {
        presentExtLimit = limit;
        if (!enabled) return;
        Log.d(TAG, "PresentExtension frame limit → " + (limit > 0 ? limit + " fps" : "UNLIMITED"));
    }

    /**
     * Updated by VulkanRenderer whenever a SurfaceControl rate hint is applied.
     * @param hint 0 = hint cleared (no pinning), >0 = hint fps value
     */
    public static void updateVkScanoutHint(int hint) {
        vkScanoutHint = hint;
        if (!enabled) return;
        Log.d(TAG, "VulkanRenderer SurfaceControl hint → "
                + (hint > 0 ? hint + " fps" : "cleared (no pinning)"));
    }

    /**
     * Updated by XServerScreen whenever the quick-menu fps limiter state changes.
     */
    public static void updateFpsLimiterState(boolean enabled2, int target) {
        limiterEnabled = enabled2;
        limiterTarget  = target;
        if (!enabled) return;
        Log.d(TAG, "QuickMenu FPS limiter → " + (enabled2 ? "ON @ " + target + " fps" : "OFF (unlimited)"));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static void resetCounters() {
        cntXPresent.set(0); cntXPresentScanout.set(0); cntXPresentCopy.set(0);
        cntVkAHB.set(0);    cntVkScanout.set(0);        cntVkPixels.set(0);
        cntGLDraw.set(0);
        cntVkSkipped.set(0); cntGLSkipped.set(0);
        cntVortek.set(0);    cntVkUpdate.set(0);
        cntSfGpu.set(0);     cntSfCpu.set(0);
    }

    // Wipe ALL per-game state so a new launch (or a disable) never carries the
    // previous game's identity, limits or counters over within the same process.
    private static void resetSession() {
        sessionGameName    = "UNKNOWN";
        sessionGameExe     = "UNKNOWN";
        sessionDevice      = "UNKNOWN";
        sessionGpu         = "UNKNOWN";
        sessionDxWrapper   = "UNKNOWN";
        sessionDxvkVersion = "NOT SET";
        sessionVkd3dVersion = "NOT SET";
        sessionDriver      = "UNKNOWN";
        sessionDriverVersion = "UNKNOWN";
        sessionDisplay      = null;
        displayMaxRefreshHz = 0f;
        measuredFps         = 0f;
        measuredAvgFps      = 0f;
        launchDxvkFrameRate   = "NOT SET";
        launchVkd3dFrameLimit = "NOT SET";
        launchMangoHudConfig  = "NOT SET";
        presentExtLimit = 0;
        vkScanoutHint   = 0;
        limiterEnabled  = false;
        limiterTarget   = 0;
        resetCounters();
    }

    private static synchronized void stopReporter() {
        if (sReportThread != null) {
            sReportThread.interrupt();
            sReportThread = null;
        }
        reporterStarted = false;
    }

    private static synchronized void ensureReporterRunning() {
        if (sReportThread != null && sReportThread.isAlive()) return;
        reporterStarted = true;
        sReportThread = new Thread(() -> {
            // Quiet startup window: wait out the busy loading phase, then reset so the
            // first reported 10 s window is an accurate sample (not the startup burst).
            try { Thread.sleep(INITIAL_DELAY_MS); }
            catch (InterruptedException e) { return; }
            resetCounters();
            while (!Thread.interrupted()) {
                try { Thread.sleep(REPORT_INTERVAL_MS); }
                catch (InterruptedException e) { break; }
                report();
            }
        }, "FramePacingLogger");
        sReportThread.setDaemon(true);
        sReportThread.setPriority(Thread.MIN_PRIORITY);
        sReportThread.start();
    }

    private static void report() {
        if (!enabled) return;
        // Snap and reset counters atomically-per-counter
        int xPresent   = cntXPresent.getAndSet(0);
        int xScanout   = cntXPresentScanout.getAndSet(0);
        int xCopy      = cntXPresentCopy.getAndSet(0);
        int vkAHB      = cntVkAHB.getAndSet(0);
        int vkScanout  = cntVkScanout.getAndSet(0);
        int vkPixels   = cntVkPixels.getAndSet(0);
        int glDraw     = cntGLDraw.getAndSet(0);
        int vkSkipped  = cntVkSkipped.getAndSet(0);
        int glSkipped  = cntGLSkipped.getAndSet(0);
        int vortek     = cntVortek.getAndSet(0);
        int vkUpdate   = cntVkUpdate.getAndSet(0);
        int sfGpu      = cntSfGpu.getAndSet(0);
        int sfCpu      = cntSfCpu.getAndSet(0);

        float sec = REPORT_INTERVAL_MS / 1000f;

        // Live panel refresh (cached field read — effectively free).
        float curHz = 0f;
        android.view.Display d = sessionDisplay;
        if (d != null) { try { curHz = d.getRefreshRate(); } catch (Throwable ignored) {} }

        Log.i(TAG, "══════════ FRAME PACING REPORT (last 10 s) ══════════");
        Log.i(TAG, "  Game: " + sessionGameName + "  |  Exe: " + sessionGameExe
                + "  |  Device: " + sessionDevice + "  |  GPU: " + sessionGpu);
        Log.i(TAG, "  Wrapper: " + sessionDxWrapper + "  |  DXVK: " + sessionDxvkVersion
                + "  |  VKD3D: " + sessionVkd3dVersion + "  |  Driver: " + sessionDriver
                + "  |  Driver ver: " + sessionDriverVersion);
        Log.i(TAG, String.format(
                "  [X Present protocol]  %.1f fps  | total=%d  scanout=%d  copy=%d",
                xPresent / sec, xPresent, xScanout, xCopy));
        Log.i(TAG, String.format(
                "    └─ back-pressure paced by PresentExtension (idle-notify delay = %s)",
                presentExtLimit > 0 ? presentExtLimit + " fps target" : "UNLIMITED – no delay"));
        Log.i(TAG, String.format(
                "  [Vortek native]       %.1f fps  | %d frames  (native Vulkan present, bypasses X Present)",
                vortek / sec, vortek));
        Log.i(TAG, String.format(
                "  [Vk compositor sink]  %.1f fps  | %d onUpdateWindowContent calls (ALL sources)",
                vkUpdate / sec, vkUpdate));
        Log.i(TAG, String.format(
                "  [VkRenderer AHB]      %.1f fps  | %d frames  (non-scanout compositor path)",
                vkAHB / sec, vkAHB));
        Log.i(TAG, String.format(
                "  [VkRenderer Scanout]  %.1f fps  | %d frames  (zero-copy SurfaceControl path)",
                vkScanout / sec, vkScanout));
        Log.i(TAG, String.format(
            "  [VkRenderer Pixels]   %.1f fps  | %d frames  (software pixel blit)",
            vkPixels / sec, vkPixels));
        Log.i(TAG, String.format(
            "  [SurfaceFlinger ASR]  %.1f fps  | gpu=%d cpu=%d  (SurfaceControl direct scanout)",
            (sfGpu + sfCpu) / sec, sfGpu, sfCpu));
        Log.i(TAG, String.format(
            "  [GL Renderer draws]   %.1f fps  | %d frames  (EGL onDrawFrame)",
            glDraw / sec, glDraw));
        Log.i(TAG, String.format(
                "  [HUD measured FPS]    %.1f fps now  | %.1f fps avg  (FrameRating: on-screen present of topmost window — matches HUD)",
                measuredFps, measuredAvgFps));
        Log.i(TAG, String.format(
                "  [Limiter skipped]     Vk=%d  GL=%d  (updates intentionally not submitted)",
                vkSkipped, glSkipped));
        Log.i(TAG, "  ─────────────── Source diagnosis ───────────────");
        // If the compositor sink is busy but X Present is idle, frames are NOT
        // coming through PresentExtension → X-Present back-pressure cannot cap them.
        String source;
        if (xPresent > 5 && xPresent >= vortek) source = "X Present (PresentExtension CAN cap)";
        else if (vortek > 5)                    source = "Vortek native (PresentExtension CANNOT cap)";
        else if ((sfGpu + sfCpu) > 5)           source = "SurfaceFlinger ASR (X Present CAN cap via idle-notify)";
        else if (vkUpdate > 5)                  source = "compositor-only (X Present NOT the source)";
        else                                    source = "idle / no frames";
        Log.i(TAG, "  Frame source        : " + source);
        // Judder check: prefer the HUD-measured on-screen FPS (FrameRating — matches the
        // device counter); fall back to the dominant renderer submission count. If the
        // panel Hz isn't a near-integer multiple of that rate, frames are held an uneven
        // number of vsyncs → visible stutter even at a "stable" average fps.
        float submitFps = Math.max(Math.max(xPresent, vortek), Math.max(sfGpu + sfCpu,
                Math.max(vkUpdate, glDraw))) / sec;
        boolean usingHud = measuredFps > 0.5f;
        float presentFps = usingHud ? measuredFps : submitFps;
        if (curHz > 0f && presentFps > 1f) {
            float ratio = curHz / presentFps;          // vsyncs per presented frame
            float frac  = Math.abs(ratio - Math.round(ratio));
            String verdict = frac < 0.08f
                    ? "even cadence (clean multiple of refresh)"
                    : "UNEVEN cadence → judder likely (present rate not a clean divisor of panel Hz)";
            Log.i(TAG, String.format(
                    "  Pacing vs display   : %.1f fps %s @ %.1f Hz → %.2f vsync/frame — %s",
                    presentFps, usingHud ? "on-screen (HUD)" : "submitted", curHz, ratio, verdict));
            if (usingHud && Math.abs(submitFps - measuredFps) > 2f) {
                Log.i(TAG, String.format(
                        "    └─ note: renderer submitted %.1f fps but only %.1f fps reached the screen (HUD)",
                        submitFps, measuredFps));
            }
        }
        Log.i(TAG, "  ───────────────────────── Active Limiters ─────────────────────────");
        Log.i(TAG, String.format(
                "  QuickMenu limiter   : %s  target=%d fps  |  Display set refresh: %s now  |  %s max",
                limiterEnabled ? "ON" : "OFF", limiterTarget,
                curHz > 0f ? String.format("%.1f Hz", curHz) : "unknown",
                displayMaxRefreshHz > 0f ? String.format("%.1f Hz", displayMaxRefreshHz) : "unknown"));
        Log.i(TAG, String.format(
                "  PresentExt limit    : %s  [X Present back-pressure; 0=unlimited=fires immediately]",
                presentExtLimit > 0 ? presentExtLimit + " fps" : "UNLIMITED (0)"));
        Log.i(TAG, String.format(
                "  VkSurfaceCtrl hint  : %s  [SurfaceControl setFrameRate – display HINT only, not a hard cap]",
                vkScanoutHint > 0 ? vkScanoutHint + " fps" : "none / cleared"));
        Log.i(TAG, "  ───────────────────────── Launch-time Env Vars ────────────────────");
        Log.i(TAG, "  DXVK_FRAME_RATE     : " + launchDxvkFrameRate
                + "  [DXVK internal limiter – set at Wine process launch, cannot change at runtime]");
        Log.i(TAG, "  VKD3D_FRAME_LIMIT   : " + launchVkd3dFrameLimit
                + "  [VKD3D-Proton DX12 limiter – set at Wine process launch, cannot change at runtime]");
        Log.i(TAG, "  MANGOHUD_CONFIG     : " + launchMangoHudConfig
                + "  [MangoHUD – check fps_limit inside config if set]");
        Log.i(TAG, "  ───────────────────────── Path Notes ──────────────────────────────");
        Log.i(TAG, "  DXVK/VKD3D games route D3D Present() → winevulkan → winex11 → X Present → PresentExtension");
        Log.i(TAG, "  VKD3D (D3D12) frames land in [Vk compositor sink] / [VkRenderer AHB] (true for the GL and Vulkan renderers)");
        Log.i(TAG, "  GL / virgl games  route via GLX/EGL → X Present → PresentExtension");
        Log.i(TAG, "  Native Vulkan scanout path bypasses X Present; only SurfaceControl hint applies");
        Log.i(TAG, "  GLRenderer has NO independent frame cap; relies solely on PresentExtension pacing");
        Log.i(TAG, "  If PresentExtension limit=0 AND no DXVK/VKD3D env-var cap → game runs UNCAPPED");
        Log.i(TAG, "══════════════════════════════════════════════════════════════════════");
    }
}

