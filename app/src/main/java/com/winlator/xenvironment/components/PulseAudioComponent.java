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

/**
 * PulseAudio component with delayed unload strategy for efficient pause/resume management.
 *
 * Pause/Resume Logic:
 * - On pause: Immediately suspends sink, then schedules module unload after 10 seconds
 * - Quick resume (< 10s): Cancels timer and resumes sink (no module reload needed)
 * - Long pause (≥ 10s): Module unloaded to save CPU
 * - Resume after unload: Automatically detects missing sink and reloads module
 */
public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private final String SINK_NAME = "AAudioSink";

    private java.lang.Process pulseProcess;
    private final Object lock = new Object();
    private float volume = 1.0f;
    private byte performanceMode = 1;
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private Timer unloadTimer;

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    // Add this method to detect optimal sample rate
    private int getOptimalSampleRate() {
        final int fallbackSampleRate = 44100;
        AudioManager audioManager = (AudioManager) environment.getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return fallbackSampleRate;
        }

        String rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        if (rate == null) {
            return fallbackSampleRate;
        }

        try {
            int parsed = Integer.parseInt(rate.trim());
            return parsed > 0 ? parsed : fallbackSampleRate;
        } catch (NumberFormatException ignored) {
            return fallbackSampleRate;
        }
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
            // Cancel unload timer if active
            stopUnloadTimer();

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
        Log.d("PulseAudioComponent", "Pausing...");
        synchronized (lock) {
            if (!isPaused.get() && isServerRunning()) {
                updateSink(true);
                isPaused.set(true);
                Log.d("PulseAudioComponent", "Audio paused");

                // Schedule module unload after 10 seconds
                startUnloadTimer();
            }
        }
    }

    public void resume() {
        Log.d("PulseAudioComponent", "Resuming...");
        synchronized (lock) {
            if (isPaused.get()) {
                // Cancel module unload timer if it is pending to run
                stopUnloadTimer();

                if (isServerRunning()) {
                    isPaused.set(false);

                    // Check if sink is alive, if not reload module
                    if (!isSinkAlive()) {
                        Log.d("PulseAudioComponent", "Sink not alive, reloading module");
                        loadModule();
                    }

                    updateSink(false);
                    Log.d("PulseAudioComponent", "Audio resumed");
                } else {
                    pulseProcess = null;
                    start();
                }
            }
        }
    }

    public void startUnloadTimer() {
        // First check if current timer is active, cancel it.
        stopUnloadTimer();

        unloadTimer = new Timer();
        TimerTask unloadTask = new TimerTask() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (isPaused.get() && isServerRunning()) {
                        unloadModule();
                        Log.d("PulseAudioComponent", "Module unloaded after timeout");
                    }
                }
            }
        };

        // 10 seconds
        int UNLOAD_TIMER_MS = 10000;
        unloadTimer.schedule(unloadTask, UNLOAD_TIMER_MS);
    }

    public void stopUnloadTimer() {
        // Cancel unload timer if still pending
        if (unloadTimer != null) {
            unloadTimer.cancel();
            unloadTimer = null;
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
        final int bitRate = getOptimalSampleRate();

        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        // nativeLibraryDir = nativeLibraryDir.replace("arm64", "arm64-v8a");
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        File configFile = new File(workingDir, "default.pa");
        FileUtils.writeString(configFile, String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""+socketConfig.path+"\"",
                "load-module module-aaudio-sink volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode) + " rate=" + bitRate
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
    }

    private void loadModule() {
        final int bitRate = getOptimalSampleRate();
        execPactlCommand("load-module module-aaudio-sink volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode) + " rate=" + bitRate);
    }

    private boolean isSinkAlive() {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;

        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:"+nativeLibraryDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));
        envVars.put("PULSE_SERVER", socketConfig.path);

        String checkCommand = workingDir + "/pactl list sinks short";
        String output = ProcessHelper.execWithOutput(checkCommand, envVars.toStringArray(), workingDir);
        return output.contains(SINK_NAME);
    }
}
