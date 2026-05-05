package com.winlator.inputcontrols;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.preference.PreferenceManager;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import app.gamenative.PrefManager;

import com.winlator.winhandler.WinHandler;

import java.util.ArrayList;
import java.util.List;

public class ControllerManager implements InputManager.InputDeviceListener {

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

    // This maps a player slot (0-3) to the unique identifier of the physical device.
    // e.g., key=0, value="vendor_123_product_456"
    private final SparseArray<String> slotAssignments = new SparseArray<>();

    // This tracks which of the 4 player slots are enabled by the user.
    private final boolean[] enabledSlots = new boolean[WinHandler.MAX_PLAYERS];

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
        scanForDevices();

        // Keep detectedDevices in sync with hot-plug events. null Handler dispatches
        // callbacks on the main thread, the same thread that handles input events,
        // so detectedDevices needs no synchronization.
        inputManager.registerInputDeviceListener(this, null);

        // Single-controller correction: if only one controller is connected and it's
        // not in slot 0, move it so P1 is always populated for games that may only check P1
        if (detectedDevices.size() == 1) {
            String id = getDeviceIdentifier(detectedDevices.get(0));
            if (id != null && !id.equals(slotAssignments.get(0))) {
                // Remove from whatever slot it was in
                for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
                    if (id.equals(slotAssignments.get(i))) {
                        slotAssignments.remove(i);
                    }
                }
                slotAssignments.put(0, id);
                enabledSlots[0] = true;
                saveAssignments();
            }
        }
    }




    /**
     * Scans for all physically connected game controllers and updates the internal list.
     */
    public void scanForDevices() {
        detectedDevices.clear();
        int[] deviceIds = inputManager.getInputDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice device = inputManager.getInputDevice(deviceId);
            // We only want physical gamepads/joysticks, not virtual ones or touchscreens.
            if (device != null && !device.isVirtual() && isGameController(device)) {
                detectedDevices.add(device);
            }
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        if (device == null || device.isVirtual() || !isGameController(device)) return;
        for (InputDevice existing : detectedDevices) {
            if (existing.getId() == deviceId) return;
        }
        detectedDevices.add(device);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        // getInputDevice returns null for a removed device, so match by id.
        for (int i = 0; i < detectedDevices.size(); i++) {
            if (detectedDevices.get(i).getId() == deviceId) {
                detectedDevices.remove(i);
                return;
            }
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        onInputDeviceRemoved(deviceId);
        onInputDeviceAdded(deviceId);
    }

    /**
     * Loads the saved player slot assignments and enabled states from SharedPreferences.
     */
    private void loadAssignments() {
        slotAssignments.clear();
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            // Load which device is assigned to this slot
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            String deviceIdentifier = preferences.getString(prefKey, null);
            if (deviceIdentifier != null) {
                slotAssignments.put(i, deviceIdentifier);
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
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
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
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return;

        String newDeviceIdentifier = getDeviceIdentifier(device);
        if (newDeviceIdentifier == null) return;

        // First, remove the new device from any slot it might already be in.
        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            if (newDeviceIdentifier.equals(slotAssignments.get(i))) {
                slotAssignments.remove(i);
            }
        }

        // Assign the new device to the target slot.
        slotAssignments.put(slotIndex, newDeviceIdentifier);
        saveAssignments(); // Persist the change immediately.
    }

    /**
     * Clears any device assignment for the given player slot.
     * @param slotIndex The player slot to un-assign (0-3).
     */
    public void unassignSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return;
        slotAssignments.remove(slotIndex);
        saveAssignments();
    }

    /**
     * Finds which player slot a given device is assigned to.
     * @param deviceId The ID of the physical device.
     * @return The player slot index (0-3), or -1 if the device is not assigned.
     */
    public int getSlotForDevice(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        String deviceIdentifier = getDeviceIdentifier(device);
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
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return;
        enabledSlots[slotIndex] = isEnabled;
        saveAssignments();
    }

    public boolean isSlotEnabled(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= WinHandler.MAX_PLAYERS) return false;
        return enabledSlots[slotIndex];
    }

    /**
     * Auto-assigns a device to the first available slot.
     * If the device is already assigned, returns its existing slot.
     * When only one controller is connected, it always gets slot 0 (P1) — this
     * prevents stale multi-controller assignments from stranding a single controller
     * in a non-P1 slot where games won't see it.
     * @param deviceId The Android device ID from the input event.
     * @return The slot index (0-3), or -1 if no slot available or device is not a controller.
     */
    public int autoAssignDevice(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        if (device == null || !isGameController(device)) return -1;

        int existingSlot = getSlotForDevice(deviceId);

        // Single-controller fast path: if this is the only connected controller and
        // it's not already in slot 0, move it there so P1 is always populated.
        // Also require that detectedDevices actually contains the current device — without
        // this, a stale cache (e.g. P2 just plugged in but onInputDeviceAdded hasn't fired
        // yet) would see size==1 from the previous device and incorrectly evict P1 from slot 0.
        if (detectedDevices.size() == 1 && detectedDevices.get(0).getId() == deviceId && existingSlot != 0) {
            String deviceIdentifier = getDeviceIdentifier(device);
            if (existingSlot >= 0) {
                slotAssignments.remove(existingSlot);
            }
            slotAssignments.put(0, deviceIdentifier);
            enabledSlots[0] = true;
            saveAssignments();
            return 0;
        }

        if (existingSlot >= 0) {
            return isSlotEnabled(existingSlot) ? existingSlot : -1;
        }

        for (int i = 0; i < WinHandler.MAX_PLAYERS; i++) {
            if (slotAssignments.get(i) == null) {
                assignDeviceToSlot(i, device);
                setSlotEnabled(i, true);
                return i;
            }
        }
        return -1;
    }
}
