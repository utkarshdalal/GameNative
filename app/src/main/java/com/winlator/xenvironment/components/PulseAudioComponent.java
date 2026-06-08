package com.winlator.xenvironment.components;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.gamenative.BuildConfig;
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
    public static final String SUSPEND_BEHAVIOR_THREAD = "suspend-via-thread";
    public static final String SUSPEND_BEHAVIOR_PACTL = "suspend-via-pactl";

    private final UnixSocketConfig socketConfig;
    private final String SINK_NAME = "AAudioSink";

    private java.lang.Process pulseProcess;
    private final Object lock = new Object();
    private float volume = 1.0f;
    private byte performanceMode = 1;
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isModuleLoaded = new AtomicBoolean(true);
    private Timer suspendTimer;
    private String suspendBehavior = SUSPEND_BEHAVIOR_THREAD;
    private boolean lowLatency = false;

    public PulseAudioComponent(UnixSocketConfig socketConfig, String suspendBehavior, boolean lowLatency) {
        this.socketConfig = socketConfig;
        this.suspendBehavior = suspendBehavior;
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
        Timber.tag("PulseAudioComponent").d("Starting...");
        synchronized (lock) {
            if (pulseProcess == null) {
                killAllPulseAudioProcesses();
                pulseProcess = execPulseAudio();
                isPaused.set(false);
            }
        }
    }

    @Override
    public void stop() {
        Timber.tag("PulseAudioComponent").d("Stopping...");
        synchronized (lock) {
            // Cancel timers if active
            stopSuspendTimer();

            // Stop sink here
            updateSink(true);

            if (isServerRunning()) {
                pulseProcess.destroy(); // Sends SIGTERM
                try {
                    // Wait for it to exit cleanly to guarantee the sink is closed
                    boolean exited = pulseProcess.waitFor(800, TimeUnit.MILLISECONDS);
                    if (!exited) {
                        pulseProcess.destroyForcibly(); // fallback to SIGKILL if stuck
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                pulseProcess = null;
            }
            isPaused.set(false);

            killAllPulseAudioProcesses();
        }
    }

    public void pause() {
        synchronized (lock) {
            if (!isPaused.get() && isServerRunning()) {
                Timber.tag("PulseAudioComponent").d("Pausing...");

                // Cancel timers if active
                stopSuspendTimer();

                // Suspend sink and set isPaused together immediately
                isPaused.set(true);
                updateSink(true);

                // Schedule module unload after delay (120s release / 10s debug)
                final long suspendDelay = BuildConfig.DEBUG ? 10000 : 120000;

                if (SUSPEND_BEHAVIOR_THREAD.equals(suspendBehavior)) {
                    startSuspendTimer(suspendDelay, () -> {
                        synchronized (lock) {
                            if (isPaused.get() && isServerRunning()) {
                                // Suspend process immediately (no delay)
                                int pid = ProcessHelper.getPid(pulseProcess);
                                if (pid > 0) {
                                    ProcessHelper.suspendProcess(pid);
                                    Timber.tag("PulseAudioComponent").d("Process suspended with PID: %s after timeout", pid);
                                }
                            }
                        }
                    });
                } else {
                    startSuspendTimer(suspendDelay, () -> {
                        synchronized (lock) {
                            if (isPaused.get() && isServerRunning()) {
                                unloadModule();
                                Timber.tag("PulseAudioComponent").d("Module unloaded after timeout");
                            }
                        }
                    });
                }
                Timber.tag("PulseAudioComponent").d("Audio paused");
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            if (isPaused.get()) {
                Timber.tag("PulseAudioComponent").d("Resuming...");

                // Cancel timers if active
                stopSuspendTimer();

                if (isServerRunning()) {
                    // Set isPaused immediately
                    isPaused.set(false);

                    if (SUSPEND_BEHAVIOR_THREAD.equals(suspendBehavior)) {
                        // Resume process and update sink immediately (no delay)
                        int pid = ProcessHelper.getPid(pulseProcess);
                        if (pid > 0) {
                            ProcessHelper.resumeProcess(pid);
                            Timber.tag("PulseAudioComponent").d("Process resumed with PID: " + pid);
                        }
                        updateSink(false);
                    } else {
                        // Pactl mode: resume immediately (no delay)
                        if (!isModuleLoaded.get()) {
                            if (!isSinkAlive()) {
                                Timber.tag("PulseAudioComponent").d("Sink not alive, reloading module");
                                loadModule();
                            } else {
                                updateSink(false);
                            }
                        } else {
                            updateSink(false);
                        }
                    }
                    Timber.tag("PulseAudioComponent").d("Audio resumed");
                } else {
                    pulseProcess = null;
                    start();
                }
            }
        }
    }

    private void startSuspendTimer(long delayMs, Runnable action) {
        stopSuspendTimer();

        suspendTimer = new Timer();
        TimerTask suspendTask = new TimerTask() {
            @Override
            public void run() {
                action.run();
            }
        };
        suspendTimer.schedule(suspendTask, delayMs);
    }

    private void stopSuspendTimer() {
        if (suspendTimer != null) {
            suspendTimer.cancel();
            suspendTimer = null;
        }
    }

    public boolean isServerRunning() {
        return pulseProcess != null && pulseProcess.isAlive();
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    public void setPerformanceMode(int performanceMode) {
        this.performanceMode = (byte) performanceMode;
    }

    private java.lang.Process execPulseAudio() {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        // nativeLibraryDir = nativeLibraryDir.replace("arm64", "arm64-v8a");
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        File configFile = new File(workingDir, "default.pa");
        String sinkParams = "volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode);
        if (lowLatency) {
            sinkParams += " low_latency=true";
        }
        FileUtils.writeString(configFile, String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""+socketConfig.path+"\"",
                "load-module module-aaudio-sink " + sinkParams
        ));

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
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        // Uncomment to enable verbose log in pulseaudio
        //command += " -vvv";

        return ProcessHelper.startProcess(command, envVars.toStringArray(), workingDir);
    }

    private void execPactlCommand(String command) {
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

        ProcessHelper.exec(workingDir + "/pactl " + command, envVars.toStringArray(), workingDir);
    }

    private void updateSink(boolean suspend) {
        if (!suspend) {
            execPactlCommand("suspend-sink " + SINK_NAME + " false");
        } else {
            execPactlCommand("suspend-sink " + SINK_NAME + " true");
        }
    }

    private void unloadModule() {
        execPactlCommand("unload-module module-aaudio-sink");
        isModuleLoaded.set(false);
    }

    private void loadModule() {
        String sinkParams = "volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode);
        if (lowLatency) {
            sinkParams += " low_latency=true";
        }
        execPactlCommand("load-module module-aaudio-sink " + sinkParams);
        isModuleLoaded.set(true);
    }

    private boolean isSinkAlive() {
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

        String checkCommand = workingDir + "/pactl list sinks short";
        String output = ProcessHelper.execWithOutput(checkCommand, envVars.toStringArray(), workingDir);
        //Log.d("PulseAudioComponent", "isSinkAlive output: " + output);
        return output.contains(SINK_NAME);
    }
}
