package com.winlator.xenvironment.components;

import android.content.Context;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * PulseAudio component with timer-based suspend strategy for efficient pause/resume management.
 *
 * Suspend Behavior Modes:
 *
 * 1. suspend-via-thread (default):
 *    Suspend: cancel timers -> set isPaused=true + updateSink(true) -> suspendProcess(SIGSTOP)
 *    Resume: cancel timers -> set isPaused=false -> resumeProcess(SIGCONT) -> updateSink(false)
 *    - Fast and lightweight, uses ProcessHelper.suspendProcess/resumeProcess
 *    - No delays, all operations execute immediately
 *
 * 2. suspend-via-pactl (power-saving):
 *    Suspend: cancel timers -> set isPaused=true + updateSink(true) -> suspend timer (120s/10s debug) -> pactl unload module
 *    Resume: cancel timers -> set isPaused=false -> check sink alive -> pactl load module OR updateSink(false)
 *    - Quick resume (< timeout): Cancels timer and resumes sink immediately (no module reload)
 *    - Long pause (≥ timeout): Module unloaded to save CPU
 *    - Resume after unload: Automatically detects missing sink and reloads module
 *    - No delay on resume for instant audio restoration
 */
public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private final String SINK_NAME = "AAudioSink";

    /** Name of the microphone source exposed to Wine/Proton. */
    public static final String MIC_SOURCE_NAME = "GameNativeMic";
    /** Stock PulseAudio module used to publish the Android microphone as a capture device. */
    private static final String PIPE_SOURCE_MODULE = "module-pipe-source.so";
    private static final String MIC_FIFO_NAME = "mic.fifo";

    private float volume = 1.0f;
    private byte performanceMode = 1;
    private final AtomicBoolean isPauseResumeRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private boolean lowLatency = false;
    private final boolean micEnabled;
    /** When true the AAudio sink is not loaded: the server only provides microphone capture. */
    private final boolean micOnly;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    public PulseAudioComponent(UnixSocketConfig socketConfig, boolean lowLatency) {
        this(socketConfig, lowLatency, false, false);
    }

    public PulseAudioComponent(UnixSocketConfig socketConfig, boolean lowLatency, boolean micEnabled) {
        this(socketConfig, lowLatency, micEnabled, false);
    }

    public PulseAudioComponent(UnixSocketConfig socketConfig, boolean lowLatency, boolean micEnabled, boolean micOnly) {
        this.socketConfig = socketConfig;
        this.lowLatency = lowLatency;
        this.micEnabled = micEnabled;
        this.micOnly = micOnly;
    }

    /** Working directory the bundled PulseAudio server runs from. */
    public static File getWorkingDir(Context context) {
        return new File(context.getFilesDir(), "/pulseaudio");
    }

    /** Path of the FIFO that {@code module-pipe-source} reads the microphone PCM from. */
    public static File getMicFifoFile(Context context) {
        return new File(getWorkingDir(context), MIC_FIFO_NAME);
    }

    /**
     * Microphone support needs PulseAudio's stock {@code module-pipe-source}, which is shipped inside
     * the bundled pulseaudio asset. Older assets do not contain it, so check before referencing it -
     * a missing module would make the whole server fail to start and kill audio output entirely.
     */
    public static boolean isMicModuleAvailable(Context context) {
        return new File(getWorkingDir(context), "modules/" + PIPE_SOURCE_MODULE).isFile();
    }

    private void killAllPulseAudioProcesses() {
        List<ProcessHelper.ProcessInfo> allProcesses = ProcessHelper.listSubProcesses();
        List<Integer> pulsePids = new ArrayList<>();

        for (ProcessHelper.ProcessInfo info : allProcesses) {
            if (info.name.contains("libpulseaudio.so")) {
                pulsePids.add(info.pid);
            }
        }

        if (!pulsePids.isEmpty()) {
            Timber.tag("PulseAudioComponent").w("Found %d pulseaudio process(es), killing: %s",
                pulsePids.size(), pulsePids.toString());

            for (int pid : pulsePids) {
                ProcessHelper.killProcess(pid);
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void start() {
        singleThreadExecutor.execute(() -> {
            Timber.tag("PulseAudioComponent").d("Starting...");
            killAllPulseAudioProcesses();
            startPulseAudio();
            isPaused.set(false);
        });
    }

    @Override
    public void stop() {
        singleThreadExecutor.execute(() -> {
            Timber.tag("PulseAudioComponent").d("Stopping...");
            killAllPulseAudioProcesses();
            isPaused.set(false);
            Timber.tag("PulseAudioComponent").d("Stopped PulseAudio server");
        });
        singleThreadExecutor.shutdown();
    }

    public void pause() {
        singleThreadExecutor.execute(() -> {
            if (!isPaused.get()) {
                if (!isPauseResumeRunning.get()) {
                    isPauseResumeRunning.set(true);
                    Timber.tag("PulseAudioComponent").d("Pausing...");

                    if (updateSink(true)) {
                        isPaused.set(true);
                        Timber.tag("PulseAudioComponent").d("Audio paused");
                    } else {
                        Timber.tag("PulseAudioComponent").d("Failed to pause Audio");
                    }

                    isPauseResumeRunning.set(false);
                }
            }
        });
    }

    public void resume() {
        singleThreadExecutor.execute(() -> {
            if (isPaused.get()) {
                if (!isPauseResumeRunning.get()) {
                    isPauseResumeRunning.set(true);
                    Timber.tag("PulseAudioComponent").d("Resuming...");

                    if (updateSink(false)) {
                        isPaused.set(false);
                        Timber.tag("PulseAudioComponent").d("Audio resumed");
                    } else {
                        Timber.tag("PulseAudioComponent").d("Failed to resume Audio");
                    }

                    isPauseResumeRunning.set(false);
                }
            }
        });
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    public void setPerformanceMode(int performanceMode) {
        this.performanceMode = (byte) performanceMode;
    }

    private void startPulseAudio() {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        // nativeLibraryDir = nativeLibraryDir.replace("arm64", "arm64-v8a");
        File workingDir = getWorkingDir(context);
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        // Clear any previous staled files, e.g. cookie
        File configDir = new File(workingDir, "/.config");
        if (configDir.exists()) {
            FileUtils.delete(configDir);
        }

        File configFile = new File(workingDir, "default.pa");
        String sinkParams = "volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode);
        if (lowLatency) {
            sinkParams += " low_latency=true";
        }

        List<String> configLines = new ArrayList<>();
        configLines.add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\""+socketConfig.path+"\"");
        if (!micOnly) {
            configLines.add("load-module module-aaudio-sink " + sinkParams);
        }
        configLines.addAll(buildMicConfigLines(context, workingDir));
        FileUtils.writeString(configFile, String.join("\n", configLines));

        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules");

        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:"+nativeLibraryDir+":"+modulesDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));


        String command = nativeLibraryDir+"/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=true";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        // Uncomment to enable verbose log in pulseaudio
        //command += " -vvv";

        String output = ProcessHelper.execWithOutput(command, envVars.toStringArray(), workingDir, true);
        Timber.tag("PulseAudioComponent").d("Started PulseAudio server %s", output);
    }

    /**
     * Builds the {@code default.pa} lines that publish the Android microphone as a PulseAudio source.
     *
     * Wine/Proton's {@code winepulse.drv} enumerates every source reported by the server, so this is
     * all that is required for the microphone to appear as a Windows recording device inside the
     * container. No Wine-side patch and no registry change is needed.
     *
     * Returns an empty list (i.e. audio output behaves exactly as before) when the microphone is
     * disabled for this container or when the bundled PulseAudio build does not ship
     * {@code module-pipe-source.so}.
     */
    private List<String> buildMicConfigLines(Context context, File workingDir) {
        List<String> lines = new ArrayList<>();
        if (!micEnabled) return lines;

        if (!isMicModuleAvailable(context)) {
            Timber.tag("PulseAudioComponent").w(
                "Microphone requested but %s is missing from the bundled pulseaudio asset; skipping",
                PIPE_SOURCE_MODULE);
            return lines;
        }

        // module-pipe-source does mkfifo() itself and fails on EEXIST, so clear any stale FIFO left
        // behind by a server that was killed instead of shut down.
        File fifoFile = new File(workingDir, MIC_FIFO_NAME);
        //noinspection ResultOfMethodCallIgnored
        fifoFile.delete();

        lines.add("load-module module-pipe-source"
            + " source_name=" + MIC_SOURCE_NAME
            + " file=\"" + fifoFile.getAbsolutePath() + "\""
            + " format=s16le"
            + " rate=" + MicrophoneComponent.SAMPLE_RATE
            + " channels=" + MicrophoneComponent.CHANNEL_COUNT
            + " source_properties=device.description=Microphone");
        // Make it the default so apps that just ask for "the" capture device get the mic instead of
        // the sink's monitor source.
        lines.add("set-default-source " + MIC_SOURCE_NAME);

        Timber.tag("PulseAudioComponent").d("Microphone source '%s' enabled (%s)", MIC_SOURCE_NAME, fifoFile);
        return lines;
    }

    private String execPactlCommand(String command) {        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");

        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        File modulesDir = new File(workingDir, "modules");
        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:" + nativeLibraryDir + ":" + modulesDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));
        envVars.put("PULSE_SERVER", socketConfig.path);

        return ProcessHelper.execWithOutput(workingDir + "/pactl " + command, envVars.toStringArray(), workingDir, true, 5);
    }

    private boolean updateSink(boolean suspend) {
        if (!suspend) {
            return !execPactlCommand("suspend-sink " + SINK_NAME + " false").toLowerCase().contains("process timeout");
        } else {
            return !execPactlCommand("suspend-sink " + SINK_NAME + " true").toLowerCase().contains("process timeout");
        }
    }

}
