package com.winlator.inputcontrols;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.preference.PreferenceManager;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import app.gamenative.PrefManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class ControllerManager {
    private static final String TAG = "ControllerManager";
    private static final int MAX_SLOTS = 4;

    @SuppressLint("StaticFieldLeak")
    private static ControllerManager instance;


    public static synchronized ControllerManager getInstance() {
        if (instance == null) {
            instance = new ControllerManager();
        }
        return instance;
    }

    private ControllerManager() {
        // Private constructor to prevent direct instantiation.
    }

    // --- Core Properties ---
    private Context context;
    private SharedPreferences preferences;
    private InputManager inputManager;

    // This list will hold all physical game controllers detected by Android.
    private final List<InputDevice> detectedDevices = new ArrayList<>();
    private final SparseArray<String> knownDeviceIdentifiers = new SparseArray<>();

    // This maps a player slot (0-3) to the unique identifier of the physical device.
    // e.g., key=0, value="vendor_123_product_456"
    private final SparseArray<String> slotAssignments = new SparseArray<>();
    private final Map<String, Integer> lastKnownSlotByIdentifier = new HashMap<>();

    // This tracks which of the 4 player slots are enabled by the user.
    private final boolean[] enabledSlots = new boolean[MAX_SLOTS];
    private final List<OnSlotsChangedListener> slotListeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Integer> recentlyFreedSlots = new ArrayDeque<>();

    private static final long ASSIGN_SETTLE_MS = 300L;
    private final Map<String, Long> firstSeenByIdentifier = new HashMap<>();
    private final Handler settleHandler = new Handler(Looper.getMainLooper());
    private final Runnable settleAssignRunnable = this::autoAssignConnectedDevices;

    public interface OnSlotsChangedListener {
        void onSlotsChanged();
    }

    public static final String PREF_PLAYER_SLOT_PREFIX = "controller_slot_";
    public static final String PREF_ENABLED_SLOTS_PREFIX = "enabled_slot_";


    /**
     * Initializes the manager. This must be called once from the main application context.
     * @param context The application context.
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.inputManager = (InputManager) this.context.getSystemService(Context.INPUT_SERVICE);

        // On startup, we load saved settings and scan for connected devices.
        loadAssignments();
        autoAssignConnectedDevices();
    }




    /**
     * Scans for all physically connected game controllers and updates the internal list.
     */
    public void scanForDevices() {
        detectedDevices.clear();
        int[] deviceIds = inputManager.getInputDeviceIds();
        Set<String> present = new HashSet<>();
        for (int deviceId : deviceIds) {
            InputDevice device = inputManager.getInputDevice(deviceId);
            // Some handhelds expose built-in controls as virtual devices, so
            // accept any device that reports a real gamepad/joystick shape.
            if (device != null && isGameController(device)) {
                detectedDevices.add(device);
                String ident = getDeviceIdentifier(device);
                knownDeviceIdentifiers.put(deviceId, ident);
                if (ident != null) present.add(ident);
            }
        }
        long now = SystemClock.elapsedRealtime();
        for (String ident : present) {
            if (!firstSeenByIdentifier.containsKey(ident)) {
                firstSeenByIdentifier.put(ident, now);
            }
        }
        firstSeenByIdentifier.keySet().retainAll(present);
    }

    private boolean isSettled(String identifier) {
        Long t = firstSeenByIdentifier.get(identifier);
        return t != null && (SystemClock.elapsedRealtime() - t) >= ASSIGN_SETTLE_MS;
    }

    private void scheduleSettleAssign() {
        settleHandler.removeCallbacks(settleAssignRunnable);
        settleHandler.postDelayed(settleAssignRunnable, ASSIGN_SETTLE_MS + 20L);
    }

    /**
     * Loads the saved player slot assignments and enabled states from SharedPreferences.
     */
    private void loadAssignments() {
        slotAssignments.clear();
        lastKnownSlotByIdentifier.clear();
        for (int i = 0; i < MAX_SLOTS; i++) {
            // Load which device is assigned to this slot
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            String deviceIdentifier = preferences.getString(prefKey, null);
            if (deviceIdentifier != null) {
                slotAssignments.put(i, deviceIdentifier);
                lastKnownSlotByIdentifier.put(deviceIdentifier, i);
            }

            // Load whether this slot is enabled. Default P1=true, P2-4=false.
            String enabledKey = PREF_ENABLED_SLOTS_PREFIX + i;
            enabledSlots[i] = preferences.getBoolean(enabledKey, i == 0);
        }
    }

    /**
     * Saves the current player slot assignments and enabled states to SharedPreferences.
     */
    public void saveAssignments() {
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < MAX_SLOTS; i++) {
            // Save the assigned device identifier
            String deviceIdentifier = slotAssignments.get(i);
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            if (deviceIdentifier != null) {
                editor.putString(prefKey, deviceIdentifier);
            } else {
                editor.remove(prefKey);
            }

            // Save the enabled state
            String enabledKey = PREF_ENABLED_SLOTS_PREFIX + i;
            editor.putBoolean(enabledKey, enabledSlots[i]);
        }
        editor.apply();
    }

// --- Helper & Getter Methods ---

    /**
     * Checks if a device is a gamepad or joystick.
     * @param device The InputDevice to check.
     * @return True if the device is a game controller.
     */
    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;

        boolean isGamepad = device.supportsSource(InputDevice.SOURCE_GAMEPAD);
        boolean isJoystick = device.supportsSource(InputDevice.SOURCE_JOYSTICK);

        boolean hasAxes =
                device.getMotionRange(android.view.MotionEvent.AXIS_X) != null ||
                        device.getMotionRange(android.view.MotionEvent.AXIS_Y) != null;

        boolean[] hasGamepadKeysArray = device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y
        );

        boolean hasGamepadKeys = false;
        for (boolean hasKey : hasGamepadKeysArray) {
            if (hasKey) {
                hasGamepadKeys = true;
                break;
            }
        }

        return (isGamepad && hasGamepadKeys) ||
                (isJoystick && hasAxes);
    }

    /**
     * Creates a stable, unique identifier string for a given device.
     * This is used for saving and loading assignments.
     * @param device The InputDevice.
     * @return A unique identifier string.
     */
    public static String getDeviceIdentifier(InputDevice device) {
        if (device == null) return null;
        // The descriptor is the most reliable unique ID for a device.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return device.getDescriptor();
        }
        // Fallback for older Android versions
        return "vendor_" + device.getVendorId() + "_product_" + device.getProductId();
    }

    /**
     * Returns the list of all detected physical game controllers.
     */
    public List<InputDevice> getDetectedDevices() {
        return detectedDevices;
    }

    public void addOnSlotsChangedListener(OnSlotsChangedListener listener) {
        if (listener != null && !slotListeners.contains(listener)) {
            slotListeners.add(listener);
        }
    }

    public void removeOnSlotsChangedListener(OnSlotsChangedListener listener) {
        slotListeners.remove(listener);
    }

    private void notifySlotsChanged() {
        for (OnSlotsChangedListener listener : slotListeners) {
            listener.onSlotsChanged();
        }
    }

    /**
     * Returns the number of player slots the user has enabled.
     */
    public int getEnabledPlayerCount() {
        int count = 0;
        for (boolean enabled : enabledSlots) {
            if (enabled) {
                count++;
            }
        }
        return count;
    }

    /**
     * Assigns a physical device to a specific player slot.
     * This method handles un-assigning the device from any other slot it might have been in.
     * @param slotIndex The player slot to assign to (0-3).
     * @param device The physical InputDevice to assign.
     */
    public void assignDeviceToSlot(int slotIndex, InputDevice device) {
        if (slotIndex < 0 || slotIndex >= MAX_SLOTS) return;

        String newDeviceIdentifier = getDeviceIdentifier(device);
        if (newDeviceIdentifier == null) return;

        assignDeviceIdentifierToSlot(slotIndex, newDeviceIdentifier);
        saveAssignments();
        notifySlotsChanged();
    }

    private void assignDeviceIdentifierToSlot(int slotIndex, String newDeviceIdentifier) {
        // First, remove the new device from any slot it might already be in.
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (newDeviceIdentifier.equals(slotAssignments.get(i))) {
                slotAssignments.remove(i);
            }
        }

        // Assign the new device to the target slot.
        slotAssignments.put(slotIndex, newDeviceIdentifier);
        lastKnownSlotByIdentifier.put(newDeviceIdentifier, slotIndex);
        recentlyFreedSlots.remove(slotIndex);
    }

    /**
     * Clears any device assignment for the given player slot.
     * @param slotIndex The player slot to un-assign (0-3).
     */
    public void unassignSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= MAX_SLOTS) return;
        String deviceIdentifier = slotAssignments.get(slotIndex);
        if (deviceIdentifier != null) {
            lastKnownSlotByIdentifier.put(deviceIdentifier, slotIndex);
        }
        slotAssignments.remove(slotIndex);
        markSlotRecentlyFreed(slotIndex);
        saveAssignments();
        notifySlotsChanged();
    }

    /**
     * Finds which player slot a given device is assigned to.
     * @param deviceId The ID of the physical device.
     * @return The player slot index (0-3), or -1 if the device is not assigned.
     */
    public int getSlotForDevice(int deviceId) {
        String deviceIdentifier = getDeviceIdentifierForDeviceId(deviceId);
        if (deviceIdentifier == null) return -1;

        return getSlotForIdentifier(deviceIdentifier);
    }

    private int getSlotForIdentifier(String deviceIdentifier) {
        if (deviceIdentifier == null) return -1;

        // Correctly loop through the sparse array to find the key for our value.
        for (int i = 0; i < slotAssignments.size(); i++) {
            int key = slotAssignments.keyAt(i);
            String value = slotAssignments.valueAt(i);
            if (deviceIdentifier.equals(value)) {
                return key; // Return the key (the slot index), not the internal index!
            }
        }

        return -1; // Not found
    }

    private String getDeviceIdentifierForDeviceId(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        String deviceIdentifier = getDeviceIdentifier(device);
        if (deviceIdentifier != null) {
            knownDeviceIdentifiers.put(deviceId, deviceIdentifier);
            return deviceIdentifier;
        }
        return knownDeviceIdentifiers.get(deviceId);
    }


    /**
     * Gets the InputDevice object that is currently assigned to a specific player slot.
     * @param slotIndex The player slot (0-3).
     * @return The assigned InputDevice, or null if no device is assigned or if the device is not currently connected.
     */
    public InputDevice getAssignedDeviceForSlot(int slotIndex) {
        String assignedIdentifier = slotAssignments.get(slotIndex);
        if (assignedIdentifier == null) return null;

        // Search our current list of connected devices for one that matches the saved identifier.
        for (InputDevice device : detectedDevices) {
            if (assignedIdentifier.equals(getDeviceIdentifier(device))) {
                return device; // Found it.
            }
        }

        return null; // The assigned device is not currently connected.
    }

    /**
     * Sets whether a player slot is enabled ("Connected").
     * @param slotIndex The player slot (0-3).
     * @param isEnabled The new enabled state.
     */
    public void setSlotEnabled(int slotIndex, boolean isEnabled) {
        if (slotIndex < 0 || slotIndex >= MAX_SLOTS) return;
        enabledSlots[slotIndex] = isEnabled;
        saveAssignments();
        notifySlotsChanged();
    }

    public boolean isSlotEnabled(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= MAX_SLOTS) return false;
        return enabledSlots[slotIndex];
    }

    public void onDeviceConnected(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        if (device == null || !isGameController(device)) {
            return;
        }

        String deviceIdentifier = getDeviceIdentifier(device);
        if (deviceIdentifier == null) {
            return;
        }

        knownDeviceIdentifiers.put(deviceId, deviceIdentifier);
        scanForDevices();
        int existing = getSlotForDevice(deviceId);
        if (existing >= 0) {
            return;
        }

        if (!isSettled(deviceIdentifier)) {
            scheduleSettleAssign();
            return;
        }

        int slot = getPreferredFreeSlot(deviceIdentifier);
        if (slot >= 0) {
            enabledSlots[slot] = true;
            assignDeviceIdentifierToSlot(slot, deviceIdentifier);
            saveAssignments();
            notifySlotsChanged();
            Log.i(TAG, "Auto-assigned deviceId=" + deviceId + " to Player " + (slot + 1));
            return;
        }
        Log.i(TAG, "No free controller slot for deviceId=" + deviceId);
    }

    /**
     * Assigns any currently connected controller that is not already bound to a player slot.
     * Built-in controllers can be present before Android dispatches any hot-plug callback,
     * so callers should run this after a device scan during startup/session refresh.
     */
    public void autoAssignConnectedDevices() {
        scanForDevices();
        boolean changed = false;
        for (InputDevice device : detectedDevices) {
            String deviceIdentifier = getDeviceIdentifier(device);
            if (deviceIdentifier == null || getSlotForDevice(device.getId()) >= 0) {
                continue;
            }

            if (!isSettled(deviceIdentifier)) {
                scheduleSettleAssign();
                continue;
            }

            int slot = getPreferredFreeSlot(deviceIdentifier);
            if (slot < 0) {
                Log.i(TAG, "No free controller slot for connected deviceId=" + device.getId());
                break;
            }

            enabledSlots[slot] = true;
            assignDeviceIdentifierToSlot(slot, deviceIdentifier);
            knownDeviceIdentifiers.put(device.getId(), deviceIdentifier);
            changed = true;
            Log.i(TAG, "Auto-assigned connected deviceId=" + device.getId()
                    + " to Player " + (slot + 1));
        }

        if (changed) {
            saveAssignments();
            notifySlotsChanged();
        }
    }

    public void onDeviceDisconnected(int deviceId) {
        String deviceIdentifier = getDeviceIdentifierForDeviceId(deviceId);
        int slot = getSlotForIdentifier(deviceIdentifier);
        knownDeviceIdentifiers.remove(deviceId);
        scanForDevices();
        if (slot >= 0) {
            slotAssignments.remove(slot);
            if (deviceIdentifier != null) {
                lastKnownSlotByIdentifier.put(deviceIdentifier, slot);
            }
            markSlotRecentlyFreed(slot);
            saveAssignments();
            notifySlotsChanged();
            Log.i(TAG, "Unassigned disconnected deviceId=" + deviceId + " from Player " + (slot + 1));
        }
    }

    private void markSlotRecentlyFreed(int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            return;
        }
        recentlyFreedSlots.remove(slot);
        recentlyFreedSlots.addLast(slot);
    }

    private boolean isSlotAvailable(int slot) {
        return slot >= 0 && slot < MAX_SLOTS && getAssignedDeviceForSlot(slot) == null;
    }

    private int getPreferredFreeSlot(String deviceIdentifier) {
        Integer previousSlot = lastKnownSlotByIdentifier.get(deviceIdentifier);
        if (previousSlot != null && isSlotAvailable(previousSlot)) {
            recentlyFreedSlots.remove(previousSlot);
            return previousSlot;
        }

        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            if (isSlotAvailable(slot)) {
                return slot;
            }
        }
        return -1;
    }
}
