package app.gamenative.inputcontrols

import android.content.Context
import android.net.Uri
import app.gamenative.data.GyroSettings
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.TouchGestureConfig
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

enum class ControlProfileSection(val wireName: String) {
    ON_SCREEN("onScreen"),
    PHYSICAL_CONTROLLER("physicalController"),
    RADIAL_MENU("radialMenu"),
    GYRO("gyro"),
    TOUCHSCREEN("touchscreen"),
    SHOOTER("shooter"),
    ;

    companion object {
        fun fromWireName(value: String): ControlProfileSection? = entries.firstOrNull { it.wireName == value }
    }
}

data class ControlProfilePreview(
    val json: JSONObject,
    val name: String,
    val sections: Set<ControlProfileSection>,
    val elementCount: Int,
    val physicalBindingCount: Int,
    val radialSlotCount: Int,
)

/** Reads and writes the portable and installed forms of the versioned .icp format. */
object ControlProfileService {
    const val SCHEMA_VERSION = 2
    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_INCLUDED_SECTIONS = "includedSections"
    private const val KEY_GYRO_SETTINGS = "gyroSettings"
    private const val KEY_TOUCHSCREEN_SETTINGS = "touchscreenSettings"
    private const val KEY_SHOOTER_SETTINGS = "shooterSettings"
    private const val KEY_LISTED = "listed"
    private const val KEY_LIBRARY_PROFILE_ID = "libraryProfileId"
    private const val KEY_GAME_OWNER_ID = "gameOwnerId"

    fun preview(context: Context, profile: ControlsProfile): ControlProfilePreview =
        preview(readProfileJson(context, profile))

    fun preview(json: JSONObject): ControlProfilePreview {
        val controllers = json.optJSONArray("controllers")
        var physicalBindings = 0
        if (controllers != null) {
            for (index in 0 until controllers.length()) {
                physicalBindings += controllers.optJSONObject(index)
                    ?.optJSONArray("controllerBindings")
                    ?.length()
                    ?: 0
            }
        }

        val radialMenus = json.optJSONArray("radialMenus")
        var radialSlots = 0
        if (radialMenus != null) {
            for (index in 0 until radialMenus.length()) {
                radialSlots += radialMenus.optJSONObject(index)?.optJSONArray("slots")?.length() ?: 0
            }
        }

        return ControlProfilePreview(
            json = json,
            name = json.optString("name", "Control Profile"),
            sections = sectionsOf(json),
            elementCount = json.optJSONArray("elements")?.length() ?: 0,
            physicalBindingCount = physicalBindings,
            radialSlotCount = radialSlots,
        )
    }

    fun sectionsOf(json: JSONObject): Set<ControlProfileSection> {
        val declared = json.optJSONArray(KEY_INCLUDED_SECTIONS)
        if (declared != null) {
            return buildSet {
                for (index in 0 until declared.length()) {
                    ControlProfileSection.fromWireName(declared.optString(index))?.let(::add)
                }
            }
        }

        // Legacy profiles always represent an on-screen profile. Optional legacy
        // arrays are included only when present.
        return buildSet {
            add(ControlProfileSection.ON_SCREEN)
            if (json.has("controllers")) add(ControlProfileSection.PHYSICAL_CONTROLLER)
            if (json.has("radialMenus")) add(ControlProfileSection.RADIAL_MENU)
            if (json.has(KEY_GYRO_SETTINGS)) add(ControlProfileSection.GYRO)
            if (json.has(KEY_TOUCHSCREEN_SETTINGS)) add(ControlProfileSection.TOUCHSCREEN)
            if (json.has(KEY_SHOOTER_SETTINGS)) add(ControlProfileSection.SHOOTER)
        }
    }

    fun ensureWorkingProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
    ): ControlsProfile? {
        val selectedId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        val selected = manager.getProfile(selectedId) ?: manager.getProfile(0) ?: manager.profiles.firstOrNull()
        if (selected != null && !selected.isListed && selected.gameOwnerId == container.id) return selected
        selected ?: return null

        val sourceJson = readProfileJson(context, selected)
        val newId = manager.nextProfileId()
        sourceJson.put("id", newId)
        sourceJson.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        sourceJson.put(KEY_LISTED, false)
        sourceJson.put(KEY_LIBRARY_PROFILE_ID, if (selected.isListed) selected.id else selected.libraryProfileId)
        sourceJson.put(KEY_GAME_OWNER_ID, container.id)
        writeProfileJson(context, newId, sourceJson)
        manager.reloadProfiles()

        container.putExtra("profileId", newId.toString())
        container.saveData()
        return manager.getProfile(newId)
    }

    fun applyProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        source: ControlsProfile,
        selectedSections: Set<ControlProfileSection> = sectionsOf(readProfileJson(context, source)),
    ): ControlsProfile? {
        val working = ensureWorkingProfile(context, container, manager) ?: return null
        val sourceJson = readProfileJson(context, source)
        val workingJson = readProfileJson(context, working)
        copySections(sourceJson, workingJson, selectedSections)
        workingJson.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        workingJson.put(KEY_LISTED, false)
        workingJson.put(KEY_LIBRARY_PROFILE_ID, source.id)
        workingJson.put(KEY_GAME_OWNER_ID, container.id)
        workingJson.put(KEY_INCLUDED_SECTIONS, sectionArray(allStoredSections(workingJson)))
        writeProfileJson(context, working.id, workingJson)
        applyContainerSections(container, sourceJson, selectedSections)
        container.saveData()
        manager.reloadProfiles()
        return manager.getProfile(working.id)
    }

    fun createBlank(
        context: Context,
        manager: InputControlsManager,
        name: String,
    ): ControlsProfile {
        val profile = manager.createProfile(name.trim())
        val json = readProfileJson(context, profile).apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_INCLUDED_SECTIONS, sectionArray(setOf(ControlProfileSection.ON_SCREEN)))
            put(KEY_LISTED, true)
        }
        writeProfileJson(context, profile.id, json)
        manager.reloadProfiles()
        return requireNotNull(manager.getProfile(profile.id))
    }

    fun saveCurrentAsProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        name: String,
        sections: Set<ControlProfileSection>,
    ): ControlsProfile {
        val newId = manager.nextProfileId()
        val json = captureCurrentJson(context, container, manager, name.trim(), sections).apply {
            put("id", newId)
            put(KEY_LISTED, true)
            remove(KEY_LIBRARY_PROFILE_ID)
            remove(KEY_GAME_OWNER_ID)
        }
        writeProfileJson(context, newId, json)
        manager.reloadProfiles()
        return requireNotNull(manager.getProfile(newId))
    }

    fun updateFromCurrent(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        target: ControlsProfile,
        sections: Set<ControlProfileSection>,
    ): ControlsProfile {
        val json = captureCurrentJson(context, container, manager, target.name, sections).apply {
            put("id", target.id)
            put(KEY_LISTED, true)
            remove(KEY_LIBRARY_PROFILE_ID)
            remove(KEY_GAME_OWNER_ID)
        }
        writeProfileJson(context, target.id, json)
        manager.reloadProfiles()
        return requireNotNull(manager.getProfile(target.id))
    }

    fun rename(context: Context, manager: InputControlsManager, profile: ControlsProfile, name: String) {
        val json = readProfileJson(context, profile)
        json.put("name", name.trim())
        writeProfileJson(context, profile.id, json)
        manager.reloadProfiles()
    }

    fun importProfile(context: Context, uri: Uri): ControlProfilePreview {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_IMPORT_BYTES + 1)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset > MAX_IMPORT_BYTES) throw IOException("Profile is larger than 2 MB")
            buffer.copyOf(offset)
        } ?: throw IOException("Unable to open profile")

        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        validate(json)
        return preview(json)
    }

    fun installImported(manager: InputControlsManager, preview: ControlProfilePreview): ControlsProfile {
        val installed = JSONObject(preview.json.toString()).apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_INCLUDED_SECTIONS, sectionArray(preview.sections))
        }
        return requireNotNull(manager.importProfile(installed))
    }

    fun exportProfile(
        context: Context,
        profile: ControlsProfile,
        sections: Set<ControlProfileSection>,
        uri: Uri,
    ) {
        val source = readProfileJson(context, profile)
        val exported = JSONObject().apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put("id", 0)
            put("name", source.optString("name", "Control Profile"))
            put(KEY_INCLUDED_SECTIONS, sectionArray(sections))
        }
        copySections(source, exported, sections)
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(exported.toString(2).toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw IOException("Unable to create profile")
    }

    fun isBuiltIn(context: Context, profile: ControlsProfile): Boolean = runCatching {
        context.assets.list("inputcontrols/profiles")?.any { assetName ->
            val asset = context.assets.open("inputcontrols/profiles/$assetName")
            InputControlsManager.loadProfile(context, asset)?.let {
                it.id == profile.id && it.name == profile.name
            } == true
        } == true
    }.getOrDefault(false)

    fun appliedLibraryProfileId(container: Container, manager: InputControlsManager): Int {
        val selectedId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        val selected = manager.getProfile(selectedId) ?: return selectedId
        return if (selected.isListed) selected.id else selected.libraryProfileId
    }

    fun readProfileJson(context: Context, profile: ControlsProfile): JSONObject =
        JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, profile.id)))

    private fun captureCurrentJson(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        name: String,
        sections: Set<ControlProfileSection>,
    ): JSONObject {
        val selectedId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        val active = manager.getProfile(selectedId) ?: manager.getProfile(0)
        val activeJson = active?.let { readProfileJson(context, it) } ?: JSONObject()
        return JSONObject().apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put("name", name)
            put(KEY_INCLUDED_SECTIONS, sectionArray(sections))
            copySections(activeJson, this, sections)
            captureContainerSections(container, this, sections)
        }
    }

    private fun captureContainerSections(
        container: Container,
        destination: JSONObject,
        sections: Set<ControlProfileSection>,
    ) {
        if (ControlProfileSection.GYRO in sections) {
            destination.put(KEY_GYRO_SETTINGS, GyroSettings.fromContainer(container).toJsonObject())
        }
        if (ControlProfileSection.TOUCHSCREEN in sections) {
            destination.put(
                KEY_TOUCHSCREEN_SETTINGS,
                JSONObject().apply {
                    put("enabled", container.isTouchscreenMode)
                    put("gestures", JSONObject(TouchGestureConfig.fromJson(container.gestureConfig).toJson()))
                },
            )
        }
        if (ControlProfileSection.SHOOTER in sections) {
            destination.put(
                KEY_SHOOTER_SETTINGS,
                JSONObject().apply {
                    put("enabled", container.isShooterMode)
                    put("config", JSONObject(ShooterModeConfig.fromJson(container.shooterConfig).toJson()))
                },
            )
        }
    }

    private fun applyContainerSections(
        container: Container,
        source: JSONObject,
        sections: Set<ControlProfileSection>,
    ) {
        if (ControlProfileSection.GYRO in sections) {
            GyroSettings.fromJsonObject(source.optJSONObject(KEY_GYRO_SETTINGS)).saveTo(container)
        }
        if (ControlProfileSection.TOUCHSCREEN in sections) {
            source.optJSONObject(KEY_TOUCHSCREEN_SETTINGS)?.let { settings ->
                container.setTouchscreenMode(settings.optBoolean("enabled", container.isTouchscreenMode))
                settings.optJSONObject("gestures")?.let { container.setGestureConfig(it.toString()) }
            }
        }
        if (ControlProfileSection.SHOOTER in sections) {
            source.optJSONObject(KEY_SHOOTER_SETTINGS)?.let { settings ->
                container.setShooterMode(settings.optBoolean("enabled", container.isShooterMode))
                settings.optJSONObject("config")?.let { container.setShooterConfig(it.toString()) }
            }
        }
    }

    private fun copySections(
        source: JSONObject,
        destination: JSONObject,
        sections: Set<ControlProfileSection>,
    ) {
        if (ControlProfileSection.ON_SCREEN in sections) {
            destination.put("cursorSpeed", source.optDouble("cursorSpeed", ControlsProfile.DEFAULT_CURSOR_SPEED.toDouble()))
            destination.put("elements", deepCopyArray(source.optJSONArray("elements")))
        }
        if (ControlProfileSection.PHYSICAL_CONTROLLER in sections) {
            destination.put("controllers", deepCopyArray(source.optJSONArray("controllers")))
        }
        if (ControlProfileSection.RADIAL_MENU in sections) {
            destination.put("radialMenus", deepCopyArray(source.optJSONArray("radialMenus")))
        }
        if (ControlProfileSection.GYRO in sections) {
            destination.put(KEY_GYRO_SETTINGS, deepCopyObject(source.optJSONObject(KEY_GYRO_SETTINGS)))
        }
        if (ControlProfileSection.TOUCHSCREEN in sections) {
            destination.put(KEY_TOUCHSCREEN_SETTINGS, deepCopyObject(source.optJSONObject(KEY_TOUCHSCREEN_SETTINGS)))
        }
        if (ControlProfileSection.SHOOTER in sections) {
            destination.put(KEY_SHOOTER_SETTINGS, deepCopyObject(source.optJSONObject(KEY_SHOOTER_SETTINGS)))
        }
    }

    private fun allStoredSections(json: JSONObject): Set<ControlProfileSection> = buildSet {
        if (json.has("elements")) add(ControlProfileSection.ON_SCREEN)
        if (json.has("controllers")) add(ControlProfileSection.PHYSICAL_CONTROLLER)
        if (json.has("radialMenus")) add(ControlProfileSection.RADIAL_MENU)
        if (json.has(KEY_GYRO_SETTINGS)) add(ControlProfileSection.GYRO)
        if (json.has(KEY_TOUCHSCREEN_SETTINGS)) add(ControlProfileSection.TOUCHSCREEN)
        if (json.has(KEY_SHOOTER_SETTINGS)) add(ControlProfileSection.SHOOTER)
    }

    private fun validate(json: JSONObject) {
        if (json.optString("name").isBlank()) throw IllegalArgumentException("Profile has no name")
        if (json.has(KEY_SCHEMA_VERSION)) {
            val version = json.optInt(KEY_SCHEMA_VERSION, -1)
            if (version !in 1..SCHEMA_VERSION) {
                throw IllegalArgumentException("Unsupported control profile version")
            }
        }
        if (json.optJSONArray("elements")?.length() ?: 0 > 256) {
            throw IllegalArgumentException("Profile contains too many on-screen controls")
        }
        if (json.optJSONArray("controllers")?.length() ?: 0 > 32) {
            throw IllegalArgumentException("Profile contains too many controllers")
        }
        val sections = sectionsOf(json)
        if (sections.isEmpty()) throw IllegalArgumentException("Profile contains no settings")
        requireArrayPayload(json, sections, ControlProfileSection.ON_SCREEN, "elements")
        requireArrayPayload(json, sections, ControlProfileSection.PHYSICAL_CONTROLLER, "controllers")
        requireArrayPayload(json, sections, ControlProfileSection.RADIAL_MENU, "radialMenus")
        requireObjectPayload(json, sections, ControlProfileSection.GYRO, KEY_GYRO_SETTINGS)
        requireObjectPayload(json, sections, ControlProfileSection.TOUCHSCREEN, KEY_TOUCHSCREEN_SETTINGS)
        requireObjectPayload(json, sections, ControlProfileSection.SHOOTER, KEY_SHOOTER_SETTINGS)
    }

    private fun requireArrayPayload(
        json: JSONObject,
        sections: Set<ControlProfileSection>,
        section: ControlProfileSection,
        key: String,
    ) {
        if (section in sections && json.optJSONArray(key) == null) {
            throw IllegalArgumentException("Profile has invalid $key settings")
        }
    }

    private fun requireObjectPayload(
        json: JSONObject,
        sections: Set<ControlProfileSection>,
        section: ControlProfileSection,
        key: String,
    ) {
        if (section in sections && json.optJSONObject(key) == null) {
            throw IllegalArgumentException("Profile has invalid $key settings")
        }
    }

    private fun sectionArray(sections: Set<ControlProfileSection>): JSONArray = JSONArray().apply {
        ControlProfileSection.entries.filter { it in sections }.forEach { put(it.wireName) }
    }

    private fun deepCopyArray(value: JSONArray?): JSONArray =
        if (value == null) JSONArray() else JSONArray(value.toString())

    private fun deepCopyObject(value: JSONObject?): JSONObject =
        if (value == null) JSONObject() else JSONObject(value.toString())

    private fun writeProfileJson(context: Context, id: Int, json: JSONObject) {
        FileUtils.writeString(ControlsProfile.getProfileFile(context, id), json.toString())
    }
}
