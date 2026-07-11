/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.xr;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.Display;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;

import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.winhandler.WinHandler;
import com.winlator.xr.api.XrAPI;
import com.winlator.xr.runtime.MetaQuest;
import com.winlator.xr.runtime.Pico;
import com.winlator.xr.ui.XrDialog;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import app.gamenative.MainActivity;
import app.gamenative.ui.PlayBridge;
import dagger.hilt.android.AndroidEntryPoint;

import static com.winlator.xr.api.XrInterface.AppInput;
import static com.winlator.xr.api.XrInterface.ControllerButton;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

@AndroidEntryPoint
public class XrActivity extends MainActivity {
    private static final String EXTRA_CONTAINER_ID = "EXTRA_CONTAINER_ID";
    private static final String EXTRA_OPEN_CONTAINER = "EXTRA_OPEN_CONTAINER";
    private static final String EXTRA_REBOOT_XR = "EXTRA_REBOOT_XR";

    private static XrActivity instance;
    public Container container;
    private XServer xserver;

    // Booting flags
    public static boolean shouldOpenContainer = false;
    public static boolean shouldRebootIn2D = true;
    public static boolean shouldRebootInXR = false;
    public static boolean shouldRunInXR = app.gamenative.BuildConfig.XR_BUILD;

    // Configuration flags
    private static boolean isEnabled = false;
    public static boolean isImmersive = false;
    // When false, the XR activity is a flat 2D panel (booting splash / dialogs visible in your
    // room) and XrRenderer renders like the normal flat GLRenderer. Flipped true at winStarted to
    // create the OpenXR session and switch to immersive rendering in-place (no activity swap).
    public static boolean xrSessionActive = false;
    private static boolean isHeadTrackingAllowed = false;
    public static boolean isAER = false;
    public static boolean isSBS = false;
    public static boolean isUDP = false;
    public static boolean isVR = false;
    public static boolean mouseEmulation;
    public static boolean mouseLeftHanded;
    public static boolean mouseLightgun;

    // Rendering status
    private static long lastActive = 0;
    private static float lastDistance = 5;
    private final ArrayList<Integer> framesyncMapping = new ArrayList<>();
    private int lastFrameSync = 0;
    public int lastMode3D = -1;

    // XR input/output
    private XrAPI xrAPI = null;
    private XrController xrController = null;
    private int frameDiagTick = 0;
    private XrKeyboard xrKeyboard = null;
    private android.widget.EditText editText = null;

    static {
        System.loadLibrary("xr");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // load config
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean usePassthrough = prefs.getBoolean("use_pt", true);
        nativeSetUsePT(usePassthrough);
        boolean curvedScreen = prefs.getBoolean("use_cs", false);
        nativeSetCurvedScreen(curvedScreen);
        mouseEmulation = prefs.getBoolean("use_xr_mouse", true);
        mouseLeftHanded = prefs.getBoolean("use_xr_leftHanded", false);
        mouseLightgun = prefs.getBoolean("use_xr_lightgun", false);
        sendManufacturer(Build.MANUFACTURER.toUpperCase());

        // Hidden EditText that backs the in-VR keyboard (XrKeyboard). Without it, tapping the
        // Keyboard/ReShade menu items NPEs (new XrKeyboard(null)).
        editText = new android.widget.EditText(this);
        editText.setVisibility(View.GONE);
        addContentView(editText, new android.view.ViewGroup.LayoutParams(1, 1));

        // set status
        instance = this;
        isEnabled = true;
        // This is a declared immersive VR activity (VR intent-filter), so we must submit XR frames
        // from the start — Horizon culls a VR activity that isn't rendering immersively. During the
        // game's boot we show passthrough + an empty quad; the game paints on it once it maps a
        // window. (Cloud-sync + conflict dialogs already happened flat, before this activity.)
        xrSessionActive = true;
        shouldOpenContainer = getIntent().getBooleanExtra(EXTRA_OPEN_CONTAINER, false);
        shouldRebootIn2D = !getIntent().getBooleanExtra(EXTRA_REBOOT_XR, false);
        shouldRebootInXR = getIntent().getBooleanExtra(EXTRA_REBOOT_XR, false);
        String containerId = getIntent().getStringExtra(EXTRA_CONTAINER_ID);
        container = new ContainerManager(this).getContainerById(containerId);

        // Drop any bridge closure left over from the flat activity we replaced; wait for THIS
        // activity's PluviaMain composition to publish its own before bringing the game forward.
        // (The flat->XR handoff hands off an already-running game, so this re-attaches rather
        // than reboots — see maybeHandOffToXr in XServerScreen.)
        PlayBridge.onClickPlay = null;
        new Thread(() -> {
            // Give the runtime time to bring this VR activity to a frame-ready (FOCUSED) session
            // state BEFORE we re-attach and start submitting XR frames. The game is already
            // running, so without this delay the very first xrLocateViews runs on a not-yet-ready
            // session and libxr crashes (SEGV). This is the deferred-model equivalent of the
            // multi-second boot delay the "boot inside XR" path gets for free.
            try {
                Thread.sleep(REATTACH_READY_DELAY_MS);
            } catch (Exception e) {
            }
            long deadline = System.currentTimeMillis() + 8000;
            while (PlayBridge.onClickPlay == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (Exception e) {
                }
            }
            runOnUiThread(() -> {
                if (PlayBridge.onClickPlay != null) {
                    PlayBridge.onClickPlay.invoke(containerId, shouldOpenContainer);
                }
            });
        }).start();
    }

    // Delay before the deferred flat->XR handoff re-attaches the running game, so the OpenXR
    // session is FOCUSED before the first frame. Tunable; raise if the xrLocateViews SEGV recurs.
    private static final long REATTACH_READY_DELAY_MS = 2000;

    // Set true while the activity is paused / not window-focused. When set, XrRenderer stops
    // driving the OpenXR frame loop (initFrame/xrLocateViews) — driving it across a focus/pause
    // transition desyncs the runtime ("frame call order invalid") and libxr crashes in
    // xrLocateViews. lastResumeMs gives the runtime a brief window to re-settle after resume
    // before we start submitting frames again.
    public static volatile boolean xrPaused = false;
    public static volatile long lastResumeMs = 0;

    @Override
    public synchronized void onPause() {
        xrPaused = true;
        if (xrController != null) xrController.unload();
        super.onPause();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        xrPaused = false;
        lastResumeMs = android.os.SystemClock.elapsedRealtime();
        xrController = new XrController();
        reclaimImmersiveFocus();
    }

    // Reassert this activity as the foreground task. On HorizonOS an immersive VR activity only gets
    // OpenXR input focus (XR_SESSION_STATE_FOCUSED -> controller buttons work) while it is the top
    // immersive app; the flat->immersive handoff sometimes leaves it merely VISIBLE (axes/mouse track
    // but buttons are dead). Bringing our own task to front is a best-effort nudge to win that grant.
    private void reclaimImmersiveFocus() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) am.moveTaskToFront(getTaskId(), 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
        // NOTE: the fork called closeSession() here, which relaunches the app launcher and kills
        // the process. That turned any activity teardown into a reboot loop. Removed — normal
        // destroy is enough; the guest/env is cleaned up by the app's own lifecycle.
        // Because we no longer kill the process, these session-scoped statics MUST be reset, or the
        // next launch's flat activity sees isEnabled=true and boots the game flat (2D panel) instead
        // of handing off to XR.
        if (instance == this) {
            isEnabled = false;
            xrSessionActive = false;
            xrPaused = false;
            isImmersive = false;
        }
    }

    public boolean onMenuItemClicked(XrDialog.MenuItem id) {
        switch (id) {
            case EXIT_GAME:
                finish();
                return true;
            case SHOW_KEYBOARD:
                if (xrKeyboard == null) {
                    xrKeyboard = new XrKeyboard(editText);
                }
                new Thread(() -> {
                    xrKeyboard.sleep(250); //ensure onWindowFocusChanged was called
                    runOnUiThread(() -> {
                        isVR = false;
                        isAER = false;
                        isImmersive = false;
                        xrKeyboard.show();
                    });
                }).start();
                return true;
            case TASK_MANAGER:
                isImmersive = false;
                WinHandler.getInstance().exec("taskmgr.exe");
                return true;
            case RESHADE_MENU:
                isImmersive = false;
                isSBS = false;
                if (xrKeyboard == null) {
                    xrKeyboard = new XrKeyboard(editText);
                }
                xrKeyboard.sendKey(KeyEvent.KEYCODE_MOVE_HOME);
                return true;
            case WINDOW_SCALE:
                lastDistance -= 1.0f;
                if (lastDistance < 0.5f) {
                    lastDistance = 7.0f;
                }
                return false;
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // NOTE: do NOT gate XR frames on window focus here — an immersive VR activity doesn't hold
        // normal Android window focus, so this fires false during normal operation and would keep
        // us out of immersive mode (flat 2D panel). Backgrounding is handled by onPause/onResume.
        if (editText != null) {
            editText.setVisibility(View.GONE);
        }
        if (hasFocus && xrSessionActive) reclaimImmersiveFocus();
    }

    public synchronized void closeSession() {
        // Neutered. The fork relaunched the launcher + killed the process here, which caused a
        // reboot loop whenever the XR activity was torn down. Exiting VR is user-driven (finish()).
        finish();
    }

    public XServer getXServer() {
        return xserver;
    }

    public static XrActivity getInstance() {
        return instance;
    }

    public static float getDistance() {
        return lastDistance;
    }

    public static boolean isActive() {
        return Math.abs(System.currentTimeMillis() - lastActive) < 5000;
    }

    public static boolean isEnabled() {
        return isEnabled;
    }

    public static boolean isSupported() {
        return getRuntime() != null;
    }

    public Pair<Boolean, Integer> processFramesync(Drawable drawable) {
        // get sync pixel
        ByteBuffer buffer = drawable.getImage((short)0, (short)0, (short)1, (short)1);
        int b = buffer.get(0) & 0xFF;
        int g = buffer.get(1) & 0xFF;
        int r = buffer.get(2) & 0xFF;
        int a = buffer.get(3) & 0xFF;

        //define framesync behavior (the same as in xr/engine.h)
        int step = 12;
        int limit = 256;
        int expectedLength = (limit / step) + 1;

        //automatically find mapping for current color space
        if (framesyncMapping.size() < expectedLength) {
            if (!framesyncMapping.contains(r)) {
                framesyncMapping.add(r);
                framesyncMapping.sort(Comparator.comparingInt(i -> i));
            }
            return new Pair<>(false, b);
        } else if (framesyncMapping.size() == expectedLength) {
            if (!framesyncMapping.contains(r)) {
                framesyncMapping.clear();
                return new Pair<>(false, b);
            }
            r = framesyncMapping.indexOf(r) * step;
        }

        // apply the values
        nativeSetFramesync(r, g, b, a);
        Pair<Boolean, Integer> output = new Pair<>(lastFrameSync != r, b > 0 ? 1 : 0);
        lastFrameSync = r;
        return output;
    }

    public static void openIntent(Context context, String containerId, boolean openContainer, boolean xr) {
        // Create the launch intent
        Class runtime = xr ? getRuntime() : XrActivity.class;
        Intent intent = new Intent(context, runtime);
        intent.putExtra(EXTRA_CONTAINER_ID, containerId);
        intent.putExtra(EXTRA_OPEN_CONTAINER, openContainer);
        intent.putExtra(EXTRA_REBOOT_XR, !xr);

        // Set the activity flags
        final int mainDisplayId = Display.DEFAULT_DISPLAY;
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(mainDisplayId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Launch the activity
        context.startActivity(intent, options.toBundle());

        // Close existing activity
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity activity) {
                activity.finish();
                return;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
    }

    public void updateFrame(float fps, XServer xserver) {
        // Get OpenXR data
        float[] axes = instance.getAxes();
        boolean[] buttons = instance.getButtons();
        this.xserver = xserver;

        // DIAG: do the controllers report ANY signal (axes/pose), or is the session totally input-dead?
        if ((++frameDiagTick % 90) == 0) {
            float amax = 0;
            if (axes != null) for (float a : axes) amax = Math.max(amax, Math.abs(a));
            int bn = 0;
            if (buttons != null) for (boolean b : buttons) if (b) bn++;
            android.util.Log.i("XrDiag", "updateFrame axesLen=" + (axes == null ? -1 : axes.length)
                    + " axesMax=" + amax + " btnLen=" + (buttons == null ? -1 : buttons.length)
                    + " btnPressed=" + bn + " isImmersive=" + isImmersive + " isVR=" + isVR + " mouseEmu=" + mouseEmulation);
        }

        // Communication between XR and Windows apps
        updateXrAPI(axes, buttons);
        xrController.updateHaptics(xrAPI);

        // Android UI input
        lastActive = System.currentTimeMillis();
        if (!xrController.updateAndroidInput(buttons))
            return;

        // Switch immersive/SBS mode
        updateShortcuts(buttons);

        // XServer input
        try (XLock lock = instance.getXServer().lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            xrAPI.consumeInputs(instance.getXServer());
            if (mouseEmulation) {
                xrController.updateMouseAxes(axes, isImmersive && isHeadTrackingAllowed);
                xrController.updateMouseSnapturn(buttons, isImmersive ? 250 : 50);
                if (mouseLightgun && !isImmersive && !isVR)
                    xrController.updateMouseLightgun(axes, lastDistance);
            }
            xrController.updateMouseState(buttons, fps);
            xrController.updateKeyboardButtons(buttons);
        }
    }

    private void updateShortcuts(boolean[] buttons) {
        ControllerButton primaryGrip = mouseLeftHanded ? ControllerButton.L_GRIP : ControllerButton.R_GRIP;
        ControllerButton secondaryPress = !mouseLeftHanded ? ControllerButton.L_THUMBSTICK_PRESS : ControllerButton.R_THUMBSTICK_PRESS;
        if (xrController.getButtonClicked(buttons, secondaryPress)) {
            if (buttons[primaryGrip.ordinal()]) {
                isSBS = !isSBS;
            } else {
                isImmersive = !isImmersive;
            }
        }
    }

    private void updateXrAPI(float[] axes, boolean[] buttons) {
        try {
            if (xrAPI == null) {
                // Set the param to true and put a udp_debug folder in your Winlator D:\ drive
                // with a file named the IP on LAN to send XR data via UDP traffic to that IP.
                xrAPI = new XrAPI(false);
            }

            // VR mode update
            int vrMode = xrAPI.getIntValue(AppInput.MODE_VR);
            isHeadTrackingAllowed = (vrMode == 0) || (vrMode == 3);
            isUDP = vrMode > 0;
            isVR = vrMode == 1;
            getInstance().nativeSetUseVR(isVR);

            if (isUDP) {
                // Field of view adjustment
                float fovx = xrAPI.getValue(AppInput.HMD_FOVX);
                float fovy = xrAPI.getValue(AppInput.HMD_FOVY);
                getInstance().nativeSetFoV(fovx, fovy);

                // 3D mode update
                lastMode3D = xrAPI.getIntValue(AppInput.MODE_3D);
                if (lastMode3D >= 0) {
                    isAER = lastMode3D == 2;
                    isSBS = lastMode3D == 1;
                }

                // Send data into the Windows app
                String data = xrAPI.encode(axes, buttons, 0) + xrAPI.getFlags();
                xrAPI.send(data.getBytes(StandardCharsets.US_ASCII));
            } else {
                xrAPI.updateImplementation();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Class getRuntime() {
        if (Build.MANUFACTURER.compareToIgnoreCase("PICO") == 0) {
            return Pico.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("OCULUS") == 0) {
            return MetaQuest.class;
        } else if (Build.MANUFACTURER.compareToIgnoreCase("META") == 0) {
            return MetaQuest.class;
        } else {
            return null;
        }
    }

    // Rendering
    public native void init(int width, int height, int refresh, int cpu, int gpu);
    public native void bindFramebuffer();
    public native int getWidth();
    public native int getHeight();
    public native boolean initFrame(boolean immersive, boolean sbs, boolean aer, float distance);
    public native void bindFBO(int index);
    public native void endFrame();

    // Controllers
    public native float[] getAxes();
    public native boolean[] getButtons();
    public native void vibrateController(int duration, int chan, float intensity);

    // Settings
    public native void nativeSetFoV(float x, float y);
    public native void nativeSetCurvedScreen(boolean enabled);
    public native void nativeSetUsePT(boolean enabled);
    public native void nativeSetUseVR(boolean enabled);
    public native void nativeSetFramesync(int r, int g, int b, int a);
    public native void sendManufacturer(String manufacturer);
}
