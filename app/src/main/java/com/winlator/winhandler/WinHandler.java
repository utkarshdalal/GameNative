package com.winlator.winhandler;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

// import com.winlator.XServerDisplayActivity;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.ControllerManager;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.JoyConSupport;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
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
    private static final long INIT_TIMEOUT_MS = 15000;
    private final ArrayDeque<Runnable> actions;
    private ExternalController currentController;
    private volatile int currentControllerId;
    private byte dinputMapperType;
    private final List<Integer> gamepadClients;
    // UDP gamepad state per client port. Guarded by actions.
    private final SparseArray<GamepadStatePort> gamepadStatePorts = new SparseArray<>();
    private volatile boolean initReceived;
    private Thread initTimeoutThread;
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
    private final short[] lastLowFreq = new short[MAX_PLAYERS];
    private final short[] lastHighFreq = new short[MAX_PLAYERS];
    private final boolean[] isRumbling = new boolean[MAX_PLAYERS];
    private final int[] rumbleDeviceIds = new int[MAX_PLAYERS];
    private final long[] controllerRumbleAppliedMs = new long[MAX_PLAYERS];
    private final int[] controllerRumbleAmplitude = new int[MAX_PLAYERS];
    private final Object rumbleLock = new Object();
    private Thread rumbleKeepaliveThread;
    private volatile int vibrationIntensity = 100;
    private long lastStandalonePhoneRumbleMs = 0;
    private boolean isShowingAssignDialog = false;
    private Context activity;
    private final java.util.Set<Integer> ignoredDeviceIds = new java.util.HashSet<>();
    private RandomAccessFile gamepadRaf;
    private RandomAccessFile[] extraGamepadRafs = new RandomAccessFile[MAX_PLAYERS - 1];

    private static volatile WinHandler activeInstance;

    /**
     * The handler of the running session, or null while no session is up. Published from
     * {@link #start()} so code outside the UI layer can reach the live handler.
     */
    public static WinHandler getActiveInstance() {
        return activeInstance;
    }

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
    private static final int CONTROLLER_RUMBLE_DURATION_MS = 10000;
    private static final int CONTROLLER_RUMBLE_REARM_MS = 9000;
    private static final int PHONE_RUMBLE_FALLBACK_DURATION_MS = 40;
    private static final int STANDALONE_PHONE_RUMBLE_DURATION_MS = 70;
    private static final int STANDALONE_PHONE_RUMBLE_THROTTLE_MS = 120;

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

    /**
     * UDP gamepad state sends to one client port. At most one is queued; a newer state updates it in place.
     * A packet identical to the last one sent to the port is skipped.
     */
    private final class GamepadStatePort implements Runnable {
        final int port;

        // Guarded by actions.
        boolean queued;
        boolean enabled;
        int deviceId;
        GamepadState state;
        boolean forgetLastSent;

        // Send thread only. lastSentSize is -1 when nothing has been sent.
        private final byte[] lastSent = new byte[64];
        private int lastSentSize = -1;

        GamepadStatePort(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            final boolean enabled;
            final int deviceId;
            final GamepadState state;
            synchronized (actions) {
                queued = false;
                if (gamepadStatePorts.get(port) != this) return;
                enabled = this.enabled;
                deviceId = this.deviceId;
                state = this.state;
                if (forgetLastSent) {
                    forgetLastSent = false;
                    lastSentSize = -1;
                }
            }
            sendData.rewind();
            sendData.put(RequestCodes.GET_GAMEPAD_STATE);
            sendData.put((byte)(enabled ? 1 : 0));
            if (enabled) {
                sendData.putInt(deviceId);
                state.writeTo(sendData);
            }
            if (isLastSent()) return;
            if (sendPacket(port)) rememberSent();
            else lastSentSize = -1;
        }

        /** Whether sendData equals the last packet sent to this port. */
        private boolean isLastSent() {
            int size = sendData.position();
            if (size != lastSentSize) return false;
            byte[] current = sendData.array();
            for (int i = 0; i < size; i++) {
                if (current[i] != lastSent[i]) return false;
            }
            return true;
        }

        /** Records sendData as the last packet sent to this port. */
        private void rememberSent() {
            lastSentSize = sendData.position();
            System.arraycopy(sendData.array(), 0, lastSent, 0, lastSentSize);
        }
    }

    /** Makes the next state send to the port go out even if unchanged. */
    private void forgetLastSentGamepadState(int port) {
        synchronized (this.actions) {
            GamepadStatePort statePort = this.gamepadStatePorts.get(port);
            if (statePort != null) statePort.forgetLastSent = true;
        }
    }

    /**
     * Records a GET_GAMEPAD_STATE reply, still in sendData, as the last packet sent to the port. It has the same
     * layout as a pushed state, so an unchanged push after it can be skipped.
     */
    private void onGamepadStateReplied(int port, boolean sent) {
        GamepadStatePort statePort;
        synchronized (this.actions) {
            statePort = this.gamepadStatePorts.get(port);
            // A pending reset takes precedence.
            if (statePort == null || statePort.forgetLastSent) return;
        }
        if (sent) statePort.rememberSent();
        else statePort.lastSentSize = -1;
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

    private boolean isEventFromController(ExternalController controller, int eventDeviceId) {
        if (controller == null) return false;
        if (controller.getDeviceId() == eventDeviceId) return true;
        InputDevice eventDevice = InputDevice.getDevice(eventDeviceId);
        InputDevice controllerDevice = InputDevice.getDevice(controller.getDeviceId());
        return JoyConSupport.isJoyCon(eventDevice)
                && JoyConSupport.isJoyCon(controllerDevice)
                && JoyConSupport.PAIRED_IDENTIFIER.equals(
                        ControllerManager.getDeviceIdentifier(eventDevice));
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
            Log.w(TAG, "WinHandler send failed (code " + this.sendData.get(0) + ")", e);
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
            boolean sent = sendPacket(CLIENT_PORT);
            if (!sent && (onGetProcessInfoListener = this.onGetProcessInfoListener) != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
            Log.d(TAG, "WinHandler listProcesses " + (sent ? "sent" : "not sent"));
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
        initTimeoutThread = new Thread(() -> {
            try {
                Thread.sleep(INIT_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this.actions) {
                if (!this.initReceived && this.running) {
                    Log.w(TAG, "WinHandler INIT not received within " + INIT_TIMEOUT_MS + " ms; sending anyway");
                    this.initReceived = true;
                    this.actions.notify();
                }
            }
        }, "WinHandler-init-timeout");
        initTimeoutThread.setDaemon(true);
        initTimeoutThread.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            while (this.running) {
                Runnable action;
                synchronized (this.actions) {
                    while (this.running && (!this.initReceived || this.actions.isEmpty())) {
                        try {
                            this.actions.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                    if (!this.running) break;
                    action = this.actions.poll();
                }
                // Run outside the lock so addAction() doesn't wait on socket.send().
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // Don't let one failed action stop the send thread.
                    Log.e(TAG, "WinHandler action failed", e);
                }
            }
        });
        // Let the thread end when the loop exits.
        executor.shutdown();
    }

    public void stop() {
        this.running = false;
        if (activeInstance == this) activeInstance = null;
        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
            rumbleTeardown(slot);
        }
        Thread keepaliveThread = rumbleKeepaliveThread;
        if (keepaliveThread != null) {
            keepaliveThread.interrupt();
        }
        Thread timeoutThread = initTimeoutThread;
        if (timeoutThread != null) {
            timeoutThread.interrupt();
        }
        try {
            if (rumblePollerThreads != null && rumblePollerThreads.length > 0) {
                for (Thread t : rumblePollerThreads) {
                    if (t != null) {
                        t.join();
                    }
                }
            }
            if (keepaliveThread != null) {
                keepaliveThread.join();
            }
        } catch (InterruptedException ignored) {
        }
        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
            stopVibration(slot);
        }
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
                Log.i(TAG, "WinHandler INIT received from port " + port);
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
                forgetLastSentGamepadState(port);
                boolean isXInput = this.receiveData.get() == 1;
                boolean notify = this.receiveData.get() == 1;
                final ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
                final boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
                int processId = this.receiveData.getInt();
                if (!useVirtualGamepad && ((externalController = this.currentController) == null || !externalController.isConnected())) {
                    this.currentController = ExternalController.getController(0);
                }
                // Read once: the action runs later, when currentController may have changed.
                final ExternalController replyController = this.currentController;
                boolean enabled2 = replyController != null || useVirtualGamepad;
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
                        this.gamepadStatePorts.remove(port);
                    }
                    final boolean finalEnabled = enabled;
                    addAction(() -> {
                        this.sendData.rewind();
                        this.sendData.put((byte) RequestCodes.GET_GAMEPAD);
                        if (finalEnabled) {
                            this.sendData.putInt(!useVirtualGamepad ? replyController.getDeviceId() : profile.id);
                            this.sendData.put(this.dinputMapperType);
                            String originalName = (useVirtualGamepad ? profile.getName() : replyController.getName());
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
                this.gamepadStatePorts.remove(port);
                final boolean finalEnabled2 = enabled;
                addAction(() -> {
                    this.sendData.rewind();
                    this.sendData.put((byte) 8);
                    if (finalEnabled2) {
                        this.sendData.putInt(!useVirtualGamepad ? replyController.getDeviceId() : profile.id);
                        this.sendData.put(this.dinputMapperType);
                        byte[] bytes2 = (useVirtualGamepad ? profile.getName() : replyController.getName()).getBytes();
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
                final ControlsProfile profile2 = inputControlsView != null ? inputControlsView.getProfile() : null;
                final boolean useVirtualGamepad2 = profile2 != null && profile2.isVirtualGamepad();
                ExternalController externalController2 = this.currentController;
                final boolean enabled3 = useVirtualGamepad2
                        || (externalController2 != null && externalController2.getDeviceId() == gamepadId);
                if (externalController2 != null && externalController2.getDeviceId() != gamepadId) {
                    this.currentController = null;
                }
                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    this.sendData.put((byte)(enabled3 ? 1 : 0));
                    if (enabled3) {
                        this.sendData.putInt(gamepadId);
                        // Use what was read with the request: currentController may be null by now.
                        if (useVirtualGamepad2) {
                            profile2.getGamepadState().writeTo(this.sendData);
                        } else {
                            externalController2.state.writeTo(this.sendData);
                        }
                    }
                    onGamepadStateReplied(port, sendPacket(port));
                });
                return;
            case RequestCodes.RELEASE_GAMEPAD:
                this.currentController = null;
                this.gamepadClients.clear();
                this.xinputProcesses.clear();
                this.gamepadStatePorts.clear();
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
        Log.i(TAG, "WinHandler start: localhost=" + this.localhost);
        this.running = true;
        activeInstance = this;
        startSendThread();
        try {
            DatagramSocket datagramSocket = new DatagramSocket((SocketAddress) null);
            this.socket = datagramSocket;
            datagramSocket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress((InetAddress) null, SERVER_PORT));
            Log.i(TAG, "WinHandler bound to " + this.socket.getLocalSocketAddress());
        } catch (IOException e) {
            Log.e(TAG, "WinHandler bind failed", e);
            DatagramSocket failed = this.socket;
            this.socket = null;
            if (failed != null) failed.close();
        }
        ExecutorService receiveExecutor = Executors.newSingleThreadExecutor();
        receiveExecutor.execute(() -> {
            try {
                while (this.running && this.socket != null) {
                    this.socket.receive(this.receivePacket);
                    synchronized (this.actions) {
                        this.receiveData.rewind();
                        byte requestCode = this.receiveData.get();
                        try {
                            handleRequest(requestCode, this.receivePacket.getPort());
                        } catch (RuntimeException e) {
                            // Don't let one bad request stop the receive thread.
                            Log.e(TAG, "WinHandler request " + requestCode + " failed", e);
                        }
                    }
                }
            } catch (IOException e) {
                if (this.running) Log.e(TAG, "WinHandler receive loop ended", e);
            }
        });
        receiveExecutor.shutdown();
        startRumblePoller();
        startRumbleKeepalive();
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
                    } catch (Exception ignored) {
                    }
                 }
            }, "rumble-poller-" + sl);
            thread.start();
            rumblePollerThreads[sl] = thread;
        }
    }

    private void startRumbleKeepalive() {
        rumbleKeepaliveThread = new Thread(() -> {
            synchronized (rumbleLock) {
                while (running) {
                    try {
                        long nextDeadline = Long.MAX_VALUE;
                        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
                            if (controllerRumbleAppliedMs[slot] == 0) continue;
                            if (SystemClock.uptimeMillis() - controllerRumbleAppliedMs[slot] >= CONTROLLER_RUMBLE_REARM_MS) {
                                rearmControllerVibration(slot);
                                if (controllerRumbleAppliedMs[slot] == 0) continue;
                            }
                            nextDeadline = Math.min(nextDeadline, controllerRumbleAppliedMs[slot] + CONTROLLER_RUMBLE_REARM_MS);
                        }
                        if (nextDeadline == Long.MAX_VALUE) {
                            rumbleLock.wait();
                        } else {
                            rumbleLock.wait(Math.max(1, nextDeadline - SystemClock.uptimeMillis()));
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        Log.e(TAG, "Rumble keepalive failed", t);
                    }
                }
            }
        }, "rumble-keepalive");
        rumbleKeepaliveThread.start();
    }

    private void rearmControllerVibration(int slot) {
        controllerRumbleAppliedMs[slot] = 0;
        InputDevice device = InputDevice.getDevice(rumbleDeviceIds[slot]);
        Vibrator controllerVibrator = device != null ? device.getVibrator() : null;
        if (controllerVibrator != null && controllerVibrator.hasVibrator()) {
            controllerVibrator.vibrate(VibrationEffect.createOneShot(CONTROLLER_RUMBLE_DURATION_MS, controllerRumbleAmplitude[slot]));
            controllerRumbleAppliedMs[slot] = SystemClock.uptimeMillis();
        }
    }

    public void setVibrationIntensity(int intensity) {
        vibrationIntensity = Math.max(0, Math.min(100, intensity));
    }

    private void startVibration(int slot, short lowFreq, short highFreq) {
        if (slot < 0 || slot >= MAX_PLAYERS) {
            return;
        }
        synchronized (rumbleLock) {
            boolean controllerWasRumbling = controllerRumbleAppliedMs[slot] != 0;
            controllerRumbleAppliedMs[slot] = 0;
            if (startDeviceVibration(slot, rumbleDeviceIds[slot], lowFreq, highFreq)) {
                isRumbling[slot] = true;
            } else if (controllerWasRumbling) {
                stopVibration(slot);
            }
            if (!controllerWasRumbling && controllerRumbleAppliedMs[slot] != 0) {
                rumbleLock.notifyAll();
            }
        }
    }

    private InputDevice getCurrentPhysicalControllerDevice() {
        InputDevice device = InputDevice.getDevice(currentControllerId);
        return ExternalController.isGameController(device) ? device : null;
    }

    private int getPhoneRumbleAmplitude(int amplitude) {
        float normalizedAmplitude = (float) amplitude / 255.0f;
        float curvedAmplitude = (float) Math.pow(normalizedAmplitude, 0.6f);
        int phoneAmplitude = (int) (curvedAmplitude * 255);
        if (phoneAmplitude > 255) phoneAmplitude = 255;
        if (phoneAmplitude <= 1) phoneAmplitude = 0;
        return phoneAmplitude;
    }

    private boolean startDeviceVibration(int slot, int deviceId, short lowFreq, short highFreq) {
        // --- Step 1: Calculate the base amplitude once at the top ---
        int unsignedLowFreq = lowFreq & 0xFFFF;
        int unsignedHighFreq = highFreq & 0xFFFF;
        int dominantRumble = Math.max(unsignedLowFreq, unsignedHighFreq);
        // This is the raw amplitude for a physical X-Input device
        int amplitude = Math.round((float) dominantRumble / 65535.0f * 254.0f) + 1;
        if (amplitude > 255) amplitude = 255;
        amplitude = amplitude * vibrationIntensity / 100;
        // If amplitude is negligible, just stop and exit.
        if (amplitude <= 1) {
            return false;
        }
        boolean controllerVibrated = false;
        boolean phoneVibrated = false;
        // --- Step 2: Attempt to vibrate the physical controller first ---
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null) {
            Vibrator controllerVibrator = device.getVibrator();
            if (controllerVibrator != null && controllerVibrator.hasVibrator()) {
                controllerVibrator.vibrate(VibrationEffect.createOneShot(CONTROLLER_RUMBLE_DURATION_MS, amplitude));
                controllerRumbleAmplitude[slot] = amplitude;
                controllerRumbleAppliedMs[slot] = SystemClock.uptimeMillis();
                controllerVibrated = true;
            }
        }

        // --- Step 3: Fallback to phone vibration only for a real controller without rumble.
        if (!controllerVibrated && device != null) {
            Log.w("WinHandler", "No physical controller vibrator found, falling back to device vibration.");
            Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (phoneVibrator != null && phoneVibrator.hasVibrator()) {
                int finalPhoneAmplitude = getPhoneRumbleAmplitude(amplitude);
                if (finalPhoneAmplitude > 0) {
                    phoneVibrator.vibrate(VibrationEffect.createOneShot(PHONE_RUMBLE_FALLBACK_DURATION_MS, finalPhoneAmplitude));
                    phoneVibrated = true;
                }
            }
        } else if (device == null) {
            long now = SystemClock.uptimeMillis();
            if (now - lastStandalonePhoneRumbleMs >= STANDALONE_PHONE_RUMBLE_THROTTLE_MS) {
                Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (phoneVibrator != null && phoneVibrator.hasVibrator()) {
                    int finalPhoneAmplitude = getPhoneRumbleAmplitude(amplitude);
                    if (finalPhoneAmplitude > 0) {
                        phoneVibrator.vibrate(VibrationEffect.createOneShot(STANDALONE_PHONE_RUMBLE_DURATION_MS, finalPhoneAmplitude));
                        lastStandalonePhoneRumbleMs = now;
                        phoneVibrated = true;
                    }
                }
            }
        }
        return controllerVibrated || phoneVibrated;
    }

    private void stopVibration(int slot) {
        if (slot < 0 || slot >= MAX_PLAYERS) {
            return;
        }
        synchronized (rumbleLock) {
            controllerRumbleAppliedMs[slot] = 0;
            if (!isRumbling[slot]) return;
            stopDeviceVibration(rumbleDeviceIds[slot]);
            isRumbling[slot] = false;
        }
    }

    private void stopDeviceVibration(int deviceId) {
        // Attempt to stop the physical controller's vibration if it exists
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null) {
            Vibrator vibrator = device.getVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.cancel();
            }
        }
        // Always attempt to stop the phone's vibration
        Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (phoneVibrator != null) {
            phoneVibrator.cancel();
        }
    }

    public void sendGamepadState() {
        if (!this.initReceived || this.gamepadClients.isEmpty()) {
            return;
        }
        final ControlsProfile profile = inputControlsView != null ? inputControlsView.getProfile() : null;
        final boolean useVirtualGamepad = isVirtualGamepadActive();
        // Read once: the receive thread may null currentController at any time.
        final ExternalController controller = this.currentController;
        final boolean enabled = controller != null || useVirtualGamepad;
        final GamepadState sourceState = enabled
                ? (useVirtualGamepad ? profile.getGamepadState() : controller.state)
                : null;
        final int deviceId = enabled
                ? (!useVirtualGamepad ? controller.getDeviceId() : profile.id)
                : 0;

        synchronized (this.actions) {
            boolean wake = false;
            // gamepadClients only changes in handleRequest(), under this lock.
            for (int i = 0, count = this.gamepadClients.size(); i < count; i++) {
                final int port = this.gamepadClients.get(i);
                GamepadStatePort statePort = this.gamepadStatePorts.get(port);
                if (statePort == null) {
                    statePort = new GamepadStatePort(port);
                    this.gamepadStatePorts.put(port, statePort);
                }
                statePort.enabled = enabled;
                statePort.deviceId = deviceId;
                statePort.state = sourceState;
                if (!statePort.queued) {
                    statePort.queued = true;
                    this.actions.add(statePort);
                    wake = true;
                }
            }
            if (wake) this.actions.notify();
        }
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        int slot = controllerManager.getSlotForDevice(event.getDeviceId());
        if (slot >= 0) {
            ExternalController controller = getControllerFromSlot(slot);
            if (!isEventFromController(controller, event.getDeviceId())) {
                Log.d(TAG, "Motion event refresh for deviceId=" + event.getDeviceId()
                        + " slot=" + slot
                        + " controller=" + (controller != null ? controller.getDeviceId() : -1));
                refreshControllerMappings();
                controller = getControllerFromSlot(slot);
            }
            if (isEventFromController(controller, event.getDeviceId())) {
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
            if (!isEventFromController(controller, event.getDeviceId())) {
                Log.d(TAG, "Key event refresh for deviceId=" + event.getDeviceId()
                        + " slot=" + slot
                        + " controller=" + (controller != null ? controller.getDeviceId() : -1));
                refreshControllerMappings();
                controller = getControllerFromSlot(slot);
            }
            if (isEventFromController(controller, event.getDeviceId())) {
                if (event.getRepeatCount() > 0) return true;
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
        writeGamepadState(controller.state, buffer, slot);
    }

    public void sendVirtualGamepadState(GamepadState state, int slot) {
        MappedByteBuffer buffer = getGamepadBuffer(slot);
        if (buffer == null || state == null) {
            return;
        }
        writeGamepadState(state, buffer, slot);
    }

    /** Writes a gamepad state to a slot's shared memory and notifies the readers only if something changed. */
    private void writeGamepadState(GamepadState state, MappedByteBuffer buffer, int slot) {
        boolean changed = putIntIfChanged(buffer, OFF_CONNECTED, 1);

        // Axes: write by fixed offsets, not sequential position
        changed |= putShortIfChanged(buffer, OFF_LX, (short) (state.thumbLX * 32767));
        changed |= putShortIfChanged(buffer, OFF_LY, (short) (state.thumbLY * 32767));
        changed |= putShortIfChanged(buffer, OFF_RX, (short) (state.thumbRX * 32767));
        changed |= putShortIfChanged(buffer, OFF_RY, (short) (state.thumbRY * 32767));

        // Triggers: curve and map to signed short range like your current code
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));
        float lCurve = (float)Math.sqrt(rawL);
        float rCurve = (float)Math.sqrt(rawR);
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;  // 0 → -32 767, 1 → 32 767
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;
        changed |= putShortIfChanged(buffer, OFF_LT, (short)lAxis);
        changed |= putShortIfChanged(buffer, OFF_RT, (short)rAxis);

        // Buttons: 15 bytes in SDL order starting at OFF_BTN
        changed |= putButtonIfChanged(buffer, 0, state.isPressed(0));   // A
        changed |= putButtonIfChanged(buffer, 1, state.isPressed(1));   // B
        changed |= putButtonIfChanged(buffer, 2, state.isPressed(2));   // X
        changed |= putButtonIfChanged(buffer, 3, state.isPressed(3));   // Y
        changed |= putButtonIfChanged(buffer, 4, state.isPressed(6));   // Back / Select
        changed |= putButtonIfChanged(buffer, 5, false);                // Guide
        changed |= putButtonIfChanged(buffer, 6, state.isPressed(7));   // Start
        changed |= putButtonIfChanged(buffer, 7, state.isPressed(8));   // L3
        changed |= putButtonIfChanged(buffer, 8, state.isPressed(9));   // R3
        changed |= putButtonIfChanged(buffer, 9, state.isPressed(4));   // LB
        changed |= putButtonIfChanged(buffer, 10, state.isPressed(5));  // RB
        changed |= putButtonIfChanged(buffer, 11, state.dpad[0]);       // Up
        changed |= putButtonIfChanged(buffer, 12, state.dpad[2]);       // Down
        changed |= putButtonIfChanged(buffer, 13, state.dpad[3]);       // Left
        changed |= putButtonIfChanged(buffer, 14, state.dpad[1]);       // Right

        // Hat at offset 31
        changed |= putByteIfChanged(buffer, OFF_HAT, (byte)0);

        // Notify native side that state changed
        if (changed) notifyStateChanged(slot);
    }

    private static boolean putIntIfChanged(MappedByteBuffer buffer, int offset, int value) {
        if (buffer.getInt(offset) == value) return false;
        buffer.putInt(offset, value);
        return true;
    }

    private static boolean putShortIfChanged(MappedByteBuffer buffer, int offset, short value) {
        if (buffer.getShort(offset) == value) return false;
        buffer.putShort(offset, value);
        return true;
    }

    private static boolean putByteIfChanged(MappedByteBuffer buffer, int offset, byte value) {
        if (buffer.get(offset) == value) return false;
        buffer.put(offset, value);
        return true;
    }

    private static boolean putButtonIfChanged(MappedByteBuffer buffer, int sdlButton, boolean pressed) {
        return putByteIfChanged(buffer, OFF_BTN + sdlButton, pressed ? (byte)1 : (byte)0);
    }

    public void sendVirtualGamepadState(GamepadState state) {
        sendVirtualGamepadState(state, 0);
    }
}
