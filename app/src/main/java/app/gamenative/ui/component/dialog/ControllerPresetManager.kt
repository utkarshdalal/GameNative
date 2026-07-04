package app.gamenative.ui.component.dialog

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import app.gamenative.PrefManager
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object ControllerPresetManager {

    private const val USER_PRESETS_DIR = "controller_presets"
    const val HOME_KEY = KeyEvent.KEYCODE_BUTTON_MODE
    val HOME_BINDING = Binding.OPEN_NAVIGATION_MENU
    const val CONTROLLER_PRESET_APPLIED_KEY = "controllerPresetApplied"
    const val LAYOUT_PRESET_APPLIED_KEY = "layoutPresetApplied"

    data class ControllerPreset(
        val name: String,
        val bindings: Map<Int, Binding>,
        val isFactory: Boolean,
    )

    // android keycodes for face/shoulder/menu/thumbstick buttons
    private const val BTN_A = KeyEvent.KEYCODE_BUTTON_A           // 96
    private const val BTN_B = KeyEvent.KEYCODE_BUTTON_B           // 97
    private const val BTN_X = KeyEvent.KEYCODE_BUTTON_X           // 99
    private const val BTN_Y = KeyEvent.KEYCODE_BUTTON_Y           // 100
    private const val BTN_L1 = KeyEvent.KEYCODE_BUTTON_L1         // 102
    private const val BTN_R1 = KeyEvent.KEYCODE_BUTTON_R1         // 103
    private const val BTN_L2 = KeyEvent.KEYCODE_BUTTON_L2         // 104
    private const val BTN_R2 = KeyEvent.KEYCODE_BUTTON_R2         // 105
    private const val BTN_L3 = KeyEvent.KEYCODE_BUTTON_THUMBL     // 106
    private const val BTN_R3 = KeyEvent.KEYCODE_BUTTON_THUMBR     // 107
    private const val BTN_START = KeyEvent.KEYCODE_BUTTON_START   // 108
    private const val BTN_SELECT = KeyEvent.KEYCODE_BUTTON_SELECT // 109
    private const val BTN_HOME = HOME_KEY                          // 110
    private const val DPAD_UP = KeyEvent.KEYCODE_DPAD_UP          // 19
    private const val DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN      // 20
    private const val DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT      // 21
    private const val DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT    // 22

    // axis keycodes — getKeyCodeForAxis inverts Y/RZ so these match runtime behavior,
    // NOT the constant names (AXIS_Y_NEGATIVE = raw negative Y = stick up, but at runtime
    // stick up produces AXIS_Y_POSITIVE via getKeyCodeForAxis's sign inversion)
    private const val LS_UP = ExternalControllerBinding.AXIS_Y_POSITIVE.toInt()    // -4
    private const val LS_DOWN = ExternalControllerBinding.AXIS_Y_NEGATIVE.toInt()  // -3
    private const val LS_LEFT = ExternalControllerBinding.AXIS_X_NEGATIVE.toInt()  // -1
    private const val LS_RIGHT = ExternalControllerBinding.AXIS_X_POSITIVE.toInt() // -2
    private const val RS_UP = ExternalControllerBinding.AXIS_RZ_POSITIVE.toInt()   // -8
    private const val RS_DOWN = ExternalControllerBinding.AXIS_RZ_NEGATIVE.toInt() // -7
    private const val RS_LEFT = ExternalControllerBinding.AXIS_Z_NEGATIVE.toInt()  // -5
    private const val RS_RIGHT = ExternalControllerBinding.AXIS_Z_POSITIVE.toInt() // -6

    private val PRESET_DEFAULT = ControllerPreset(
        name = "Default (XInput)",
        isFactory = true,
        bindings = mapOf(
            BTN_A to Binding.GAMEPAD_BUTTON_A,
            BTN_B to Binding.GAMEPAD_BUTTON_B,
            BTN_X to Binding.GAMEPAD_BUTTON_X,
            BTN_Y to Binding.GAMEPAD_BUTTON_Y,
            BTN_L1 to Binding.GAMEPAD_BUTTON_L1,
            BTN_R1 to Binding.GAMEPAD_BUTTON_R1,
            BTN_L2 to Binding.GAMEPAD_BUTTON_L2,
            BTN_R2 to Binding.GAMEPAD_BUTTON_R2,
            BTN_L3 to Binding.GAMEPAD_BUTTON_L3,
            BTN_R3 to Binding.GAMEPAD_BUTTON_R3,
            BTN_START to Binding.GAMEPAD_BUTTON_START,
            BTN_SELECT to Binding.GAMEPAD_BUTTON_SELECT,
            BTN_HOME to Binding.OPEN_NAVIGATION_MENU,
            DPAD_UP to Binding.GAMEPAD_DPAD_UP,
            DPAD_DOWN to Binding.GAMEPAD_DPAD_DOWN,
            DPAD_LEFT to Binding.GAMEPAD_DPAD_LEFT,
            DPAD_RIGHT to Binding.GAMEPAD_DPAD_RIGHT,
            LS_UP to Binding.GAMEPAD_LEFT_THUMB_UP,
            LS_DOWN to Binding.GAMEPAD_LEFT_THUMB_DOWN,
            LS_LEFT to Binding.GAMEPAD_LEFT_THUMB_LEFT,
            LS_RIGHT to Binding.GAMEPAD_LEFT_THUMB_RIGHT,
            RS_UP to Binding.GAMEPAD_RIGHT_THUMB_UP,
            RS_DOWN to Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            RS_LEFT to Binding.GAMEPAD_RIGHT_THUMB_LEFT,
            RS_RIGHT to Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
        ),
    )

    private val PRESET_FPS = ControllerPreset(
        name = "FPS (KB+Mouse)",
        isFactory = true,
        bindings = mapOf(
            // left stick → WASD
            LS_UP to Binding.KEY_W,
            LS_DOWN to Binding.KEY_S,
            LS_LEFT to Binding.KEY_A,
            LS_RIGHT to Binding.KEY_D,
            // right stick → mouse
            RS_UP to Binding.MOUSE_MOVE_UP,
            RS_DOWN to Binding.MOUSE_MOVE_DOWN,
            RS_LEFT to Binding.MOUSE_MOVE_LEFT,
            RS_RIGHT to Binding.MOUSE_MOVE_RIGHT,
            // face buttons
            BTN_A to Binding.KEY_SPACE,
            BTN_B to Binding.KEY_E,
            BTN_X to Binding.KEY_R,
            BTN_Y to Binding.KEY_F,
            // d-pad → weapon select
            DPAD_UP to Binding.KEY_1,
            DPAD_DOWN to Binding.KEY_3,
            DPAD_LEFT to Binding.KEY_4,
            DPAD_RIGHT to Binding.KEY_2,
            // shoulder
            BTN_L1 to Binding.MOUSE_SCROLL_DOWN,
            BTN_R1 to Binding.MOUSE_SCROLL_UP,
            // triggers
            BTN_L2 to Binding.MOUSE_RIGHT_BUTTON,
            BTN_R2 to Binding.MOUSE_LEFT_BUTTON,
            // menu
            BTN_START to Binding.KEY_ESC,
            BTN_SELECT to Binding.KEY_TAB,
            BTN_HOME to Binding.OPEN_NAVIGATION_MENU,
            // stick clicks
            BTN_L3 to Binding.KEY_SHIFT_L,
            BTN_R3 to Binding.MOUSE_MIDDLE_BUTTON,
        ),
    )

    private val PRESET_PLATFORMER = ControllerPreset(
        name = "Platformer (KB)",
        isFactory = true,
        bindings = mapOf(
            // left stick → arrows
            LS_UP to Binding.KEY_UP,
            LS_DOWN to Binding.KEY_DOWN,
            LS_LEFT to Binding.KEY_LEFT,
            LS_RIGHT to Binding.KEY_RIGHT,
            // right stick → mouse (for menus/cursor)
            RS_UP to Binding.MOUSE_MOVE_UP,
            RS_DOWN to Binding.MOUSE_MOVE_DOWN,
            RS_LEFT to Binding.MOUSE_MOVE_LEFT,
            RS_RIGHT to Binding.MOUSE_MOVE_RIGHT,
            // face buttons — Z/X/C convention
            BTN_A to Binding.KEY_Z,
            BTN_B to Binding.KEY_X,
            BTN_X to Binding.KEY_C,
            BTN_Y to Binding.KEY_A,
            // d-pad → arrows (same as left stick)
            DPAD_UP to Binding.KEY_UP,
            DPAD_DOWN to Binding.KEY_DOWN,
            DPAD_LEFT to Binding.KEY_LEFT,
            DPAD_RIGHT to Binding.KEY_RIGHT,
            // shoulder
            BTN_L1 to Binding.KEY_Q,
            BTN_R1 to Binding.KEY_W,
            // triggers
            BTN_L2 to Binding.KEY_SHIFT_L,
            BTN_R2 to Binding.KEY_CTRL_L,
            // menu
            BTN_START to Binding.KEY_ESC,
            BTN_SELECT to Binding.KEY_ENTER,
            BTN_HOME to Binding.OPEN_NAVIGATION_MENU,
            // stick clicks
            BTN_L3 to Binding.KEY_SHIFT_L,
            BTN_R3 to Binding.KEY_SPACE,
        ),
    )

    // same as default but A↔B and X↔Y (Nintendo-style layout)
    private val PRESET_FACE_SWAP = ControllerPreset(
        name = "Face Swap (XInput)",
        isFactory = true,
        bindings = PRESET_DEFAULT.bindings + mapOf(
            BTN_A to Binding.GAMEPAD_BUTTON_B,
            BTN_B to Binding.GAMEPAD_BUTTON_A,
            BTN_X to Binding.GAMEPAD_BUTTON_Y,
            BTN_Y to Binding.GAMEPAD_BUTTON_X,
        ),
    )

    private val factoryPresets = listOf(PRESET_DEFAULT, PRESET_FACE_SWAP, PRESET_FPS, PRESET_PLATFORMER)

    fun getDefaultBindings(): Map<Int, Binding> = PRESET_DEFAULT.bindings

    /** Ensures the home/guide button always maps to OPEN_NAVIGATION_MENU. */
    fun ensureHomeBindingInMap(bindings: MutableMap<Int, Binding>) {
        bindings[HOME_KEY] = HOME_BINDING
    }

    /** Replaces all bindings on [ctrl] with the given map. */
    fun replaceBindings(ctrl: ExternalController, bindings: Map<Int, Binding>) {
        ctrl.getControllerBindings().toList().forEach { ctrl.removeControllerBinding(it) }
        for ((keyCode, binding) in bindings) {
            val newBinding = ExternalControllerBinding()
            newBinding.setKeyCode(keyCode)
            newBinding.setBinding(binding)
            ctrl.addControllerBinding(newBinding)
        }
    }

    /**
     * Ensures wildcard controller exists on [profile], copying bindings from the
     * default profile (ID 0) if a new one has to be created.
     */
    fun ensureWildcardController(context: Context, profile: ControlsProfile) {
        if (profile.getController("*") != null) return

        Timber.d("Creating wildcard controller for profile: ${profile.name} (ID: ${profile.id})")
        val ctrl = profile.addController("*")

        // copy from Physical Controller Default profile (ID 0)
        val defaultProfile = InputControlsManager(context).getProfile(0)
        if (defaultProfile != null) {
            val defaultControllers = defaultProfile.getControllers()
            if (defaultControllers.isNotEmpty()) {
                for (binding in defaultControllers[0].getControllerBindings()) {
                    val newBinding = ExternalControllerBinding()
                    newBinding.setKeyCode(binding.getKeyCodeForAxis())
                    newBinding.setBinding(binding.getBinding())
                    ctrl.addControllerBinding(newBinding)
                }
            }
        }

        ensureHomeBinding(ctrl)
        profile.save()
    }

    /**
     * Applies the user's default controller preset to the wildcard controller,
     * replacing its existing bindings. No-op if no default preset is configured.
     */
    fun applyDefaultPreset(context: Context, profile: ControlsProfile): Boolean {
        val presetName = PrefManager.defaultControllerPreset
        if (presetName.isEmpty()) return false

        val preset = getPresetByName(context, presetName) ?: return false
        val ctrl = profile.getController("*") ?: return false

        Timber.d("Applying default controller preset '${preset.name}' to profile ${profile.name}")

        replaceBindings(ctrl, preset.bindings)
        ensureHomeBinding(ctrl)
        profile.save()
        return true
    }

    private fun ensureHomeBinding(ctrl: ExternalController) {
        val existing = ctrl.getControllerBindings().find {
            it.getKeyCodeForAxis() == HOME_KEY
        }
        if (existing == null || existing.getBinding() != HOME_BINDING) {
            if (existing != null) ctrl.removeControllerBinding(existing)
            val home = ExternalControllerBinding()
            home.setKeyCode(HOME_KEY)
            home.setBinding(HOME_BINDING)
            ctrl.addControllerBinding(home)
        }
    }

    fun getUserPresets(context: Context): List<ControllerPreset> {
        val dir = getUserPresetsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { loadPresetFromFile(it, isFactory = false) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun getAllPresets(context: Context): List<ControllerPreset> =
        factoryPresets + getUserPresets(context)

    fun getPresetByName(context: Context, name: String): ControllerPreset? =
        factoryPresets.firstOrNull { it.name == name }
            ?: getUserPresets(context).firstOrNull { it.name == name }

    fun saveUserPreset(context: Context, name: String, bindings: Map<Int, Binding>): Boolean {
        return try {
            val dir = getUserPresetsDir(context)
            dir.mkdirs()
            val file = File(dir, sanitizeFilename(name) + ".json")
            val json = JSONObject()
            json.put("name", name)
            json.put("controllerBindings", bindingsToJson(bindings))
            file.writeText(json.toString(2))
            Timber.i("Saved controller preset: $name")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save controller preset: $name")
            false
        }
    }

    fun renameUserPreset(context: Context, preset: ControllerPreset, newName: String): Boolean {
        return try {
            val dir = getUserPresetsDir(context)
            val oldFile = File(dir, sanitizeFilename(preset.name) + ".json")
            val newFile = File(dir, sanitizeFilename(newName) + ".json")
            val sameFile = oldFile.canonicalPath == newFile.canonicalPath
            if (newFile.exists() && !sameFile) {
                Timber.w("Cannot rename: preset file already exists for '$newName'")
                return false
            }
            val json = JSONObject(oldFile.readText())
            json.put("name", newName)
            newFile.writeText(json.toString(2))
            if (!sameFile && !oldFile.delete()) {
                Timber.w("Failed to delete old preset file after rename: ${oldFile.name}")
                // revert to avoid duplicate presets
                newFile.delete()
                return false
            }
            Timber.i("Renamed controller preset: ${preset.name} -> $newName")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to rename controller preset: ${preset.name}")
            false
        }
    }

    /** Returns a name like "Name (2)" that doesn't conflict with existing presets. */
    fun uniqueName(existing: Set<String>, base: String): String {
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    fun deleteUserPreset(context: Context, preset: ControllerPreset): Boolean {
        val file = File(getUserPresetsDir(context), sanitizeFilename(preset.name) + ".json")
        val deleted = file.delete()
        if (deleted) {
            Timber.i("Deleted controller preset: ${preset.name}")
        } else {
            Timber.w("Failed to delete controller preset: ${preset.name}")
        }
        return deleted
    }

    fun exportBindingsToUri(context: Context, uri: Uri, bindings: Map<Int, Binding>): Boolean {
        return try {
            val json = JSONObject()
            json.put("controllerBindings", bindingsToJson(bindings))
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                Timber.e("Failed to open output stream for export")
                return false
            }
            out.use { it.write(json.toString(2).toByteArray()) }
            Timber.i("Exported controller bindings")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to export controller bindings")
            false
        }
    }

    fun importBindingsFromUri(context: Context, uri: Uri): Map<Int, Binding>? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: return null
            val json = JSONObject(text)
            val arr = json.getJSONArray("controllerBindings")
            val bindings = parseBindingsFromJson(arr)
            if (arr.length() > 0 && bindings.isEmpty()) {
                Timber.w("All ${arr.length()} bindings failed to parse")
                return null
            }
            Timber.i("Imported ${bindings.size} controller bindings")
            bindings
        } catch (e: Exception) {
            Timber.e(e, "Failed to import controller bindings")
            null
        }
    }

    private fun bindingsToJson(bindings: Map<Int, Binding>): JSONArray {
        val arr = JSONArray()
        for ((keyCode, binding) in bindings) {
            val obj = JSONObject()
            obj.put("keyCode", keyCode)
            obj.put("binding", binding.name)
            arr.put(obj)
        }
        return arr
    }

    private fun parseBindingsFromJson(arr: JSONArray): Map<Int, Binding> {
        val bindings = mutableMapOf<Int, Binding>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val keyCode = obj.getInt("keyCode")
                val binding = Binding.fromString(obj.getString("binding"))
                bindings[keyCode] = binding
            } catch (e: Exception) {
                Timber.w(e, "Skipping invalid binding at index $i")
            }
        }
        return bindings
    }

    private fun getUserPresetsDir(context: Context): File =
        File(context.filesDir, USER_PRESETS_DIR)

    private fun loadPresetFromFile(file: File, isFactory: Boolean): ControllerPreset? {
        return try {
            val json = JSONObject(file.readText())
            val arr = json.getJSONArray("controllerBindings")
            val bindings = parseBindingsFromJson(arr)
            if (arr.length() > 0 && bindings.isEmpty()) {
                Timber.w("All ${arr.length()} bindings failed to parse in ${file.name}")
                return null
            }
            ControllerPreset(
                name = json.getString("name"),
                bindings = bindings,
                isFactory = isFactory,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to load controller preset from ${file.name}")
            null
        }
    }

    private val FILENAME_SANITIZE_REGEX = Regex("[^a-zA-Z0-9._-]")

    private fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(FILENAME_SANITIZE_REGEX, "_").take(50).ifEmpty { "preset" }
        // deterministic suffix from original name to avoid collisions between
        // names that sanitize identically (e.g. "My Preset!" vs "My Preset?")
        val hash = name.hashCode().toUInt().toString(16).padStart(8, '0')
        return "${sanitized}_$hash"
    }

    // --- Layout presets (onscreen touch controls) ---

    private const val LAYOUT_PRESETS_DIR = "layout_presets"

    data class LayoutPreset(
        val name: String,
        val elements: JSONArray,
    )

    fun currentLayoutPreset(profile: ControlsProfile): LayoutPreset {
        val arr = JSONArray()
        for (element in profile.elements) arr.put(element.toJSONObject())
        return LayoutPreset(name = profile.name, elements = arr)
    }

    fun getLayoutPresetByName(context: Context, name: String): LayoutPreset? {
        val file = File(getLayoutPresetsDir(context), sanitizeFilename(name) + ".json")
        return if (file.exists()) loadLayoutPresetFromFile(file) else null
    }

    fun applyDefaultLayoutPreset(context: Context, profile: ControlsProfile, icView: InputControlsView): Boolean {
        val presetName = PrefManager.defaultLayoutPreset
        if (presetName.isEmpty()) return false

        val preset = getLayoutPresetByName(context, presetName) ?: return false

        Timber.d("Applying default layout preset '${preset.name}' to profile ${profile.name}")
        applyLayoutPreset(preset, profile, icView)
        profile.save()
        return true
    }

    fun getLayoutPresets(context: Context): List<LayoutPreset> {
        val dir = getLayoutPresetsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { loadLayoutPresetFromFile(it) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun saveLayoutPreset(context: Context, name: String, elements: List<ControlElement>): Boolean {
        return try {
            val dir = getLayoutPresetsDir(context)
            dir.mkdirs()
            val file = File(dir, sanitizeFilename(name) + ".json")
            val arr = JSONArray()
            for (element in elements) arr.put(element.toJSONObject())
            val json = JSONObject()
            json.put("name", name)
            json.put("elements", arr)
            file.writeText(json.toString(2))
            Timber.i("Saved layout preset: $name (${elements.size} elements)")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save layout preset: $name")
            false
        }
    }

    fun deleteLayoutPreset(context: Context, preset: LayoutPreset): Boolean {
        val file = File(getLayoutPresetsDir(context), sanitizeFilename(preset.name) + ".json")
        val deleted = file.delete()
        if (deleted) Timber.i("Deleted layout preset: ${preset.name}")
        else Timber.w("Failed to delete layout preset: ${preset.name}")
        return deleted
    }

    fun renameLayoutPreset(context: Context, preset: LayoutPreset, newName: String): Boolean {
        return try {
            val dir = getLayoutPresetsDir(context)
            val oldFile = File(dir, sanitizeFilename(preset.name) + ".json")
            val newFile = File(dir, sanitizeFilename(newName) + ".json")
            val sameFile = oldFile.canonicalPath == newFile.canonicalPath
            if (newFile.exists() && !sameFile) return false
            val json = JSONObject(oldFile.readText())
            json.put("name", newName)
            newFile.writeText(json.toString(2))
            if (!sameFile && !oldFile.delete()) {
                newFile.delete()
                return false
            }
            Timber.i("Renamed layout preset: ${preset.name} -> $newName")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to rename layout preset: ${preset.name}")
            false
        }
    }

    /**
     * Applies a layout preset to the current profile, replacing all elements.
     * Must be called with a valid [icView] that has non-zero dimensions.
     */
    fun applyLayoutPreset(preset: LayoutPreset, profile: ControlsProfile, icView: InputControlsView) {
        // parse all elements before mutating the profile so a malformed preset
        // doesn't leave the user with an empty layout
        val parsed = mutableListOf<ControlElement>()
        for (i in 0 until preset.elements.length()) {
            val obj = preset.elements.getJSONObject(i)
            val element = ControlElement(icView)
            try {
                element.setType(ControlElement.Type.valueOf(obj.getString("type")))
            } catch (_: IllegalArgumentException) {
                Timber.w("Skipping element with unknown type: ${obj.optString("type")}")
                continue
            }
            try {
                element.setShape(ControlElement.Shape.valueOf(obj.getString("shape")))
            } catch (_: IllegalArgumentException) {
                Timber.w("Skipping element with unknown shape: ${obj.optString("shape")}")
                continue
            }
            element.setToggleSwitch(obj.optBoolean("toggleSwitch", false))
            element.setX((obj.optDouble("x", 0.5) * icView.maxWidth).toInt())
            element.setY((obj.optDouble("y", 0.5) * icView.maxHeight).toInt())
            element.setScale(obj.optDouble("scale", 1.0).toFloat())
            element.setText(obj.optString("text", ""))
            element.setIconId(obj.optInt("iconId", 0))
            if (obj.has("range")) {
                try {
                    element.setRange(ControlElement.Range.valueOf(obj.getString("range")))
                } catch (_: IllegalArgumentException) {
                    Timber.w("Skipping element with unknown range: ${obj.optString("range")}")
                    continue
                }
            }
            if (obj.has("orientation")) element.setOrientation(obj.getInt("orientation").toByte())
            if (obj.has("scrollLocked")) element.isScrollLocked = obj.getBoolean("scrollLocked")
            if (obj.has("shooterMovementType")) element.shooterMovementType = obj.getString("shooterMovementType")
            if (obj.has("shooterLookType")) element.shooterLookType = obj.getString("shooterLookType")
            if (obj.has("shooterLookSensitivity")) element.shooterLookSensitivity = obj.getDouble("shooterLookSensitivity").toFloat()
            if (obj.has("shooterJoystickSize")) element.shooterJoystickSize = obj.getDouble("shooterJoystickSize").toFloat()

            val bindingsArr = obj.optJSONArray("bindings") ?: JSONArray()
            element.setBindingCount(maxOf(bindingsArr.length(), 4))
            for (j in 0 until bindingsArr.length()) {
                try {
                    element.setBindingAt(j, Binding.fromString(bindingsArr.getString(j)))
                } catch (_: Exception) {
                    Timber.w("Skipping invalid binding at element $i index $j: ${bindingsArr.optString(j)}")
                }
            }
            parsed.add(element)
        }

        val elementsToRemove = profile.elements.toList()
        elementsToRemove.forEach { profile.removeElement(it) }
        parsed.forEach { profile.addElement(it) }
        icView.invalidate()
    }

    fun exportLayoutPresetToUri(context: Context, uri: Uri, preset: LayoutPreset): Boolean {
        return try {
            val json = JSONObject()
            json.put("name", preset.name)
            json.put("elements", preset.elements)
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                Timber.e("Failed to open output stream for layout preset export")
                return false
            }
            out.use { it.write(json.toString(2).toByteArray()) }
            Timber.i("Exported layout preset: ${preset.name}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to export layout preset")
            false
        }
    }

    fun importLayoutPresetFromUri(context: Context, uri: Uri): LayoutPreset? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: return null
            val json = JSONObject(text)
            val preset = LayoutPreset(
                name = json.getString("name"),
                elements = json.getJSONArray("elements"),
            )
            if (preset.elements.length() == 0) {
                Timber.w("Imported layout preset has no elements")
                return null
            }
            Timber.i("Imported layout preset: ${preset.name} (${preset.elements.length()} elements)")
            preset
        } catch (e: Exception) {
            Timber.e(e, "Failed to import layout preset")
            null
        }
    }

    private fun getLayoutPresetsDir(context: Context): File =
        File(context.filesDir, LAYOUT_PRESETS_DIR)

    private fun loadLayoutPresetFromFile(file: File): LayoutPreset? {
        return try {
            val json = JSONObject(file.readText())
            val elements = json.getJSONArray("elements")
            if (elements.length() == 0) {
                Timber.w("Layout preset has no elements: ${file.name}")
                return null
            }
            LayoutPreset(
                name = json.getString("name"),
                elements = elements,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to load layout preset from ${file.name}")
            null
        }
    }
}
