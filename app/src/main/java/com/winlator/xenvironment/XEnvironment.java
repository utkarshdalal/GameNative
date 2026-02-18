package com.winlator.xenvironment;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.BionicProgramLauncherComponent;
import com.winlator.xenvironment.components.GlibcProgramLauncherComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final ImageFs imageFs;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

    private boolean winetricksRunning = false;

    public synchronized boolean isWinetricksRunning() {
        return winetricksRunning;
    }

    public synchronized void setWinetricksRunning(boolean running) {
        this.winetricksRunning = running;
    }

    public XEnvironment(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    public Context getContext() {
        return context;
    }

    public ImageFs getImageFs() {
        return imageFs;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public static File getTmpDir(Context context) {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir(getContext()));
        for (EnvironmentComponent environmentComponent : this) environmentComponent.start();
    }

    public void stopEnvironmentComponents() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.stop();
    }

    public void onPause() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.suspendProcess();
        GlibcProgramLauncherComponent glibcProgramLauncherComponent = getComponent(GlibcProgramLauncherComponent.class);
        if (glibcProgramLauncherComponent != null) glibcProgramLauncherComponent.suspendProcess();
        BionicProgramLauncherComponent bionicProgramLauncherComponent = getComponent(BionicProgramLauncherComponent.class);
        if (bionicProgramLauncherComponent != null) bionicProgramLauncherComponent.suspendProcess();

        // Pause audio components
        PulseAudioComponent pulseAudioComponent = getComponent(PulseAudioComponent.class);
        if (pulseAudioComponent != null) pulseAudioComponent.pause();
        ALSAServerComponent alsaServerComponent = getComponent(ALSAServerComponent.class);
        if (alsaServerComponent != null) alsaServerComponent.pause();
    }

    public void onResume() {
        // Resume audio FIRST so it's ready when game processes wake up
        PulseAudioComponent pulseAudioComponent = getComponent(PulseAudioComponent.class);
        if (pulseAudioComponent != null) pulseAudioComponent.resume();
        ALSAServerComponent alsaServerComponent = getComponent(ALSAServerComponent.class);
        if (alsaServerComponent != null) alsaServerComponent.resume();

        // Then resume game processes
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.resumeProcess();
        GlibcProgramLauncherComponent glibcProgramLauncherComponent = getComponent(GlibcProgramLauncherComponent.class);
        if (glibcProgramLauncherComponent != null) glibcProgramLauncherComponent.resumeProcess();
        BionicProgramLauncherComponent bionicProgramLauncherComponent = getComponent(BionicProgramLauncherComponent.class);
        if (bionicProgramLauncherComponent != null) bionicProgramLauncherComponent.resumeProcess();
    }
}
