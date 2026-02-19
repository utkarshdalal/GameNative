package com.winlator.xenvironment.components;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;
import java.util.ArrayList;

import app.gamenative.BuildConfig;

public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();
    private float volume = 1.0f;
    private byte performanceMode = 1;
    private volatile boolean isPaused = false;
    private int pauseCount = 0;

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
            pauseCount = 0;
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
            pauseCount = 0;
        }
    }

    public void pause() {
        Log.d("PulseAudioComponent", "Pausing...");
        synchronized (lock) {
            if (isPaused || pid == -1) return;
            ProcessHelper.suspendProcess(pid);
            isPaused = true;
            pauseCount++;
        }
    }

    public void resume() {
        Log.d("PulseAudioComponent", "Resuming...");
        synchronized (lock) {
            if (!isPaused || pid == -1) return;

            if (pauseCount >= 3) {
                // After several SIGSTOP/SIGCONT cycles, PulseAudio's internal state can
                // become corrupted (ring buffers, socket read positions). Kill and restart
                // to guarantee a clean state. PA clients reconnect automatically.
                Log.d("PulseAudioComponent", "Restarting PulseAudio after " + pauseCount + " suspend cycles to prevent state corruption");
                Process.killProcess(pid);
                pid = execPulseAudio();
                pauseCount = 0;
            } else {
                ProcessHelper.resumeProcess(pid);
            }

            isPaused = false;
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

        if (BuildConfig.DEBUG) {
            // Show pulseaudio debug output
            command += " -vvv";
        }


        return ProcessHelper.exec(command, envVars.toStringArray(), workingDir);
    }
}
