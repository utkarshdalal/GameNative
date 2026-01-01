package com.winlator.audio;

import static android.media.AudioManager.GET_DEVICES_OUTPUTS;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

public class AudioMonitor {

    private final XEnvironment xEnvironment;
    private final AudioManager audioManager;
    private boolean audioCallbackRegistered = false;
    private final Handler restartHandler = new Handler(Looper.getMainLooper());
    private final Runnable restartRunnable = this::restartAudioComponent;
    private final int restartDelay = 500;

    public AudioMonitor(XEnvironment xEnvironment, Context context) {
        this.xEnvironment = xEnvironment;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.audioCallbackRegistered = true;
        this.audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
    }

    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            // Handle newly added audio devices (e.g., headphones connected)
            try {
                restartHandler.removeCallbacks(restartRunnable);
            } catch (Exception ignored) {
            }

            Timber.d("onAudioDevicesAdded: %s", Arrays.toString(Arrays.stream(addedDevices)
                    .map(device -> device.getProductName() + "-" + device.getType()).toArray()));

            // Check if any new device is sink and all new devices are not in skippedDeviceTypes
            if (Arrays.stream(addedDevices).anyMatch(AudioDeviceInfo::isSink)) {
                restartHandler.postDelayed(restartRunnable, restartDelay);
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            // Handle removed audio devices (e.g., headphones disconnected)
            try {
                restartHandler.removeCallbacks(restartRunnable);
            } catch (Exception ignored) {
            }

            Timber.d("onAudioDevicesRemoved: %s", Arrays.toString(Arrays.stream(removedDevices)
                    .map(device -> device.getProductName() + "-" + device.getType()).toArray()));

            // Check if any removed device is sink and all removed devices are not in skippedDeviceTypes
            if (Arrays.stream(removedDevices).anyMatch(AudioDeviceInfo::isSink)) {
                restartHandler.postDelayed(restartRunnable, restartDelay);
            }
        }
    };

    private void restartAudioComponent() {
        // if it is android 15 or above skip restart
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        final ALSAServerComponent alsaServerComponent = xEnvironment.getComponent(ALSAServerComponent.class);
        if (alsaServerComponent != null) {
            alsaServerComponent.stop();
            alsaServerComponent.start();
        }

        final PulseAudioComponent pulseAudioComponent = xEnvironment.getComponent(PulseAudioComponent.class);
        if (pulseAudioComponent != null) {
            //pulseAudioComponent.stop(); stop is already called inside start function
            pulseAudioComponent.start();
        }
    }

    public void onPause() {
        if (audioCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
            audioCallbackRegistered = false;
        }
    }

    public void onResume() {
        if (!audioCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
            audioCallbackRegistered = true;
        }
    }
}
