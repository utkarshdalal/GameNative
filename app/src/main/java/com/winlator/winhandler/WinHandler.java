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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class WinHandler {

    private static final String TAG = "WinHandler";
    private final ControllerManager controllerManager;
    public static final int MAX_PLAYERS = 1;
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
    private Thread rumblePollerThread;
    private short lastLowFreq = 0;  // Use 'short' instead of uint16_t
    private short lastHighFreq = 0; // Use 'short' instead of uint16_t
    private boolean isRumbling = false;
    private long lastStandalonePhoneRumbleMs = 0;
    private boolean isShowingAssignDialog = false;
    private Context activity;
    private final java.util.Set<Integer> ignoredDeviceIds = new java.util.HashSet<>();
    private RandomAccessFile gamepadRaf;
    private RandomAccessFile[] extraGamepadRafs;

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
    private static final int CONTROLLER_RUMBLE_DURATION_MS = 1000;
    private static final int PHONE_RUMBLE_FALLBACK_DURATION_MS = 40;
    private static final int STANDALONE_PHONE_RUMBLE_DURATION_MS = 70;
    private static final int STANDALONE_PHONE_RUMBLE_THROTTLE_MS = 120;

    // Add method to set InputControlsView
    public void setInputControlsView(InputControlsView view) {
        this.inputControlsView = view;
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
    }

    public void refreshControllerMappings() {
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
                Log.i(TAG, "Initialized Player 1 with: " + p1Device.getName());
            }
        }
        // Initialize Extra Players (2, 3, 4)
        for (int i = 0; i < extraControllers.length; i++) {
            // Player 2 is slot 1, which corresponds to extraControllers[0]
            InputDevice extraDevice = controllerManager.getAssignedDeviceForSlot(i + 1);
            if (extraDevice != null) {
                extraControllers[i] = ExternalController.getController(extraDevice.getId());
                Log.i(TAG, "Initialized Player " + (i + 2) + " with: " + extraDevice.getName());
            }
        }
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
        rumbleTeardown(0);
        try {
            if (rumblePollerThread != null)
                this.rumblePollerThread.join();
        } catch (InterruptedException ignored) {
        }
        DatagramSocket datagramSocket = this.socket;
        if (datagramSocket != null) {
            datagramSocket.close();
            this.socket = null;
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
        if (currentControllerId != deviceId)
            this.currentControllerId = deviceId;
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
        rumblePollerThread = new Thread(() -> {
            int curSeq = 0;
            int lastSeq = 0;
            while (running) {
                try {
                    curSeq = WinHandler.waitForRumble(0, lastSeq);
                    if (!running) break;
                    if (curSeq == lastSeq) {
                        continue;
                    }

                    lastSeq = curSeq;

                    // Read the rumble values from the shared memory file after change was signaled or timeout happened
                    short lowFreq = gamepadBuffer.getShort(OFF_RUMBLE_LOW);
                    short highFreq = gamepadBuffer.getShort(OFF_RUMBLE_HIGH);

                    // Check if the rumble state has changed
                    if (lowFreq != lastLowFreq || highFreq != lastHighFreq) {
                        lastLowFreq = lowFreq;
                        lastHighFreq = highFreq;
                        if (lowFreq == 0 && highFreq == 0) {
                            stopVibration();
                        } else {
                            startVibration(lowFreq, highFreq);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        rumblePollerThread.start();
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

    private void startVibration(short lowFreq, short highFreq) {
        // --- Step 1: Calculate the base amplitude once at the top ---
        int unsignedLowFreq = lowFreq & 0xFFFF;
        int unsignedHighFreq = highFreq & 0xFFFF;
        int dominantRumble = Math.max(unsignedLowFreq, unsignedHighFreq);
        // This is the raw amplitude for a physical X-Input device
        int amplitude = Math.round((float) dominantRumble / 65535.0f * 254.0f) + 1;
        if (amplitude > 255) amplitude = 255;
        // If amplitude is negligible, just stop and exit.
        if (amplitude <= 1) {
            stopVibration();
            return;
        }
        boolean controllerVibrated = false;
        boolean phoneVibrated = false;
        // --- Step 2: Attempt to vibrate the physical controller first ---
        InputDevice device = getCurrentPhysicalControllerDevice();
        if (device != null) {
            Vibrator controllerVibrator = device.getVibrator();
            if (controllerVibrator != null && controllerVibrator.hasVibrator()) {
                controllerVibrator.vibrate(VibrationEffect.createOneShot(CONTROLLER_RUMBLE_DURATION_MS, amplitude));
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
        isRumbling = controllerVibrated || phoneVibrated;
    }
    private void stopVibration() {
        if (!isRumbling) return; // Simplified check
        // Attempt to stop the physical controller's vibration if it exists
        InputDevice device = getCurrentPhysicalControllerDevice();
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
        isRumbling = false;
    }

    public void sendGamepadState() {
        if (!this.initReceived || this.gamepadClients.isEmpty()) {
            return;
        }
        final ControlsProfile profile = inputControlsView.getProfile();
        final boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
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
        // If this is a gamepad event but our controller is null or mismatched, adopt it
        InputDevice device = event.getDevice();
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
            sendMemoryFileState(this.currentController, buffer);
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
        sendMemoryFileState(currentController, gamepadBuffer);
    }

    private void sendMemoryFileState(ExternalController controller, MappedByteBuffer buffer) {
        if (buffer == null || controller == null) {
            return;
        }
        GamepadState state = controller.state;

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

        notifyStateChanged(0);
    }

    public void sendVirtualGamepadState(GamepadState state) {
        if (gamepadBuffer == null || state == null) {
            return;
        }

        // Axes: write by fixed offsets, not sequential position
        gamepadBuffer.putShort(OFF_LX, (short) (state.thumbLX * 32767));
        gamepadBuffer.putShort(OFF_LY, (short) (state.thumbLY * 32767));
        gamepadBuffer.putShort(OFF_RX, (short) (state.thumbRX * 32767));
        gamepadBuffer.putShort(OFF_RY, (short) (state.thumbRY * 32767));

        // Triggers: curve and map to signed short range like your current code
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));

        float lCurve = (float) Math.sqrt(rawL);
        float rCurve = (float) Math.sqrt(rawR);

        int lAxis = Math.round(lCurve * 65534f) - 32767;
        int rAxis = Math.round(rCurve * 65534f) - 32767;

        gamepadBuffer.putShort(OFF_LT, (short) lAxis);
        gamepadBuffer.putShort(OFF_RT, (short) rAxis);

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
            gamepadBuffer.put(OFF_BTN + i, sdlButtons[i]);
        }

        // Hat at offset 31
        gamepadBuffer.put(OFF_HAT, (byte) 0);

        // Notify native side that state changed
        notifyStateChanged(0);
    }
}
