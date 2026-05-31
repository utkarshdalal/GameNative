package com.winlator.xenvironment.components;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.gamenative.BuildConfig;

/**
 * PulseAudio component with timer-based suspend strategy for efficient pause/resume management.
 *
 * Suspend Behavior Modes:
 *
 * 1. suspend-via-thread (default):
 *    Suspend: cancel timers -> set isPaused=true + updateSink(true) -> suspend timer (200ms) -> suspendProcess(SIGSTOP)
 *    Resume: cancel timers -> set isPaused=false -> resumeProcess(SIGCONT) -> resume timer (200ms) -> updateSink(false)
 *    - Fast and lightweight, uses ProcessHelper.suspendProcess/resumeProcess
 *    - isPaused set immediately, updateSink(true) on pause immediately, updateSink(false) on resume delayed
 *    - Process suspend/resume operations are delayed via timers
 *
 * 2. suspend-via-pactl (power-saving):
 *    Suspend: cancel timers -> set isPaused=true + updateSink(true) -> suspend timer (120s/10s debug) -> pactl unload module
 *    Resume: cancel timers -> set isPaused=false -> resume timer (200ms) -> check sink alive -> pactl load module OR updateSink(false)
 *    - Quick resume (< timeout): Cancels timer and resumes sink (no module reload)
 *    - Long pause (≥ timeout): Module unloaded to save CPU
 *    - Resume after unload: Automatically detects missing sink and reloads module
 *    - isPaused set immediately, all sink operations delayed via timer to avoid UI blocking
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
    private Timer resumeTimer;
    private String suspendBehavior = SUSPEND_BEHAVIOR_THREAD;
    private boolean lowLatency = false;

    public PulseAudioComponent(UnixSocketConfig socketConfig, String suspendBehavior, boolean lowLatency) {
        this.socketConfig = socketConfig;
        this.suspendBehavior = suspendBehavior;
        this.lowLatency = lowLatency;
    }


    @Override
    public void start() {
        Log.d("PulseAudioComponent", "Starting...");
        synchronized (lock) {
            if (pulseProcess == null) {
                pulseProcess = execPulseAudio();
                isPaused.set(false);
            }
        }
    }

    @Override
    public void stop() {
        Log.d("PulseAudioComponent", "Stopping...");
        synchronized (lock) {
            // Cancel timers if active
            stopSuspendTimer();
            stopResumeTimer();

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
        }
    }

    public void pause() {
        synchronized (lock) {
            if (!isPaused.get() && isServerRunning()) {
                Log.d("PulseAudioComponent", "Pausing...");

                // Cancel timers if active
                stopSuspendTimer();
                stopResumeTimer();

                // Suspend sink and set isPaused together immediately
                isPaused.set(true);
                updateSink(true);

                if (SUSPEND_BEHAVIOR_THREAD.equals(suspendBehavior)) {
                    // Schedule process suspend after 200ms delay
                    startSuspendTimer(200, () -> {
                        synchronized (lock) {
                            if (isPaused.get() && isServerRunning()) {
                                int pid = ProcessHelper.getPid(pulseProcess);
                                if (pid > 0) {
                                    ProcessHelper.suspendProcess(pid);
                                    Log.d("PulseAudioComponent", "Process suspended with PID: " + pid);
                                }
                            }
                        }
                    });
                } else {
                    // Schedule module unload after delay (120s release / 10s debug)
                    long unloadDelay = BuildConfig.DEBUG ? 10000 : 120000;

                    startSuspendTimer(unloadDelay, () -> {
                        synchronized (lock) {
                            if (isPaused.get() && isServerRunning()) {
                                unloadModule();
                                Log.d("PulseAudioComponent", "Module unloaded after timeout");
                            }
                        }
                    });
                }
                Log.d("PulseAudioComponent", "Audio paused");
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            if (isPaused.get()) {
                Log.d("PulseAudioComponent", "Resuming...");

                // Cancel timers if active
                stopSuspendTimer();
                stopResumeTimer();

                if (isServerRunning()) {
                    // Set isPaused immediately
                    isPaused.set(false);

                    if (SUSPEND_BEHAVIOR_THREAD.equals(suspendBehavior)) {
                        // Resume Process first
                        int pid = ProcessHelper.getPid(pulseProcess);
                        if (pid > 0) {
                            ProcessHelper.resumeProcess(pid);
                            Log.d("PulseAudioComponent", "Process resumed with PID: " + pid);
                        }

                        // Schedule updateSink after 200ms delay
                        startResumeTimer(200, () -> {
                            synchronized (lock) {
                                if (!isPaused.get() && isServerRunning()) {
                                    updateSink(false);
                                }
                            }
                        });
                    } else {
                        if (!isModuleLoaded.get()) {
                            if (!isSinkAlive()) {
                                Log.d("PulseAudioComponent", "Sink not alive, reloading module");
                                loadModule();
                            } else {
                                updateSink(false);
                            }
                        } else {
                            // Start a timer here to avoid UI locking
                            startResumeTimer(200, () -> {
                                synchronized (lock) {
                                    if (!isPaused.get() && isServerRunning()) {
                                        // Check if module was unloaded during pause, reload if needed
                                        updateSink(false);
                                    }
                                }
                            });
                        }
                    }
                    Log.d("PulseAudioComponent", "Audio resumed");
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

    private void startResumeTimer(long delayMs, Runnable action) {
        stopResumeTimer();

        resumeTimer = new Timer();
        TimerTask resumeTask = new TimerTask() {
            @Override
            public void run() {
                action.run();
            }
        };
        resumeTimer.schedule(resumeTask, delayMs);
    }

    private void stopResumeTimer() {
        if (resumeTimer != null) {
            resumeTimer.cancel();
            resumeTimer = null;
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
        execPactlCommand("suspend-sink " + SINK_NAME + " " + (suspend ? "true" : "false"));
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
