package com.winlator.widget;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.xenvironment.ImageFs;

import app.gamenative.R;
import app.gamenative.powercontrol.metrics.FrameTimeRing;
import timber.log.Timber;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Counts the frames the game produced, on either display server.
 *
 * <ul>
 *   <li>X11: one tick per content update of the tracked application window, i.e. per
 *   present the game sends to the app's X server (XServerScreen's onUpdateWindowContent).</li>
 *   <li>gamescope: one tick per {@link app.gamenative.display.GameNativeDrm#onFramePublished}
 *   upcall. The daemon raises it once per frame the ICD shim reports as produced (acquired, plus
 *   generated under frame generation), and once per frame it published to the panel only while
 *   no shim report has arrived for a second.</li>
 * </ul>
 *
 * Neither renderer reports the frames that reached the panel, so the "displayed" figure kept
 * here is an estimate: each one-second reading clamped to the panel's refresh rate
 * ({@link #setPanelRefreshHz}). A frame the panel dropped below that rate is not seen.
 */
public class FrameRating extends FrameLayout implements Runnable {
    /** The panel's refresh rate in Hz, 0 while unknown; set from the session's display. */
    private static volatile float panelRefreshHz = 0f;

    /** Record the panel's refresh rate, the ceiling of the displayed-fps estimate. */
    public static void setPanelRefreshHz(float hz) {
        panelRefreshHz = (hz > 0f && !Float.isInfinite(hz)) ? hz : 0f;
    }

    /** {@code fps} clamped to the panel's refresh rate: what the panel could have shown. */
    public static float displayedFps(float fps) {
        float hz = panelRefreshHz;
        return hz > 0f ? Math.min(fps, hz) : fps;
    }

    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private final TextView textView;

    // FPS reading tracking
    private static final int READING_INTERVAL_MS = 1000; // Take reading every 1 second
    private int readingCount = 0;
    private long sessionStartTime = 0;
    private int maxFPS = 0;
    private int minFPS = Integer.MAX_VALUE;
    private long lastReadingTime = 0;
    private long fpsSum = 0; // Sum of all FPS readings for average calculation
    private double displayedFpsSum = 0; // Same readings clamped to the panel's rate
    private long lastFrameTime = 0;
    private long totalFrames = 0;
    private long activeMs = 0;
    public static final int FRAME_HIST_CAP_MS = 200;
    private static final int FPS_BUCKET_MS = 5 * 60 * 1000;
    private static final int MAX_FPS_BUCKETS = 48;
    private final long[] bucketFpsSum = new long[MAX_FPS_BUCKETS];
    private final int[] bucketReadings = new int[MAX_FPS_BUCKETS];
    private final int[] frameHistMs = new int[FRAME_HIST_CAP_MS + 1];

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        textView = view.findViewById(R.id.TVFPS);
        addView(view);
    }

    public void update() {
        update(SystemClock.elapsedRealtime());
    }

    public void update(long time) {
        FrameTimeRing.record();
        if (lastTime == 0) lastTime = time;
        if (sessionStartTime == 0) sessionStartTime = time;
        try {
            if (lastFrameTime != 0 && time >= lastFrameTime) {
                int delta = (int) Math.min(time - lastFrameTime, FRAME_HIST_CAP_MS);
                frameHistMs[delta]++;
            }
            if (lastFrameTime != 0 && time > lastFrameTime && time - lastFrameTime < 1000) {
                activeMs += time - lastFrameTime;
            }
            if (time > lastFrameTime) lastFrameTime = time;
            totalFrames++;
        } catch (RuntimeException ignored) {
        }
        if (time >= lastTime + 500) {
            lastFPS = ((float)(frameCount * 1000) / (time - lastTime));

            // Take reading at specified interval
            if (lastReadingTime == 0 || time >= lastReadingTime + READING_INTERVAL_MS) {
                int currentFPS = Math.round(lastFPS);
                readingCount++;
                fpsSum += currentFPS;
                displayedFpsSum += displayedFps(currentFPS);
                int bucket = (int) ((time - sessionStartTime) / FPS_BUCKET_MS);
                if (bucket >= 0 && bucket < MAX_FPS_BUCKETS) {
                    bucketFpsSum[bucket] += currentFPS;
                    bucketReadings[bucket]++;
                }

                // Track max and min FPS (min must be > 1)
                if (currentFPS > maxFPS) {
                    maxFPS = currentFPS;
                }
                if (currentFPS > 1 && currentFPS < minFPS) {
                    minFPS = currentFPS;
                }

                lastReadingTime = time;
            }

            post(this);
            lastTime = time;
            frameCount = 0;
        }

        frameCount++;
    }

    public void reset() {
        lastTime = 0;
        frameCount = 0;
        lastFPS = 0;
        lastFrameTime = 0;
        post(() -> textView.setText(String.format(Locale.ENGLISH, "%.1f", 0f)));
    }

    /** Returns the most recent measured FPS value for the active session. */
    public float getCurrentFPS() {
        return lastFPS;
    }

    /** Average of the one-second game-fps readings. */
    public float getAvgFPS() {
        if (readingCount == 0) return 0;
        return (float) fpsSum / readingCount;
    }

    /** Average of the same readings clamped to the panel's rate (an estimate, see the class note). */
    public float getAvgDisplayedFPS() {
        if (readingCount == 0) return 0;
        return (float) (displayedFpsSum / readingCount);
    }

    public long getTotalFrames() {
        return totalFrames;
    }

    public long getActiveMs() {
        return activeMs;
    }

    public java.util.List<Integer> getFpsBy5Min() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < MAX_FPS_BUCKETS && bucketReadings[i] > 0; i++) {
            out.add((int) (bucketFpsSum[i] / bucketReadings[i]));
        }
        return out;
    }

    public int getFramePercentileMs(double percentile) {
        long counted = 0;
        long target = (long) Math.ceil(percentile * (totalFrames - 1));
        for (int ms = 0; ms < frameHistMs.length; ms++) {
            counted += frameHistMs[ms];
            if (counted > target) return ms;
        }
        return FRAME_HIST_CAP_MS;
    }

    public float getSessionLengthSec() {
        if (sessionStartTime == 0) return 0;
        return (SystemClock.elapsedRealtime() - sessionStartTime) / 1000.0f;
    }

    public void writeSessionSummary() {
        if (readingCount == 0) return;

        final long sessionLengthMs = sessionStartTime > 0 ?
            SystemClock.elapsedRealtime() - sessionStartTime : 0;
        final float sessionLengthSec = sessionLengthMs / 1000.0f;
        final int max = maxFPS;
        final int min = minFPS == Integer.MAX_VALUE ? 0 : minFPS;
        final float avgFPS = (float) fpsSum / readingCount;

        Context context = getContext();
        ImageFs imageFs = ImageFs.find(context);

        // Generate unique filename with timestamp
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH);
        String timestamp = dateFormat.format(new Date());
        File fpsLogFile = new File(imageFs.getTmpDir(), "fps_session" + ".json");
        ExecutorService fileWriteExecutor = Executors.newSingleThreadExecutor();

        fileWriteExecutor.execute(() -> {
            try {
                // Create file if it doesn't exist, or overwrite if it does
                if (!fpsLogFile.exists()) {
                    fpsLogFile.createNewFile();
                }

                // Write JSON format for easy parsing
                String json = String.format(Locale.ENGLISH,
                    "{\n" +
                    "  \"length_sec\": %.2f,\n" +
                    "  \"avg_fps\": %.1f,\n" +
                    "  \"max_fps\": %d,\n" +
                    "  \"min_fps\": %d,\n" +
                    "  \"readings\": %d\n" +
                    "}\n",
                    sessionLengthSec, avgFPS, max, min, readingCount);
                try (FileWriter fw = new FileWriter(fpsLogFile, false)) {
                    fw.write(json);
                    fw.flush();
                }
                Timber.d("Session summary written to: %s", fpsLogFile.getAbsolutePath());
            } catch (IOException e) {
                Timber.e(e, "Failed to write session summary");
            } finally {
                fileWriteExecutor.shutdown();
            }
        });
    }

    @Override
    public void run() {
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        textView.setText(String.format(Locale.ENGLISH, "%.1f", lastFPS));
    }
}
