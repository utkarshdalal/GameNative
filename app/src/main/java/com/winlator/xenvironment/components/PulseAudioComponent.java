package com.winlator.xenvironment.components;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;

import app.gamenative.BuildConfig;

public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();
    private float volume = 1.0f;
    private byte performanceMode = 1;
    private boolean isPaused = false;

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        Log.d("PulseAudioComponent", "Starting...");
        synchronized (lock) {
            stop();
            pid = execPulseAudio();
            isPaused = false;
        }
    }

    @Override
    public void stop() {
        Log.d("PulseAudioComponent", "Stopping...");
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
            isPaused = false;
        }
    }

    public void pause() {
        Log.d("PulseAudioComponent", "Pausing...");
        synchronized (lock) {
            if (!isPaused && pid != -1) {
                executePactl(true);
                isPaused = true;
                final int capturedPid = pid;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    synchronized (lock) {
                        if (isPaused && capturedPid == pid) {
                            ProcessHelper.suspendProcess(capturedPid);
                            Log.d("PulseAudioComponent", "Audio paused");
                        }
                    }
                }, 200);
            }
        }
    }

    public void resume() {
        Log.d("PulseAudioComponent", "Resuming...");
        synchronized (lock) {
            if (isPaused && pid != -1) {
                final int capturedPid = pid;
                ProcessHelper.resumeProcess(capturedPid);
                isPaused = false;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    synchronized (lock) {
                        if (!isPaused && capturedPid == pid) {
                            executePactl(false);
                            Log.d("PulseAudioComponent", "Audio resumed");
                        }
                    }
                }, 200);
            }
        }
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    public void setPerformanceMode(int performanceMode) {
        this.performanceMode = (byte) performanceMode;
    }

    private int execPulseAudio() {
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
                "load-module module-aaudio-sink volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode),
                "set-default-sink AAudioSink"
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

        return ProcessHelper.exec(command, envVars.toStringArray(), workingDir);
    }

    private void executePactl(boolean suspend) {
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

        String suspendCommand = workingDir + "/pactl suspend-sink AAudioSink " + (suspend ? "true" : "false");
        ProcessHelper.exec(suspendCommand, envVars.toStringArray(), workingDir);
    }
}
