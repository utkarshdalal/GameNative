package com.winlator.winhandler;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationAttributes;
import android.os.VibratorManager;
import android.view.View;

// import com.winlator.XServerDisplayActivity;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.ControllerManager;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.TouchMouse;
import com.winlator.math.XForm;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.XServerRendererView;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class WinHandler {

    private static final String TAG = "WinHandler";
    private final ControllerManager controllerManager;
    public static final int MAX_PLAYERS = 4;
    private final MappedByteBuffer[] extraGamepadBuffers = new MappedByteBuffer[MAX_PLAYERS - 1];
    private final ExternalController[] extraControllers = new ExternalController[MAX_PLAYERS - 1];
    private MappedByteBuffer gamepadBuffer;
    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;
    private final ArrayDeque<Runnable> actions;
    private ExternalController currentController;
    private volatile int currentControllerId;
    private byte dinputMapperType;
    private final List<Integer> gamepadClients;
    private boolean initReceived;
    private InetAddress localhost;
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private PreferredInputApi preferredInputApi;
    private final ByteBuffer receiveData;
    private final DatagramPacket receivePacket;
    private volatile boolean running;
    private final ByteBuffer sendData;
    private final DatagramPacket sendPacket;
    private DatagramSocket socket;
    private final ArrayList<Integer> xinputProcesses;
    private final XServer xServer;
    private final XServerRendererView xServerView;

    private InputControlsView inputControlsView;
    private Thread[] rumblePollerThreads = new Thread[MAX_PLAYERS];
    private Thread rumbleKeepaliveThread;
    // Serializes vibration apply/cancel across the pollers, keepalive, and UI threads.
    private final Object rumbleLock = new Object();
    // waitForRumble() blocks until the game sends a NEW rumble command, so the poller can't
    // refresh a held rumble; this is the cadence at which the keepalive re-applies it. It re-arms
    // 1s before the controller one-shot expires (long one-shot + rare refresh per upstream #1584 —
    // far fewer vibrate() IPC/Bluetooth calls than a short one-shot refreshed every ~240ms).
    private static final int RUMBLE_KEEPALIVE_MS = 9000;
    private final short[] lastLowFreq = new short[MAX_PLAYERS];
    private final short[] lastHighFreq = new short[MAX_PLAYERS];
    private final boolean[] isRumbling = new boolean[MAX_PLAYERS];
    private final int[] rumbleDeviceIds = new int[MAX_PLAYERS];
    private boolean isShowingAssignDialog = false;
    private Context activity;
    private final java.util.Set<Integer> ignoredDeviceIds = new java.util.HashSet<>();
    private RandomAccessFile gamepadRaf;
    private RandomAccessFile[] extraGamepadRafs = new RandomAccessFile[MAX_PLAYERS - 1];

    private static final int OFF_LX = 4;
    private static final int OFF_LY = 6;
    private static final int OFF_RX = 8;
    private static final int OFF_RY = 10;
    private static final int OFF_LT = 12;
    private static final int OFF_RT = 14;
    private static final int OFF_BTN = 16;
    private static final int OFF_HAT = 31;
    private static final int OFF_RUMBLE_LOW = 32;
    private static final int OFF_RUMBLE_HIGH = 34;
    private static final int OFF_CONNECTED = 40;
    private static final int CONTROLLER_RUMBLE_DURATION_MS = 1000;
    private static final int PHONE_RUMBLE_FALLBACK_DURATION_MS = 40;
    private static final int STANDALONE_PHONE_RUMBLE_DURATION_MS = 70;
    private static final int STANDALONE_PHONE_RUMBLE_THROTTLE_MS = 120;

    // --- Vibration routing (off / controller / device) + intensity ---
    // Long one-shots refreshed shortly before expiry: vibrate() replaces the in-flight effect, so
    // rumble changes/stops still apply instantly, but a held rumble costs one re-arm per period
    // instead of constant refreshing.
    private static final int CONTROLLER_RUMBLE_MS = 10000;
    private static final int DEVICE_RUMBLE_MS = 60000;
    // Refresh the device one-shot only as it nears expiry (its period is longer than the controller's).
    private static final long DEVICE_RUMBLE_REFRESH_MS = DEVICE_RUMBLE_MS - 5_000L;
    private static final String DEFAULT_VIBRATION_MODE = "controller";
    private static final Set<String> VALID_VIBRATION_MODES =
            new HashSet<>(Arrays.asList("off", "controller", "device"));
    // AudioAttributes is available on all supported API levels; VibrationAttributes needs API 31.
    private static final AudioAttributes AUDIO_ATTRS_GAME = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .build();
    private final VibrationAttributes vibrationAttrs;
    // Motor-ID pairs already logged once, to avoid flooding logcat on every rumble.
    private final Set<String> loggedRumbleMotorIds = new HashSet<>();
    private volatile String vibrationMode = DEFAULT_VIBRATION_MODE;
    private volatile int vibrationIntensity = 100;

    // Add method to set InputControlsView
    public void setInputControlsView(InputControlsView view) {
        this.inputControlsView = view;
    }

    private static String describeDevice(InputDevice device) {
        if (device == null) return "null";
        return "id=" + device.getId()
                + " name=\"" + device.getName() + "\""
                + " descriptor=\"" + device.getDescriptor() + "\"";
    }

    public enum PreferredInputApi {
        AUTO,
        DINPUT,
        XINPUT,
        BOTH
    }

    static {
        System.loadLibrary("evshim");
    }

    private static native void notifyStateChanged(int playerIndex);
    public static native int waitForRumble(int idx, int lastSeq);
    public static native void rumbleTeardown(int idx);

    public WinHandler(XServer xServer, XServerRendererView xServerView) {
        ByteBuffer allocate = ByteBuffer.allocate(64);
        ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
        ByteBuffer order = allocate.order(byteOrder);
        this.sendData = order;
        ByteBuffer order2 = ByteBuffer.allocate(64).order(byteOrder);
        this.receiveData = order2;
        this.sendPacket = new DatagramPacket(order.array(), 64);
        this.receivePacket = new DatagramPacket(order2.array(), 64);
        this.actions = new ArrayDeque<>();
        this.initReceived = false;
        this.running = false;
        this.dinputMapperType = (byte) 1;
        this.preferredInputApi = PreferredInputApi.BOTH;
        this.gamepadClients = new CopyOnWriteArrayList();
        this.xinputProcesses = new ArrayList<>();
        this.xServer = xServer;
        this.xServerView = xServerView;
        this.controllerManager = ControllerManager.getInstance();
        this.activity = xServerView.getContext();
        this.currentControllerId = -1;

        // VibrationAttributes and its Vibrator/VibratorManager overloads are API 33+; below that
        // we fall back to AudioAttributes, so only build it on TIRAMISU+ (null acts as the gate).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.vibrationAttrs = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build();
        } else {
            this.vibrationAttrs = null;
        }
        for (int i = 0; i < rumbleDeviceIds.length; i++) {
            rumbleDeviceIds[i] = -1;
        }
    }

    public void refreshControllerMappings() {
        refreshControllerMappings(false);
    }

    public void refreshControllerMappingsForHotplug() {
        refreshControllerMappings(true);
    }

    private void refreshControllerMappings(boolean clearDisconnectedSlots) {
        Log.d(TAG, "Refreshing controller assignments from settings...");
        currentController = null;
        for (int i = 0; i < extraControllers.length; i++) {
            extraControllers[i] = null;
        }
        controllerManager.scanForDevices();
        InputDevice p1Device = controllerManager.getAssignedDeviceForSlot(0);
        if (p1Device != null) {
            currentController = ExternalController.getController(p1Device.getId());
            if (currentController != null) {
                currentController.setContext(activity);
                Log.i(TAG, "Initialized Player 1 with: " + describeDevice(p1Device));
            }
        } else {
            Log.i(TAG, "Player 1 has no assigned connected controller");
        }
        setGamepadSlotConnected(0, currentController != null || isVirtualGamepadActive());
        // Initialize Extra Players (2, 3, 4)
        for (int i = 0; i < extraControllers.length; i++) {
            // Player 2 is slot 1, which corresponds to extraControllers[0]
            InputDevice extraDevice = controllerManager.getAssignedDeviceForSlot(i + 1);
            if (extraDevice != null) {
                extraControllers[i] = ExternalController.getController(extraDevice.getId());
                if (extraControllers[i] != null) {
                    extraControllers[i].setContext(activity);
                }
                Log.i(TAG, "Initialized Player " + (i + 2) + " with: " + describeDevice(extraDevice));
            } else {
                Log.i(TAG, "Player " + (i + 2) + " has no assigned connected controller");
            }
            setGamepadSlotConnected(i + 1, extraControllers[i] != null);
        }

        if (clearDisconnectedSlots) {
            clearDisconnectedGamepadSlots();
            sendGamepadState();
        }
    }

    public void reassertPrimaryController() {
        controllerManager.scanForDevices();
        InputDevice p1Device = controllerManager.getAssignedDeviceForSlot(0);
        if (p1Device == null) return;
        ExternalController c = ExternalController.getController(p1Device.getId());
        if (c != null) {
            c.setContext(activity);
            currentController = c;
        }
    }

    private ExternalController getControllerFromSlot(int slot){
        if (slot == 0) return currentController;
        if (slot < 0 || slot >= MAX_PLAYERS) return null;

        return extraControllers[slot -1];
    }

    private MappedByteBuffer getGamepadBuffer(int slot) {
        if (slot == 0) return gamepadBuffer;
        if (slot < 0 || slot >= MAX_PLAYERS) return null;

        return extraGamepadBuffers[slot -1];
    }

    private void clearDisconnectedGamepadSlots() {
        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
            if (getControllerFromSlot(slot) == null && !isVirtualGamepadSlot(slot)) {
                clearGamepadSlot(slot);
            }
        }
    }

    private boolean isVirtualGamepadSlot(int slot) {
        return slot == 0 && isVirtualGamepadActive();
    }

    private boolean isVirtualGamepadActive() {
        if (inputControlsView == null) {
            return false;
        }
        ControlsProfile profile = inputControlsView.getProfile();
        return profile != null
                && profile.isVirtualGamepad()
                && inputControlsView.isShowTouchscreenControls()
                && inputControlsView.getVisibility() == View.VISIBLE;
    }

    private void clearGamepadSlot(int slot) {
        MappedByteBuffer buffer = getGamepadBuffer(slot);
        if (buffer == null) {
            return;
        }

        writeNeutralGamepadState(buffer);
        buffer.putInt(OFF_CONNECTED, 0);
        notifyStateChanged(slot);
        stopVibration(slot);
        lastLowFreq[slot] = 0;
        lastHighFreq[slot] = 0;
        rumbleDeviceIds[slot] = -1;
        Log.i(TAG, "Cleared disconnected Player " + (slot + 1) + " gamepad state");
    }

    private void setGamepadSlotConnected(int slot, boolean connected) {
        MappedByteBuffer buffer = getGamepadBuffer(slot);
        if (buffer == null) {
            return;
        }
        if (connected && buffer.getInt(OFF_CONNECTED) == 0) {
            writeNeutralGamepadState(buffer);
        }
        buffer.putInt(OFF_CONNECTED, connected ? 1 : 0);
        notifyStateChanged(slot);
        Log.i(TAG, "Player " + (slot + 1) + " connected=" + connected);
    }

    private void writeNeutralGamepadState(MappedByteBuffer buffer) {
        for (int offset = OFF_LX; offset < OFF_RUMBLE_LOW; offset++) {
            buffer.put(offset, (byte)0);
        }
        buffer.putShort(OFF_LT, (short)-32767);
        buffer.putShort(OFF_RT, (short)-32767);
    }

    private boolean sendPacket(int port) {
        try {
            int size = this.sendData.position();
            if (size == 0) {
                return false;
            }
            this.sendPacket.setAddress(this.localhost);
            this.sendPacket.setPort(port);
            this.socket.send(this.sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean sendPacket(int port, byte[] data) {
        try {
            DatagramPacket sendPacket = new DatagramPacket(data, data.length);
            sendPacket.setAddress(this.localhost);
            sendPacket.setPort(port);
            this.socket.send(sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void exec(String command) {
        String command2 = command.trim();
        if (command2.isEmpty()) {
            return;
        }
        String[] cmdList = command2.split(" ", 2);
        final String filename = cmdList[0];
        final String parameters = cmdList.length > 1 ? cmdList[1] : "";
        addAction(() -> {
            byte[] filenameBytes = filename.getBytes();
            byte[] parametersBytes = parameters.getBytes();
            this.sendData.rewind();
            this.sendData.put(RequestCodes.EXEC);
            this.sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            this.sendData.putInt(filenameBytes.length);
            this.sendData.putInt(parametersBytes.length);
            this.sendData.put(filenameBytes);
            this.sendData.put(parametersBytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void killProcess(String processName) {
        killProcess(processName, 0);
    }

    public void killProcess(final String processName, final int pid) {
        addAction(() -> {
            this.sendData.rewind();
            this.sendData.put(RequestCodes.KILL_PROCESS);
            if (processName == null) {
                this.sendData.putInt(0);
            } else {
                byte[] bytes = processName.getBytes();
                int minLength = Math.min(bytes.length, 55);
                this.sendData.putInt(minLength);
                this.sendData.put(bytes, 0, minLength);
            }
            this.sendData.putInt(pid);
            sendPacket(CLIENT_PORT);
        });
    }

    public void listProcesses() {
        addAction(() -> {
            OnGetProcessInfoListener onGetProcessInfoListener;
            this.sendData.rewind();
            this.sendData.put(RequestCodes.LIST_PROCESSES);
            this.sendData.putInt(0);
            if (!sendPacket(CLIENT_PORT) && (onGetProcessInfoListener = this.onGetProcessInfoListener) != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
        });
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(() -> {
            byte[] bytes = processName.getBytes();
            this.sendData.rewind();
            this.sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            this.sendData.putInt(bytes.length + 9);
            this.sendData.putInt(0);
            this.sendData.putInt(affinityMask);
            this.sendData.put((byte)bytes.length);
            this.sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9);
            sendData.putInt(pid);
            sendData.putInt(affinityMask);
            sendData.put((byte)0);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(final int flags, final int dx, final int dy, final int wheelDelta) {
        if (this.initReceived) {
            addAction(() -> {
                this.sendData.rewind();
                this.sendData.put(RequestCodes.MOUSE_EVENT);
                this.sendData.putInt(10);
                this.sendData.putInt(flags);
                this.sendData.putShort((short) dx);
                this.sendData.putShort((short) dy);
                this.sendData.putShort((short) wheelDelta);
                this.sendData.put((byte) ((flags & MouseEventFlags.MOVE) != 0 ? 1 : 0)); // cursor pos feedback
                sendPacket(CLIENT_PORT);
            });
        }
    }

    public void keyboardEvent(byte vkey, int flags) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KEYBOARD_EVENT);
            sendData.put(vkey);
            sendData.putInt(flags);
            sendPacket(CLIENT_PORT);
        });
    }

    public void bringToFront(String processName) {
        bringToFront(processName, 0L);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(() -> {
            this.sendData.rewind();
            this.sendData.put(RequestCodes.BRING_TO_FRONT);
            byte[] bytes = processName.getBytes();
            int minLength = Math.min(bytes.length, 51);
            this.sendData.putInt(minLength);
            this.sendData.put(bytes, 0, minLength);
            this.sendData.putLong(handle);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setClipboardData(final String data) {
        addAction(() -> {
            this.sendData.rewind();
            byte[] bytes = data.getBytes();
            this.sendData.put((byte) 14);
            this.sendData.putInt(bytes.length);
            if (sendPacket(7946)) {
                sendPacket(7946, bytes);
            }
        });
    }

    private void addAction(Runnable action) {
        synchronized (this.actions) {
            this.actions.add(action);
            this.actions.notify();
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (this.actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (this.running) {
                synchronized (this.actions) {
                    while (this.initReceived && !this.actions.isEmpty()) {
                        this.actions.poll().run();
                    }
                    try {
                        this.actions.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        });
    }

    public void stop() {
        this.running = false;
        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
            rumbleTeardown(slot);
        }
        if (rumbleKeepaliveThread != null) rumbleKeepaliveThread.interrupt();
        try {
            if (rumblePollerThreads != null && rumblePollerThreads.length > 0) {
                for (Thread t : rumblePollerThreads) {
                    if (t != null) {
                        t.join();
                    }
                }
            }
            if (rumbleKeepaliveThread != null)
                this.rumbleKeepaliveThread.join();
        } catch (InterruptedException ignored) {
        }
        // Cancel any in-flight one-shot so the device doesn't keep vibrating after shutdown.
        stopAllVibration();
        DatagramSocket datagramSocket = this.socket;
        if (datagramSocket != null) {
            datagramSocket.close();
            this.socket = null;
        }
        try {
            if (gamepadRaf != null) {
                gamepadRaf.close();
                gamepadRaf = null;
            }
            for (int i = 0; i < extraGamepadRafs.length; i++) {
                if (extraGamepadRafs[i] != null) {
                    extraGamepadRafs[i].close();
                    extraGamepadRafs[i] = null;
                }
            }
        } catch (IOException ignored) {
        }
        synchronized (this.actions) {
            this.actions.notify();
        }
    }

    private void handleRequest(byte requestCode, final int port) throws IOException {
        boolean enabled = true;
        ExternalController externalController;
        switch (requestCode) {
            case RequestCodes.INIT:
                this.initReceived = true;
                synchronized (this.actions) {
                    this.actions.notify();
                }
                return;
            case RequestCodes.GET_PROCESS:
                if (this.onGetProcessInfoListener == null) {
                    return;
                }
                ByteBuffer byteBuffer = this.receiveData;
                byteBuffer.position(byteBuffer.position() + 4);
                int numProcesses = this.receiveData.getShort();
                int index = this.receiveData.getShort();
                int pid = this.receiveData.getInt();
                long memoryUsage = this.receiveData.getLong();
                int affinityMask = this.receiveData.getInt();
                boolean wow64Process = this.receiveData.get() == 1;
                byte[] bytes = new byte[32];
                this.receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);
                this.onGetProcessInfoListener.onGetProcessInfo(index, numProcesses, new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64Process));
                return;
            case RequestCodes.GET_GAMEPAD:
                boolean isXInput = this.receiveData.get() == 1;
                boolean notify = this.receiveData.get() == 1;
                final ControlsProfile profile = inputControlsView.getProfile();
                final boolean useVirtualGamepad = inputControlsView != null && profile != null && profile.isVirtualGamepad();
                int processId = this.receiveData.getInt();
                if (!useVirtualGamepad && ((externalController = this.currentController) == null || !externalController.isConnected())) {
                    this.currentController = ExternalController.getController(0);
                }
                boolean enabled2 = this.currentController != null || useVirtualGamepad;
                if (enabled2) {
                    switch (this.preferredInputApi) {
                        case DINPUT:
                            boolean hasXInputProcess = this.xinputProcesses.contains(Integer.valueOf(processId));
                            if (isXInput) {
                                if (!hasXInputProcess) {
                                    this.xinputProcesses.add(Integer.valueOf(processId));
                                    break;
                                }
                            } else if (hasXInputProcess) {
                                enabled = false;
                                break;
                            }
                            break;
                        case XINPUT:
                            if (isXInput) {
                                enabled = false;
                                break;
                            }
                            break;
                        case BOTH:
                            if (!isXInput) {
                                enabled = false;
                                break;
                            }
                            break;
                    }
                    if (notify) {
                        if (!this.gamepadClients.contains(Integer.valueOf(port))) {
                            this.gamepadClients.add(Integer.valueOf(port));
                        }
                    } else {
                        this.gamepadClients.remove(Integer.valueOf(port));
                    }
                    final boolean finalEnabled = enabled;
                    addAction(() -> {
                        this.sendData.rewind();
                        this.sendData.put((byte) RequestCodes.GET_GAMEPAD);
                        if (finalEnabled) {
                            this.sendData.putInt(!useVirtualGamepad ? this.currentController.getDeviceId() : profile.id);
                            this.sendData.put(this.dinputMapperType);
                            String originalName = (useVirtualGamepad ? profile.getName() : currentController.getName());
                            byte[] originalBytes = originalName.getBytes();
                            final int MAX_NAME_LENGTH = 54;
                            byte[] bytesToWrite;
                            if (originalBytes.length > MAX_NAME_LENGTH) {
                                Log.w("WinHandler", "Controller name is too long ("+originalBytes.length+" bytes), truncating: "+originalName);
                                bytesToWrite = new byte[MAX_NAME_LENGTH];
                                System.arraycopy(originalBytes, 0, bytesToWrite, 0, MAX_NAME_LENGTH);
                            } else {
                                bytesToWrite = originalBytes;
                            }
                            sendData.putInt(bytesToWrite.length);
                            sendData.put(bytesToWrite);
                        } else {
                            this.sendData.putInt(0);
                            this.sendData.put((byte) 0);
                            this.sendData.putInt(0);
                        }
                        sendPacket(port);
                    });
                    return;
                }
                enabled = enabled2;
                if (!enabled) {
                }
                this.gamepadClients.remove(Integer.valueOf(port));
                final boolean finalEnabled2 = enabled;
                addAction(() -> {
                    this.sendData.rewind();
                    this.sendData.put((byte) 8);
                    if (finalEnabled2) {
                        this.sendData.putInt(!useVirtualGamepad ? this.currentController.getDeviceId() : profile.id);
                        this.sendData.put(this.dinputMapperType);
                        byte[] bytes2 = (useVirtualGamepad ? profile.getName() : this.currentController.getName()).getBytes();
                        this.sendData.putInt(bytes2.length);
                        this.sendData.put(bytes2);
                    } else {
                        this.sendData.putInt(0);
                        this.sendData.put((byte) 0);
                        this.sendData.putInt(0);
                    }
                    sendPacket(port);
                });
                return;
            case RequestCodes.GET_GAMEPAD_STATE:
                final int gamepadId = this.receiveData.getInt();
                final ControlsProfile profile2 = inputControlsView.getProfile();
                final boolean useVirtualGamepad2 = inputControlsView != null && profile2 != null && profile2.isVirtualGamepad();
                ExternalController externalController2 = this.currentController;
                final boolean enabled3 = externalController2 != null || useVirtualGamepad2;
                if (externalController2 != null && externalController2.getDeviceId() != gamepadId) {
                    this.currentController = null;
                }
                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    this.sendData.put((byte)(enabled3 ? 1 : 0));
                    if (enabled3) {
                        this.sendData.putInt(gamepadId);
                        if (useVirtualGamepad2) {
                            inputControlsView.getProfile().getGamepadState().writeTo(this.sendData);
                        } else {
                            this.currentController.state.writeTo(this.sendData);
                        }
                    }
                    sendPacket(port);
                });
                return;
            case RequestCodes.RELEASE_GAMEPAD:
                this.currentController = null;
                this.gamepadClients.clear();
                this.xinputProcesses.clear();
                return;
            case RequestCodes.CURSOR_POS_FEEDBACK:
                short x = this.receiveData.getShort();
                short y = this.receiveData.getShort();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                xServerView.requestRender();
                return;
            default:
                return;
        }
    }

    public void setCurrentController(int deviceId) {
        if (currentControllerId != deviceId) {
            Log.d(TAG, "setCurrentController deviceId=" + deviceId);
            this.currentControllerId = deviceId;
        }
    }

    public void start() {
        try {
            this.localhost = InetAddress.getLocalHost();
            Context context = activity.getApplicationContext();
            File gamepadShmDir = new File(
                    context.getFilesDir(),
                    "gamepad_shm"
            );

            if (!gamepadShmDir.exists() && !gamepadShmDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + gamepadShmDir.getAbsolutePath());
            }

            File p1_memFile = new File(gamepadShmDir, "gamepad.mem");
            if (gamepadBuffer == null) {
                gamepadRaf = new RandomAccessFile(p1_memFile, "rw");
                gamepadRaf.setLength(64);
                gamepadBuffer = gamepadRaf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                gamepadBuffer.order(ByteOrder.LITTLE_ENDIAN);
                Log.i(TAG, "Successfully created and mapped gamepad file for Player 1");
            }

            for (int i = 0; i < extraGamepadBuffers.length; i++) {
                File extra_mem_path = new File(gamepadShmDir, "gamepad" + (i + 1) + ".mem");
                if (extraGamepadBuffers[i] != null) continue;
                extraGamepadRafs[i] = new RandomAccessFile(extra_mem_path, "rw");
                extraGamepadRafs[i].setLength(64);
                extraGamepadBuffers[i] = extraGamepadRafs[i].getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                extraGamepadBuffers[i].order(ByteOrder.LITTLE_ENDIAN);
            }
        } catch (IOException e) {
            Log.e("EVSHIM_HOST", "FATAL: Failed to create memory-mapped file(s).", e);
            try {
                this.localhost = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException e2) {
            }
        }
        refreshControllerMappings();
        this.running = true;
        startSendThread();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                DatagramSocket datagramSocket = new DatagramSocket((SocketAddress) null);
                this.socket = datagramSocket;
                datagramSocket.setReuseAddress(true);
                this.socket.bind(new InetSocketAddress((InetAddress) null, SERVER_PORT));
                while (this.running) {
                    this.socket.receive(this.receivePacket);
                    synchronized (this.actions) {
                        this.receiveData.rewind();
                        byte requestCode = this.receiveData.get();
                        handleRequest(requestCode, this.receivePacket.getPort());
                    }
                }
            } catch (IOException ignored) {
            }
        });
        startRumblePoller();
    }

    private void startRumblePoller() {
        if (rumblePollerThreads == null || rumblePollerThreads.length != MAX_PLAYERS) {
            rumblePollerThreads = new Thread[MAX_PLAYERS];
        }
        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
           final int sl = slot;
            Thread thread = new Thread(() -> {
                int curSeq = 0;
                int lastSeq = 0;
                while (running) {
                    try {
                        // Blocks until the game issues a new rumble command (or teardown wakes us).
                        curSeq = WinHandler.waitForRumble(sl, lastSeq);
                        if (!running) break;
                        if (curSeq == lastSeq) {
                            continue;
                        }

                        lastSeq = curSeq;
                        MappedByteBuffer buffer = getGamepadBuffer(sl);
                        if (buffer == null) {
                            continue;
                        }

                        // Read the rumble values from the shared memory file after change was signaled or timeout happened
                        short lowFreq = buffer.getShort(OFF_RUMBLE_LOW);
                        short highFreq = buffer.getShort(OFF_RUMBLE_HIGH);

                        synchronized (rumbleLock) {
                            ExternalController controller = getControllerFromSlot(sl);
                            int deviceId = controller != null ? controller.getDeviceId() : -1;
                            if (rumbleDeviceIds[sl] != deviceId) {
                                if (isRumbling[sl]) {
                                    stopVibration(sl);
                                }
                                rumbleDeviceIds[sl] = deviceId;
                            }

                            // Check if the rumble state has changed
                            if (lowFreq != lastLowFreq[sl] || highFreq != lastHighFreq[sl]) {
                                lastLowFreq[sl] = lowFreq;
                                lastHighFreq[sl] = highFreq;
                                if (lowFreq == 0 && highFreq == 0) {
                                    stopVibration(sl);
                                } else {
                                    startVibration(sl, lowFreq, highFreq);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                 }
            }, "rumble-poller-" + sl);
            thread.start();
            rumblePollerThreads[sl] = thread;
        }
        startRumbleKeepalive();
    }

    /**
     * waitForRumble() blocks until the game issues a NEW rumble command, so the pollers cannot
     * refresh a sustained rumble — the motor one-shot would lapse (~1s) while the game holds a
     * constant value. This thread re-applies each slot's active rumble on a per-mode cadence so it
     * sustains. It is event-driven: it blocks on rumbleLock while idle and is woken by
     * startVibration(), so it does not poll when nothing is rumbling. Re-issuing the same constant
     * amplitude is seamless.
     */
    private void startRumbleKeepalive() {
        rumbleKeepaliveThread = new Thread(() -> {
            synchronized (rumbleLock) {
                while (running) {
                    try {
                        boolean anyRumbling = false;
                        for (int sl = 0; sl < MAX_PLAYERS; sl++) {
                            if (isRumbling[sl] && (lastLowFreq[sl] != 0 || lastHighFreq[sl] != 0)) {
                                anyRumbling = true;
                                break;
                            }
                        }
                        if (!anyRumbling) {
                            // Idle: block until a rumble starts (woken by startVibration). No polling.
                            rumbleLock.wait();
                        } else {
                            // Active: re-arm each mode's one-shot shortly before it expires.
                            long interval = "device".equals(vibrationMode)
                                    ? DEVICE_RUMBLE_REFRESH_MS : RUMBLE_KEEPALIVE_MS;
                            rumbleLock.wait(interval);
                            for (int sl = 0; sl < MAX_PLAYERS; sl++) {
                                if (isRumbling[sl] && (lastLowFreq[sl] != 0 || lastHighFreq[sl] != 0)) {
                                    startVibration(sl, lastLowFreq[sl], lastHighFreq[sl]);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        rumbleKeepaliveThread.setName("rumble-keepalive");
        rumbleKeepaliveThread.start();
    }

    /** Sets the vibration routing mode (off/controller/device), normalizing and validating input. */
    public void setVibrationMode(String mode) {
        String newMode;
        if (mode == null) {
            newMode = DEFAULT_VIBRATION_MODE;
        } else {
            String normalized = mode.trim().toLowerCase(Locale.US);
            newMode = VALID_VIBRATION_MODES.contains(normalized) ? normalized : DEFAULT_VIBRATION_MODE;
        }
        if (newMode.equals(this.vibrationMode)) return;
        this.vibrationMode = newMode;
        reconcileActiveRumble();
    }

    /** Sets the vibration intensity percentage, clamped to 0–100. */
    public void setVibrationIntensity(int intensity) {
        int clamped = Math.max(0, Math.min(100, intensity));
        if (clamped == this.vibrationIntensity) return;
        this.vibrationIntensity = clamped;
        reconcileActiveRumble();
    }

    /**
     * Apply a mode/intensity change immediately. Cancels any in-flight vibration, then proactively
     * re-applies the rumble currently in shared memory under the new settings — the pollers only wake
     * on a NEW game rumble command, so a sustained rumble would otherwise stay stale until the game
     * next changed it.
     */
    private void reconcileActiveRumble() {
        synchronized (rumbleLock) {
            for (int sl = 0; sl < MAX_PLAYERS; sl++) {
                if (isRumbling[sl]) stopVibration(sl);
                lastLowFreq[sl] = 0;
                lastHighFreq[sl] = 0;
            }
            if ("off".equals(vibrationMode) || vibrationIntensity == 0) return;
            for (int sl = 0; sl < MAX_PLAYERS; sl++) {
                MappedByteBuffer buffer = getGamepadBuffer(sl);
                if (buffer == null) continue;
                try {
                    short low = buffer.getShort(OFF_RUMBLE_LOW);
                    short high = buffer.getShort(OFF_RUMBLE_HIGH);
                    if (low != 0 || high != 0) {
                        lastLowFreq[sl] = low;
                        lastHighFreq[sl] = high;
                        startVibration(sl, low, high);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "reconcileActiveRumble: failed to re-apply P" + (sl + 1) + " rumble", e);
                }
            }
        }
    }

    /** Cancels vibration on every player slot. */
    private void stopAllVibration() {
        synchronized (rumbleLock) {
            for (int sl = 0; sl < MAX_PLAYERS; sl++) {
                stopVibration(sl);
            }
        }
    }

    /** Converts a raw 16-bit XInput rumble value (0–65535) to a 0–255 amplitude, scaled by intensity percent. */
    private int scaleAmplitude(short rawFreq, int intensityPercent) {
        int unsigned = rawFreq & 0xFFFF;
        if (unsigned == 0 || intensityPercent == 0) return 0;
        // Map full 16-bit range to 1–255 so any non-zero game rumble produces a non-zero
        // amplitude, then scale by the user's intensity preference.
        int base = (int) Math.round(unsigned * 255.0 / 65535.0);
        return Math.min(255, Math.max(1, (base * intensityPercent) / 100));
    }

    /**
     * Collapses the low-freq (heavy) and high-freq (light) motor amplitudes into a single value
     * for one-vibrator targets, weighting the heavy motor. Floors to 1 (never silence) whenever
     * either motor is non-zero, so a high-freq-only rumble still vibrates.
     */
    private static int blendMotors(int lowAmp, int highAmp) {
        if (lowAmp <= 0 && highAmp <= 0) return 0;
        return Math.max(1, Math.min(255, (int) Math.round(lowAmp * 0.80 + highAmp * 0.33)));
    }

    /** Issues a one-shot vibration on a single vibrator, respecting amplitude-control availability. */
    private void vibrateSingle(Vibrator vibrator, int amplitude, int durationMs) {
        if (amplitude <= 0) { vibrator.cancel(); return; }
        int amp = Math.min(255, amplitude);
        int finalAmp = vibrator.hasAmplitudeControl() ? amp : VibrationEffect.DEFAULT_AMPLITUDE;
        VibrationEffect effect = VibrationEffect.createOneShot(durationMs, finalAmp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vibrationAttrs != null) {
            vibrator.vibrate(effect, vibrationAttrs);
        } else {
            vibrator.vibrate(effect, AUDIO_ATTRS_GAME);
        }
    }

    /**
     * Drives per-motor rumble through VibratorManager (API 31+). Sorts vibrator IDs ascending so the
     * low-freq (heavy/left) and high-freq (light/right) motor selection is well-defined; falls back to
     * a blended single-motor vibration when only one vibrator is available.
     */
    @TargetApi(31)
    private boolean rumbleViaVibratorManager(VibratorManager vm, short lowFreq, short highFreq) {
        int[] ids = vm.getVibratorIds();
        if (ids.length == 0) return false;

        int highAmp = scaleAmplitude(highFreq, vibrationIntensity);
        int lowAmp  = scaleAmplitude(lowFreq,  vibrationIntensity);
        if (lowAmp == 0 && highAmp == 0) { vm.cancel(); return true; }

        Arrays.sort(ids);
        int lowMotorId  = ids[0];
        int highMotorId = ids.length >= 2 ? ids[1] : ids[0];

        if (ids.length >= 2) {
            String motorKey = lowMotorId + "_" + highMotorId;
            if (loggedRumbleMotorIds.add(motorKey)) {
                Log.d(TAG, "Rumble motors: lowMotor=" + lowMotorId + " highMotor=" + highMotorId);
            }
        }

        CombinedVibration.ParallelCombination combo = CombinedVibration.startParallel();
        boolean anyAdded = false;

        if (ids.length >= 2) {
            if (lowAmp > 0) {
                int a = vm.getVibrator(lowMotorId).hasAmplitudeControl() ? lowAmp : VibrationEffect.DEFAULT_AMPLITUDE;
                combo.addVibrator(lowMotorId, VibrationEffect.createOneShot(CONTROLLER_RUMBLE_MS, a));
                anyAdded = true;
            }
            if (highAmp > 0) {
                int a = vm.getVibrator(highMotorId).hasAmplitudeControl() ? highAmp : VibrationEffect.DEFAULT_AMPLITUDE;
                combo.addVibrator(highMotorId, VibrationEffect.createOneShot(CONTROLLER_RUMBLE_MS, a));
                anyAdded = true;
            }
        } else {
            int blended = blendMotors(lowAmp, highAmp);
            if (blended > 0) {
                int a = vm.getVibrator(ids[0]).hasAmplitudeControl() ? blended : VibrationEffect.DEFAULT_AMPLITUDE;
                combo.addVibrator(ids[0], VibrationEffect.createOneShot(CONTROLLER_RUMBLE_MS, a));
                anyAdded = true;
            }
        }

        if (!anyAdded) { vm.cancel(); return true; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vibrationAttrs != null) {
            vm.vibrate(combo.combine(), vibrationAttrs);
        } else {
            vm.vibrate(combo.combine());
        }
        return true;
    }

    /** Resolves the physical InputDevice currently driving the given player slot, or null if none. */
    private InputDevice resolveInputDevice(int slot) {
        int deviceId = slot >= 0 && slot < MAX_PLAYERS ? rumbleDeviceIds[slot] : -1;
        if (deviceId < 0) {
            ExternalController controller = getControllerFromSlot(slot);
            if (controller != null) deviceId = controller.getDeviceId();
        }
        if (deviceId < 0) return null;
        return InputDevice.getDevice(deviceId);
    }

    /** Vibrates the slot's physical controller, trying VibratorManager (per-motor) first, then legacy Vibrator. */
    private boolean vibrateController(int slot, short lowFreq, short highFreq) {
        InputDevice device = resolveInputDevice(slot);
        if (device == null) {
            Log.w(TAG, "Rumble: no physical controller found for P" + (slot + 1));
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = device.getVibratorManager();
                if (rumbleViaVibratorManager(vm, lowFreq, highFreq)) {
                    return true;
                }
            }
            Vibrator v = device.getVibrator();
            if (v != null && v.hasVibrator()) {
                int lowMSB = scaleAmplitude(lowFreq, vibrationIntensity);
                int highMSB = scaleAmplitude(highFreq, vibrationIntensity);
                int blended = blendMotors(lowMSB, highMSB);
                vibrateSingle(v, blended, CONTROLLER_RUMBLE_MS);
                return true;
            }
            Log.w(TAG, "Rumble: no vibrators available on '" + device.getName() + "'");
        } catch (Exception e) {
            Log.e(TAG, "Rumble: exception vibrating controller", e);
        }
        return false;
    }

    /** Vibrates the Android device's built-in vibrator with a haptic-curved amplitude. Returns true if a vibration was issued. */
    private boolean vibrateDevice(short lowFreq, short highFreq) {
        try {
            int lowMSB = scaleAmplitude(lowFreq, vibrationIntensity);
            int highMSB = scaleAmplitude(highFreq, vibrationIntensity);
            int rawAmplitude = blendMotors(lowMSB, highMSB);

            Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (phoneVibrator == null || !phoneVibrator.hasVibrator()) return false;

            if (rawAmplitude <= 0) {
                phoneVibrator.cancel();
                return false;
            }
            float curved = (float) Math.pow((float) rawAmplitude / 255f, 0.6f);
            int amp = Math.max(1, Math.round(curved * 255));
            vibrateSingle(phoneVibrator, amp, DEVICE_RUMBLE_MS);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Rumble: exception vibrating device", e);
            return false;
        }
    }

    /** Routes a slot's rumble command to the controller or the phone based on the current vibration mode. */
    private void startVibration(int slot, short lowFreq, short highFreq) {
        if (slot < 0 || slot >= MAX_PLAYERS) return;
        synchronized (rumbleLock) {
            if ("off".equals(vibrationMode) || vibrationIntensity == 0) {
                stopVibration(slot);
                return;
            }
            boolean started = "device".equals(vibrationMode)
                    ? vibrateDevice(lowFreq, highFreq)
                    : vibrateController(slot, lowFreq, highFreq);
            if (started) {
                isRumbling[slot] = true;
                rumbleLock.notifyAll();  // wake the idle keepalive so it begins refreshing
            } else {
                // Nothing handled it (e.g. no controller present) — don't mark rumbling so the
                // keepalive won't retry, and clear any prior in-flight rumble.
                stopVibration(slot);
            }
        }
    }

    /** Cancels the slot's controller vibration, plus the phone vibrator once no slot is rumbling. */
    private void stopVibration(int slot) {
        if (slot < 0 || slot >= MAX_PLAYERS) return;
        synchronized (rumbleLock) {
            if (!isRumbling[slot]) return;
            isRumbling[slot] = false;
            try {
                InputDevice device = resolveInputDevice(slot);
                if (device != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibratorManager vm = device.getVibratorManager();
                        if (vm != null && vm.getVibratorIds().length > 0) {
                            vm.cancel();
                        } else {
                            Vibrator v = device.getVibrator();
                            if (v != null && v.hasVibrator()) v.cancel();
                        }
                    } else {
                        Vibrator v = device.getVibrator();
                        if (v != null && v.hasVibrator()) v.cancel();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error cancelling controller vibration", e);
            }
            // The phone vibrator is shared across slots (device mode), so only cancel it once
            // no slot is rumbling anymore.
            boolean anyRumbling = false;
            for (int sl = 0; sl < MAX_PLAYERS; sl++) {
                if (isRumbling[sl]) { anyRumbling = true; break; }
            }
            if (!anyRumbling) {
                try {
                    Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                    if (phoneVibrator != null) phoneVibrator.cancel();
                } catch (Exception e) {
                    Log.e(TAG, "Error cancelling device vibration", e);
                }
            }
        }
    }

    public void sendGamepadState() {
        if (!this.initReceived || this.gamepadClients.isEmpty()) {
            return;
        }
        final ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
        final boolean useVirtualGamepad = isVirtualGamepadActive();
        final boolean enabled = this.currentController != null || useVirtualGamepad;
        Iterator<Integer> it = this.gamepadClients.iterator();
        while (it.hasNext()) {
            final int port = it.next().intValue();
            addAction(() -> {
                this.sendData.rewind();
                sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                sendData.put((byte)(enabled ? 1 : 0));
                if (enabled) {
                    this.sendData.putInt(!useVirtualGamepad ? this.currentController.getDeviceId() : inputControlsView.getProfile().id);
                    if (useVirtualGamepad) {
                        inputControlsView.getProfile().getGamepadState().writeTo(sendData);
                    } else {
                        this.currentController.state.writeTo(this.sendData);
                    }
                }
                sendPacket(port);
            });
        }
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        int slot = controllerManager.getSlotForDevice(event.getDeviceId());
        if (slot >= 0) {
            ExternalController controller = getControllerFromSlot(slot);
            if (controller == null || controller.getDeviceId() != event.getDeviceId()) {
                Log.d(TAG, "Motion event refresh for deviceId=" + event.getDeviceId()
                        + " slot=" + slot
                        + " controller=" + (controller != null ? controller.getDeviceId() : -1));
                refreshControllerMappings();
                controller = getControllerFromSlot(slot);
            }
            if (controller != null && controller.getDeviceId() == event.getDeviceId()) {
                handled = controller.updateStateFromMotionEvent(event);
                if (handled) {
                    sendMemoryFileState(controller, getGamepadBuffer(slot), slot);
                    sendGamepadState();
                }
                return handled;
            }
        }

        ExternalController externalController = this.currentController;
        // Adopt newly connected controller if deviceId mismatches
        if ((externalController == null || externalController.getDeviceId() != event.getDeviceId()) && ExternalController.isJoystickDevice(event)) {
            ExternalController adopted = null;
            // Try to get controller from profile first (has saved bindings)
            if (inputControlsView != null) {
                ControlsProfile profile = inputControlsView.getProfile();
                if (profile != null) {
                    adopted = profile.getController(event.getDeviceId());
                }
            }
            // Fallback to creating new controller if profile doesn't have one
            if (adopted == null) {
                adopted = ExternalController.getController(event.getDeviceId());
            }
            if (adopted != null && "*".equals(adopted.getId())) {
                this.currentController = adopted;
                externalController = adopted;
                Timber.d("WinHandler.onGenericMotionEvent: adopted controller %s(#%d)", adopted.getName(), adopted.getDeviceId());
            }
        }
        if (externalController != null && externalController.getDeviceId() == event.getDeviceId() && (handled = this.currentController.updateStateFromMotionEvent(event))) {
            if (handled) {
                sendGamepadState();
                sendMemoryFileState();
            }
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        MappedByteBuffer buffer = null;
        boolean handled = false;
        ExternalController externalController = this.currentController;
        buffer = gamepadBuffer;
        int slot = controllerManager.getSlotForDevice(event.getDeviceId());

        // If this is a gamepad event but our controller is null or mismatched, adopt it
        InputDevice device = event.getDevice();

        if (slot >= 0) {
            ExternalController controller = getControllerFromSlot(slot);
            if (controller == null || controller.getDeviceId() != event.getDeviceId()) {
                Log.d(TAG, "Key event refresh for deviceId=" + event.getDeviceId()
                        + " slot=" + slot
                        + " controller=" + (controller != null ? controller.getDeviceId() : -1));
                refreshControllerMappings();
                controller = getControllerFromSlot(slot);
            }
            if (controller != null && controller.getDeviceId() == event.getDeviceId()) {
                handled = controller.updateStateFromKeyEvent(event); // or motion variant
                Log.d(TAG, "Key routed deviceId=" + event.getDeviceId()
                        + " keyCode=" + event.getKeyCode()
                        + " action=" + event.getAction()
                        + " -> P" + (slot + 1)
                        + " handled=" + handled
                        + " buffer=" + (getGamepadBuffer(slot) != null));
                sendMemoryFileState(controller, getGamepadBuffer(slot), slot);
                if (handled) sendGamepadState();
                return handled;
            }
        }

        if ((externalController == null || externalController.getDeviceId() != event.getDeviceId())
                && device != null && ExternalController.isGameController(device)
                && event.getRepeatCount() == 0) {
            ExternalController adopted = null;
            // Try to get controller from profile first (has saved bindings)
            if (inputControlsView != null) {
                ControlsProfile profile = inputControlsView.getProfile();
                if (profile != null) {
                    adopted = profile.getController(event.getDeviceId());
                }
            }
            // Fallback to creating new controller if profile doesn't have one
            if (adopted == null) {
                adopted = ExternalController.getController(event.getDeviceId());
            }
            if (adopted != null && "*".equals(adopted.getId())) {
                this.currentController = adopted;
                externalController = adopted;
                Timber.d("WinHandler.onKeyEvent: adopted controller %s(#%d)", adopted.getName(), adopted.getDeviceId());
            }
        }


        if (externalController != null && externalController.getDeviceId() == event.getDeviceId() && event.getRepeatCount() == 0) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN) {
                handled = this.currentController.updateStateFromKeyEvent(event);
            } else if (action == KeyEvent.ACTION_UP) {
                handled = this.currentController.updateStateFromKeyEvent(event);
            }
            sendMemoryFileState(this.currentController, buffer, 0);
            if (handled) {
                sendGamepadState();
            }
        }
        return handled;
    }

    public void setDInputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }

    public void setPreferredInputApi(PreferredInputApi preferredInputApi) {
        this.preferredInputApi = preferredInputApi;
    }

    public ExternalController getCurrentController() {
        return this.currentController;
    }


    private void sendMemoryFileState() {
        sendMemoryFileState(currentController, gamepadBuffer, 0);
    }

    private void sendMemoryFileState(ExternalController controller, MappedByteBuffer buffer, int slot) {
        if (buffer == null || controller == null) {
            return;
        }
        GamepadState state = controller.state;
        buffer.putInt(OFF_CONNECTED, 1);

        buffer.putShort(OFF_LX, (short)(state.thumbLX * 32767));
        buffer.putShort(OFF_LY, (short)(state.thumbLY * 32767));
        buffer.putShort(OFF_RX, (short)(state.thumbRX * 32767));
        buffer.putShort(OFF_RY, (short)(state.thumbRY * 32767));
        // Clamp the raw value first – some firmwares report 1.00–1.02 at the top end
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));
        float lCurve = (float)Math.sqrt(rawL);
        float rCurve = (float)Math.sqrt(rawR);
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;  // 0 → -32 767, 1 → 32 767
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;
        buffer.putShort(OFF_LT, (short)lAxis);
        buffer.putShort(OFF_RT, (short)rAxis);

        byte[] sdlButtons = new byte[15];
        sdlButtons[0] = state.isPressed(0) ? (byte)1 : (byte)0;  // A
        sdlButtons[1] = state.isPressed(1) ? (byte)1 : (byte)0;  // B
        sdlButtons[2] = state.isPressed(2) ? (byte)1 : (byte)0;  // X
        sdlButtons[3] = state.isPressed(3) ? (byte)1 : (byte)0;  // Y
        sdlButtons[9] = state.isPressed(4) ? (byte)1 : (byte)0;  // Left Bumper
        sdlButtons[10] = state.isPressed(5) ? (byte)1 : (byte)0; // Right Bumper
        sdlButtons[4] = state.isPressed(6) ? (byte)1 : (byte)0;  // Select/Back
        sdlButtons[6] = state.isPressed(7) ? (byte)1 : (byte)0;  // Start
        sdlButtons[7] = state.isPressed(8) ? (byte)1 : (byte)0;  // Left Stick
        sdlButtons[8] = state.isPressed(9) ? (byte)1 : (byte)0;  // Right Stick
        sdlButtons[11] = state.dpad[0] ? (byte)1 : (byte)0;      // DPAD_UP
        sdlButtons[12] = state.dpad[2] ? (byte)1 : (byte)0;      // DPAD_DOWN
        sdlButtons[13] = state.dpad[3] ? (byte)1 : (byte)0;      // DPAD_LEFT
        sdlButtons[14] = state.dpad[1] ? (byte)1 : (byte)0;      // DPAD_RIGHT
        for (int i = 0; i < 15; i++) {
            buffer.put(OFF_BTN + i, sdlButtons[i]);
        }
        buffer.put(OFF_HAT, (byte)0);

        notifyStateChanged(slot);
    }

    public void sendVirtualGamepadState(GamepadState state, int slot) {
        MappedByteBuffer buffer = getGamepadBuffer(slot);
        if (buffer == null || state == null) {
            return;
        }
        buffer.putInt(OFF_CONNECTED, 1);

        // Axes: write by fixed offsets, not sequential position
        buffer.putShort(OFF_LX, (short) (state.thumbLX * 32767));
        buffer.putShort(OFF_LY, (short) (state.thumbLY * 32767));
        buffer.putShort(OFF_RX, (short) (state.thumbRX * 32767));
        buffer.putShort(OFF_RY, (short) (state.thumbRY * 32767));

        // Triggers: curve and map to signed short range like your current code
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));

        float lCurve = (float) Math.sqrt(rawL);
        float rCurve = (float) Math.sqrt(rawR);

        int lAxis = Math.round(lCurve * 65534f) - 32767;
        int rAxis = Math.round(rCurve * 65534f) - 32767;

        buffer.putShort(OFF_LT, (short) lAxis);
        buffer.putShort(OFF_RT, (short) rAxis);

        // Buttons: 15 bytes starting at offset 16
        byte[] sdlButtons = new byte[15];
        sdlButtons[0]  = state.isPressed(0) ? (byte) 1 : 0;   // A
        sdlButtons[1]  = state.isPressed(1) ? (byte) 1 : 0;   // B
        sdlButtons[2]  = state.isPressed(2) ? (byte) 1 : 0;   // X
        sdlButtons[3]  = state.isPressed(3) ? (byte) 1 : 0;   // Y
        sdlButtons[9]  = state.isPressed(4) ? (byte) 1 : 0;   // LB
        sdlButtons[10] = state.isPressed(5) ? (byte) 1 : 0;   // RB
        sdlButtons[4]  = state.isPressed(6) ? (byte) 1 : 0;   // Back / Select
        sdlButtons[6]  = state.isPressed(7) ? (byte) 1 : 0;   // Start
        sdlButtons[7]  = state.isPressed(8) ? (byte) 1 : 0;   // L3
        sdlButtons[8]  = state.isPressed(9) ? (byte) 1 : 0;   // R3
        sdlButtons[11] = state.dpad[0] ? (byte) 1 : 0;        // Up
        sdlButtons[12] = state.dpad[2] ? (byte) 1 : 0;        // Down
        sdlButtons[13] = state.dpad[3] ? (byte) 1 : 0;        // Left
        sdlButtons[14] = state.dpad[1] ? (byte) 1 : 0;        // Right

        for (int i = 0; i < 15; i++) {
            buffer.put(OFF_BTN + i, sdlButtons[i]);
        }

        // Hat at offset 31
        buffer.put(OFF_HAT, (byte) 0);

        // Notify native side that state changed
        notifyStateChanged(slot);
    }

    public void sendVirtualGamepadState(GamepadState state) {
        sendVirtualGamepadState(state, 0);
    }
}
