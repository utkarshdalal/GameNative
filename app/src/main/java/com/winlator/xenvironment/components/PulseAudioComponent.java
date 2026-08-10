package com.winlator.xenvironment.components;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

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
    private final String SOURCE_NAME = "AAudioSource";

    private float volume = 1.0f;
    private byte performanceMode = 1;
    private final AtomicBoolean isPauseResumeRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private boolean lowLatency = false;
    private boolean micEnabled = false;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    public PulseAudioComponent(UnixSocketConfig socketConfig, boolean lowLatency) {
        this.socketConfig = socketConfig;
        this.lowLatency = lowLatency;
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
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
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
        // Without a capture source the only recording device PulseAudio offers is
        // AAudioSink.monitor, which Wine hands to games as a microphone - so a game with
        // voice chat ends up transmitting its own output back into the lobby. Load a real
        // source when we are allowed to; module-aaudio-source makes itself the default
        // source, which is what actually outranks the monitor.
        String config = String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\""+socketConfig.path+"\"",
                "load-module module-aaudio-sink " + sinkParams
        );

        if (hasMicrophonePermission(context)) {
            config += "\nload-module module-aaudio-source source_name=" + SOURCE_NAME;
            micEnabled = true;
        } else {
            // Not fatal: the daemon runs with --fail=false and simply comes up without a
            // capture device, which is the behaviour before this change.
            Timber.tag("PulseAudioComponent").i("RECORD_AUDIO not granted, starting without a capture source");
            micEnabled = false;
        }

        FileUtils.writeString(configFile, config);

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

    private String execPactlCommand(String command) {
        Context context = environment.getContext();
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

    private boolean hasMicrophonePermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Load the capture source into an already running daemon.
     *
     * The daemon reads default.pa exactly once, when it spawns. If the user grants
     * RECORD_AUDIO after that - which is the normal case on a first launch, since the
     * permission dialog is answered while the container is still booting - there would
     * otherwise be no capture device until the game is relaunched. Loading the module at
     * runtime avoids that.
     */
    public void enableMicrophone() {
        if (singleThreadExecutor.isShutdown()) return;

        singleThreadExecutor.execute(() -> {
            if (micEnabled) return;

            Context context = environment.getContext();
            if (!hasMicrophonePermission(context)) return;

            String result = execPactlCommand("load-module module-aaudio-source source_name=" + SOURCE_NAME);
            String lower = result.toLowerCase();
            if (lower.contains("failure") || lower.contains("process timeout")) {
                Timber.tag("PulseAudioComponent").w("Failed to load capture source at runtime: %s", result.trim());
            } else {
                micEnabled = true;
                Timber.tag("PulseAudioComponent").i("Capture source loaded after permission grant");
            }
        });
    }

    private boolean updateSink(boolean suspend) {
        String state = suspend ? "true" : "false";
        boolean sinkUpdated = !execPactlCommand("suspend-sink " + SINK_NAME + " " + state)
                .toLowerCase().contains("process timeout");

        // The capture source has to follow the sink. A suspended game must not keep
        // holding the microphone open, or the Android privacy indicator stays lit and
        // Android 14+ background-capture restrictions apply. Failing to suspend the
        // source is logged rather than blocking the pause/resume transition, since the
        // source is optional.
        if (micEnabled) {
            String result = execPactlCommand("suspend-source " + SOURCE_NAME + " " + state).toLowerCase();
            // pactl reports a missing source as "Failure: No such entity" on stderr, which
            // execPactlCommand captures, so check for that as well as a timeout.
            if (result.contains("failure") || result.contains("process timeout")) {
                Timber.tag("PulseAudioComponent").w("Failed to %s source %s: %s",
                        suspend ? "suspend" : "resume", SOURCE_NAME, result.trim());
                if (result.contains("no such entity")) {
                    // The module never loaded - most likely the pulseaudio asset predates
                    // module-aaudio-source. Stop issuing suspend-source rather than warning
                    // on every pause for the rest of the session.
                    Timber.tag("PulseAudioComponent").w("Capture source absent, disabling source suspend handling");
                    micEnabled = false;
                }
            }
        }

        return sinkUpdated;
    }

}
