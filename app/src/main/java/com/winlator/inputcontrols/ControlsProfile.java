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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile> {
    public static final float DEFAULT_CURSOR_SPEED = 1.0f;
    public static final String KEY_AUTO_FIT_LAYOUT = "autoFitLayout";

    public final int id;
    private String name;
    private float cursorSpeed = DEFAULT_CURSOR_SPEED;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final ArrayList<RadialMenu> radialMenus = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean radialMenusLoaded = false;
    private boolean virtualGamepad = false;
    private boolean listed = true;
    private int libraryProfileId = -1;
    private int maxReferencedProfileId = -1;
    private String gameOwnerId = "";
    private JSONObject elementSourceOverride;
    private final Context context;
    private GamepadState gamepadState;
    private final IdentityHashMap<ControlElement, AutoFitLayout> autoFitLayouts = new IdentityHashMap<>();

    private static final class AutoFitLayout {
        final double sourceX;
        final double sourceY;
        final float sourceScale;
        int fittedX;
        int fittedY;
        float fittedScale;

        AutoFitLayout(double sourceX, double sourceY, float sourceScale) {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.sourceScale = sourceScale;
        }

        void captureFitted(ControlElement element) {
            fittedX = element.getX();
            fittedY = element.getY();
            fittedScale = element.getScale();
        }

        boolean matchesFitted(ControlElement element) {
            return element.getX() == fittedX && element.getY() == fittedY &&
                    Float.compare(element.getScale(), fittedScale) == 0;
        }
    }

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

    public boolean isListed() {
        return listed;
    }

    public void setListed(boolean listed) {
        this.listed = listed;
    }

    public int getLibraryProfileId() {
        return libraryProfileId;
    }

    public void setLibraryProfileId(int libraryProfileId) {
        this.libraryProfileId = libraryProfileId;
    }

    public int getMaxReferencedProfileId() {
        return maxReferencedProfileId;
    }

    public void setMaxReferencedProfileId(int maxReferencedProfileId) {
        this.maxReferencedProfileId = maxReferencedProfileId;
    }

    public String getGameOwnerId() {
        return gameOwnerId;
    }

    public void setGameOwnerId(String gameOwnerId) {
        this.gameOwnerId = gameOwnerId != null ? gameOwnerId : "";
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

    public boolean save() {
        if (elementSourceOverride != null) return false;
        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Saving profile: " + name + " (ID: " + id + ") to " + file.getAbsolutePath());

        try {
            // Preserve profile sections and metadata managed outside this runtime model.
            // Rebuilding from an empty object would silently discard them whenever the
            // on-screen editor saves a profile.
            JSONObject data = file.isFile()
                    ? new JSONObject(FileUtils.readString(file))
                    : new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));
            data.put("listed", listed);
            if (libraryProfileId >= 0) data.put("libraryProfileId", libraryProfileId);
            else data.remove("libraryProfileId");
            if (!gameOwnerId.isEmpty()) data.put("gameOwnerId", gameOwnerId);
            else data.remove("gameOwnerId");

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONArray storedElements = data.optJSONArray("elements");
                if (storedElements != null) elementsJSONArray = storedElements;
            }
            else for (ControlElement element : elements) {
                JSONObject elementJson = element.toJSONObject();
                AutoFitLayout fitted = autoFitLayouts.get(element);
                if (fitted != null) {
                    if (fitted.matchesFitted(element)) {
                        elementJson.put("x", fitted.sourceX);
                        elementJson.put("y", fitted.sourceY);
                        elementJson.put("scale", fitted.sourceScale);
                    }
                    else {
                        // The user deliberately edited the fitted layout, so its current
                        // geometry becomes the new authored layout from this point on.
                        autoFitLayouts.remove(element);
                    }
                }
                elementsJSONArray.put(elementJson);
            }
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONArray storedControllers = data.optJSONArray("controllers");
                if (storedControllers != null) controllersJSONArray = storedControllers;
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0 || includesSection(data, "physicalController")) {
                data.put("controllers", controllersJSONArray);
            }
            else if (controllersLoaded) data.remove("controllers");

            JSONArray radialMenusJSONArray = new JSONArray();
            if (!radialMenusLoaded && file.isFile()) {
                JSONArray storedRadialMenus = data.optJSONArray("radialMenus");
                if (storedRadialMenus != null) radialMenusJSONArray = storedRadialMenus;
            }
            else {
                for (RadialMenu menu : radialMenus) {
                    JSONObject menuJSONObject = menu.toJSONObject();
                    if (menuJSONObject != null) radialMenusJSONArray.put(menuJSONObject);
                }
            }
            if (radialMenusJSONArray.length() > 0 || includesSection(data, "radialMenu")) {
                data.put("radialMenus", radialMenusJSONArray);
            }
            else if (radialMenusLoaded) data.remove("radialMenus");

            if (!FileUtils.writeString(file, data.toString())) {
                Log.e("ControlsProfile", "Failed to write profile: " + name + " (ID: " + id + ")");
                return false;
            }
            Log.d("ControlsProfile", "Profile saved successfully: " + name + " (controllers: " + controllersJSONArray.length() + ", elements: " + elementsJSONArray.length() + ")");
            return true;
        }
        catch (Exception e) {
            Log.e("ControlsProfile", "Failed to save profile: " + name + " (ID: " + id + ")", e);
            return false;
        }
    }

    private static boolean includesSection(JSONObject data, String section) {
        JSONArray included = data.optJSONArray("includedSections");
        if (included == null) return false;
        for (int i = 0; i < included.length(); i++) {
            if (section.equals(included.optString(i))) return true;
        }
        return false;
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

    public RadialMenu getDefaultRadialMenu() {
        if (!radialMenusLoaded) loadRadialMenus();
        if (radialMenus.isEmpty()) {
            radialMenus.add(RadialMenu.createDefault());
            radialMenusLoaded = true;
        }
        return radialMenus.get(0);
    }

    public void setDefaultRadialMenu(RadialMenu menu) {
        if (!radialMenusLoaded) loadRadialMenus();
        radialMenus.clear();
        radialMenus.add(menu != null ? menu : RadialMenu.createDefault());
        radialMenusLoaded = true;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<RadialMenu> loadRadialMenus() {
        radialMenus.clear();
        radialMenusLoaded = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) {
            radialMenus.add(RadialMenu.createDefault());
            radialMenusLoaded = true;
            return radialMenus;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            JSONArray radialMenusJSONArray = profileJSONObject.optJSONArray("radialMenus");
            if (radialMenusJSONArray == null || radialMenusJSONArray.length() == 0) {
                radialMenus.add(RadialMenu.createDefault());
            }
            else {
                for (int i = 0; i < radialMenusJSONArray.length(); i++) {
                    JSONObject radialMenuJSONObject = radialMenusJSONArray.optJSONObject(i);
                    if (radialMenuJSONObject != null) radialMenus.add(RadialMenu.fromJSONObject(radialMenuJSONObject));
                }
                if (radialMenus.isEmpty()) radialMenus.add(RadialMenu.createDefault());
            }
            radialMenusLoaded = true;
        }
        catch (Exception e) {
            Log.e("ControlsProfile", "Failed to load radial menus for profile: " + name + " (ID: " + id + ")", e);
            radialMenus.add(RadialMenu.createDefault());
            radialMenusLoaded = true;
        }

        return radialMenus;
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
                controllersLoaded = true;
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
                    if (controllerBindingJSONObject.has("bindings")) {
                        controllerBinding.setBindingCombo(BindingCombo.fromJsonValue(controllerBindingJSONObject));
                    }
                    else {
                        controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    }
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
            Log.d("ControlsProfile", "Loaded " + controllers.size() + " controllers for profile: " + name);
        }
        catch (Exception e) {
            Log.e("ControlsProfile", "Failed to load controllers for profile: " + name + " (ID: " + id + ")", e);
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        if (elementSourceOverride != null) {
            loadElementsFromJsonInternal(inputControlsView, elementSourceOverride);
            return;
        }

        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Loading elements for profile: " + name + " (ID: " + id + ") from " + file.getAbsolutePath());

        if (!file.isFile()) {
            resetElements();
            Log.d("ControlsProfile", "Profile file does not exist: " + name);
            return;
        }

        try {
            loadElementsFromJsonInternal(inputControlsView, new JSONObject(FileUtils.readString(file)));
        }
        catch (Exception e) {
            resetElements();
            Log.e("ControlsProfile", "Failed to load profile JSON: " + name + " (ID: " + id + ")", e);
        }
    }

    /** Retains and loads an in-memory profile so previews can resize without temporary files. */
    public void loadElementsFromJson(InputControlsView inputControlsView, JSONObject profileJSONObject) {
        elementSourceOverride = profileJSONObject;
        loadElementsFromJsonInternal(inputControlsView, profileJSONObject);
    }

    private void resetElements() {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;
        autoFitLayouts.clear();
    }

    private void loadElementsFromJsonInternal(InputControlsView inputControlsView, JSONObject profileJSONObject) {
        resetElements();

        // Check if view has valid dimensions before loading
        if (inputControlsView.getMaxWidth() == 0 || inputControlsView.getMaxHeight() == 0) {
            Log.w("ControlsProfile", "Cannot load elements - view has no dimensions yet (width: " +
                inputControlsView.getWidth() + ", height: " + inputControlsView.getHeight() + ")");
            return;
        }

        try {
            JSONArray elementsJSONArray = profileJSONObject.optJSONArray("elements");
            if (elementsJSONArray == null) elementsJSONArray = new JSONArray();
            // Existing layouts, including migrated working copies, keep their
            // authored geometry until the user explicitly imports/applies a layout.
            boolean autoFitLayout = profileJSONObject.optBoolean(KEY_AUTO_FIT_LAYOUT, false);
            IdentityHashMap<ControlElement, AutoFitLayout> sourceLayouts = new IdentityHashMap<>();
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                ControlElement element = new ControlElement(inputControlsView);
                try {
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
                } catch (IllegalArgumentException e) {
                    Log.w("ControlsProfile", "Skipping element with unknown type: " + elementJSONObject.getString("type"));
                    continue;
                }
                if (elementJSONObject.has("lookThrough")) {
                    element.setLookThroughSetting(elementJSONObject.getBoolean("lookThrough"));
                }
                else {
                    element.setLookThroughSetting(null);
                }
                element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                double sourceX = elementJSONObject.getDouble("x");
                double sourceY = elementJSONObject.getDouble("y");
                float sourceScale = (float)elementJSONObject.getDouble("scale");
                element.setX((int)(sourceX * inputControlsView.getMaxWidth()));
                element.setY((int)(sourceY * inputControlsView.getMaxHeight()));
                element.setScale(sourceScale);
                element.setText(elementJSONObject.getString("text"));
                element.setIconId(elementJSONObject.getInt("iconId"));
                if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));
                if (elementJSONObject.has("scrollLocked")) element.setScrollLocked(elementJSONObject.getBoolean("scrollLocked"));

                if (elementJSONObject.has("shooterMovementType")) element.setShooterMovementType(elementJSONObject.getString("shooterMovementType"));
                if (elementJSONObject.has("shooterLookType")) element.setShooterLookType(elementJSONObject.getString("shooterLookType"));
                if (elementJSONObject.has("shooterLookSensitivity")) element.setShooterLookSensitivity((float)elementJSONObject.getDouble("shooterLookSensitivity"));
                if (elementJSONObject.has("shooterJoystickSize")) element.setShooterJoystickSize((float)elementJSONObject.getDouble("shooterJoystickSize"));
                if (elementJSONObject.has("buttonColor")) {
                    element.setButtonColor(ControlElement.parseRgbColor(elementJSONObject.get("buttonColor"), ControlElement.DEFAULT_BUTTON_COLOR));
                }
                if (elementJSONObject.has("buttonActiveColor")) {
                    element.setButtonActiveColor(ControlElement.parseRgbColor(elementJSONObject.get("buttonActiveColor"), ControlElement.DEFAULT_BUTTON_ACTIVE_COLOR), true);
                }
                if (elementJSONObject.has("buttonOpacity")) element.setButtonOpacity((float)elementJSONObject.getDouble("buttonOpacity"));
                if (elementJSONObject.has("buttonStrokeScale")) element.setButtonStrokeScale((float)elementJSONObject.getDouble("buttonStrokeScale"));
                if (elementJSONObject.has("shooterLookThrough")) element.setShooterLookThrough(elementJSONObject.getBoolean("shooterLookThrough"));

                boolean hasGamepadBinding = true;
                JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
                element.setBindingCount(Math.max(bindingsJSONArray.length(), 4));
                for (int j = 0; j < bindingsJSONArray.length(); j++) {
                    BindingCombo binding = BindingCombo.fromJsonValue(bindingsJSONArray.get(j));
                    element.setBindingComboAt(j, binding);
                    if (!binding.isGamepadOnly()) hasGamepadBinding = false;
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                elements.add(element);
                if (autoFitLayout) sourceLayouts.put(element, new AutoFitLayout(sourceX, sourceY, sourceScale));
            }
            if (autoFitLayout) fitElementsToBounds(inputControlsView, sourceLayouts);
            elementsLoaded = true;
            Log.d("ControlsProfile", "Loaded " + elements.size() + " elements for profile: " + name + " (virtualGamepad: " + virtualGamepad + ")");
        }
        catch (Exception e) {
            Log.e("ControlsProfile", "Failed to load elements for profile: " + name + " (ID: " + id + ")", e);
            e.printStackTrace();
        }
    }

    private void fitElementsToBounds(
            InputControlsView inputControlsView,
            IdentityHashMap<ControlElement, AutoFitLayout> sourceLayouts
    ) {
        int maxWidth = inputControlsView.getMaxWidth();
        int maxHeight = inputControlsView.getMaxHeight();
        if (maxWidth <= 0 || maxHeight <= 0) return;

        for (ControlElement element : elements) {
            int originalX = element.getX();
            int originalY = element.getY();
            float originalScale = element.getScale();
            android.graphics.Rect bounds = element.getBoundingBox();
            if (bounds.width() > maxWidth || bounds.height() > maxHeight) {
                float fitScale = Math.min(
                        (float)maxWidth / Math.max(1, bounds.width()),
                        (float)maxHeight / Math.max(1, bounds.height())
                );
                element.setScale(Math.max(0.1f, element.getScale() * fitScale));
                bounds = element.getBoundingBox();
            }

            int dx = bounds.left < 0 ? -bounds.left :
                    (bounds.right > maxWidth ? maxWidth - bounds.right : 0);
            int dy = bounds.top < 0 ? -bounds.top :
                    (bounds.bottom > maxHeight ? maxHeight - bounds.bottom : 0);
            if (dx != 0) element.setX(element.getX() + dx);
            if (dy != 0) element.setY(element.getY() + dy);
            if (element.getX() != originalX || element.getY() != originalY ||
                    Float.compare(element.getScale(), originalScale) != 0) {
                AutoFitLayout layout = sourceLayouts.get(element);
                if (layout != null) {
                    layout.captureFitted(element);
                    autoFitLayouts.put(element, layout);
                }
            }
        }
    }
}
