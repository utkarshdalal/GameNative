package com.winlator.inputcontrols;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.core.FileUtils;
import com.winlator.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile> {
    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean virtualGamepad = false;
    private final Context context;
    private GamepadState gamepadState;

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = cursorSpeed;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    public void setVirtualGamepad(boolean isVirtualGamepad) {
        virtualGamepad = isVirtualGamepad;
    }

    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) {
            controller = new ExternalController();
            controller.setId(id);
            controller.setName("Physical Controller");
            controllers.add(controller);
        }
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();

        // First try exact device ID match
        for (ExternalController controller : controllers) {
            if (controller.getDeviceId() == deviceId) return controller;
        }

        // Fall back to wildcard controller if no exact match
        for (ExternalController controller : controllers) {
            if (controller.getId().equals("*")) return controller;
        }

        return null;
    }

    public ArrayList<ExternalController> getControllers() {
        if (!controllersLoaded) loadControllers();
        return new ArrayList<>(controllers);
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Saving profile: " + name + " (ID: " + id + ") to " + file.getAbsolutePath());

        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                elementsJSONArray = profileJSONObject.getJSONArray("elements");
            }
            else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

            FileUtils.writeString(file, data.toString());
            Log.d("ControlsProfile", "Profile saved successfully: " + name + " (controllers: " + controllersJSONArray.length() + ", elements: " + elementsJSONArray.length() + ")");
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to save profile: " + name + " (ID: " + id + ")", e);
        }
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Loading controllers for profile: " + name + " (ID: " + id + ") from " + file.getAbsolutePath());

        if (!file.isFile()) {
            Log.d("ControlsProfile", "Profile file does not exist: " + name);
            return controllers;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("controllers")) {
                Log.d("ControlsProfile", "No controllers section in profile: " + name);
                return controllers;
            }
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                String id = controllerJSONObject.getString("id");
                ExternalController controller = new ExternalController();
                controller.setId(id);
                controller.setName(controllerJSONObject.getString("name"));

                JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                    JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                    ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                    controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                    controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
            Log.d("ControlsProfile", "Loaded " + controllers.size() + " controllers for profile: " + name);
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to load controllers for profile: " + name + " (ID: " + id + ")", e);
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        // Check if view has valid dimensions before loading
        if (inputControlsView.getMaxWidth() == 0 || inputControlsView.getMaxHeight() == 0) {
            Log.w("ControlsProfile", "Cannot load elements - view has no dimensions yet (width: " +
                inputControlsView.getWidth() + ", height: " + inputControlsView.getHeight() + ")");
            return;
        }

        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Loading elements for profile: " + name + " (ID: " + id + ") from " + file.getAbsolutePath());

        if (!file.isFile()) {
            Log.d("ControlsProfile", "Profile file does not exist: " + name);
            return;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                ControlElement element = new ControlElement(inputControlsView);
                try {
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
                } catch (IllegalArgumentException e) {
                    Log.w("ControlsProfile", "Skipping element with unknown type: " + elementJSONObject.getString("type"));
                    continue;
                }
                element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                element.setX((int)(elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
                element.setY((int)(elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
                element.setScale((float)elementJSONObject.getDouble("scale"));
                element.setText(elementJSONObject.getString("text"));
                element.setIconId(elementJSONObject.getInt("iconId"));
                if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));
                if (elementJSONObject.has("scrollLocked")) element.setScrollLocked(elementJSONObject.getBoolean("scrollLocked"));

                if (elementJSONObject.has("shooterMovementType")) element.setShooterMovementType(elementJSONObject.getString("shooterMovementType"));
                if (elementJSONObject.has("shooterLookType")) element.setShooterLookType(elementJSONObject.getString("shooterLookType"));
                if (elementJSONObject.has("shooterLookSensitivity")) element.setShooterLookSensitivity((float)elementJSONObject.getDouble("shooterLookSensitivity"));
                if (elementJSONObject.has("shooterJoystickSize")) element.setShooterJoystickSize((float)elementJSONObject.getDouble("shooterJoystickSize"));

                boolean hasGamepadBinding = true;
                JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
                for (int j = 0; j < bindingsJSONArray.length(); j++) {
                    Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
                    element.setBindingAt(j, Binding.fromString(bindingsJSONArray.getString(j)));
                    if (!binding.isGamepad()) hasGamepadBinding = false;
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                elements.add(element);
            }
            elementsLoaded = true;
            Log.d("ControlsProfile", "Loaded " + elements.size() + " elements for profile: " + name + " (virtualGamepad: " + virtualGamepad + ")");
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to load elements for profile: " + name + " (ID: " + id + ")", e);
            e.printStackTrace();
        }
    }
}
