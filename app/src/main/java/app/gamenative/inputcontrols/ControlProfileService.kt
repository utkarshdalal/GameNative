package app.gamenative.inputcontrols

import android.content.Context
import android.net.Uri
import app.gamenative.data.GyroSettings
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.TouchGestureConfig
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.inputcontrols.RadialMenu
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
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
    const val SCHEMA_VERSION = 1
    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_INCLUDED_SECTIONS = "includedSections"
    private const val KEY_GYRO_SETTINGS = "gyroSettings"
    private const val KEY_TOUCHSCREEN_SETTINGS = "touchscreenSettings"
    private const val KEY_SHOOTER_SETTINGS = "shooterSettings"
    private const val KEY_LISTED = "listed"
    private const val KEY_LIBRARY_PROFILE_ID = "libraryProfileId"
    private const val KEY_GAME_OWNER_ID = "gameOwnerId"
    private const val KEY_SECTION_SOURCES = "sectionSources"

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
        val selected = selectedProfile(container, manager)
        if (selected != null && !selected.isListed && selected.gameOwnerId == container.id) return selected
        selected ?: return null

        return createWorkingProfile(context, container, manager, selected)
    }

    private fun selectedProfile(container: Container, manager: InputControlsManager): ControlsProfile? {
        val selectedId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        return manager.getProfile(selectedId) ?: manager.getProfile(0) ?: manager.profiles.firstOrNull()
    }

    private fun createWorkingProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        selected: ControlsProfile,
    ): ControlsProfile {
        val newId = writeWorkingProfile(context, container, manager, selected)
        manager.reloadProfiles()
        return requireNotNull(manager.getProfile(newId)) { "Unable to load per-game control profile" }
    }

    private fun writeWorkingProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        selected: ControlsProfile,
    ): Int {
        val json = workingProfileJson(context, container, manager, selected)
        saveWorkingProfile(context, container, json)
        return json.getInt("id")
    }

    private fun workingProfileJson(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        selected: ControlsProfile,
    ): JSONObject {
        val sourceJson = readProfileJson(context, selected)
        if (!selected.isListed && selected.gameOwnerId == container.id) return sourceJson
        val newId = manager.nextProfileId()
        sourceJson.put("id", newId)
        sourceJson.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        sourceJson.put(KEY_LISTED, false)
        sourceJson.put(KEY_LIBRARY_PROFILE_ID, if (selected.isListed) selected.id else selected.libraryProfileId)
        sourceJson.put(KEY_GAME_OWNER_ID, container.id)
        val inheritedSources = sectionSourcesOf(sourceJson).toMutableMap()
        if (selected.isListed) {
            allStoredSections(sourceJson).forEach { inheritedSources[it] = selected.id }
        }
        writeSectionSources(sourceJson, inheritedSources)
        return sourceJson
    }

    /** Migrates pre-library containers away from directly referencing editable global profiles. */
    fun migrateReferencedLibraryProfiles(context: Context, manager: InputControlsManager): Int =
        migrateReferencedLibraryProfiles(context, manager, ContainerManager(context).containers)

    internal fun migrateReferencedLibraryProfiles(
        context: Context,
        manager: InputControlsManager,
        containers: List<Container>,
    ): Int {
        var migrated = 0
        containers.forEach { candidate ->
            val selectedId = candidate.getExtra("profileId", "0").toIntOrNull() ?: 0
            if (selectedId == 0) return@forEach
            val selected = manager.getProfile(selectedId) ?: return@forEach
            if (selected.isListed || selected.gameOwnerId != candidate.id) {
                writeWorkingProfile(context, candidate, manager, selected)
                migrated++
            }
        }
        if (migrated > 0) manager.reloadProfiles()
        return migrated
    }

    fun deleteWorkingProfilesForContainer(context: Context, containerId: String): Int {
        // Called only by explicit container deletion. Its async callback can also
        // run after a failed deletion, so require a successful directory listing
        // that confirms the container entry (including any symlink) is gone.
        val containerEntries = File(ImageFs.find(context).rootDir, "home").list() ?: return 0
        if ("${ImageFs.USER}-$containerId" in containerEntries) return 0
        val manager = InputControlsManager(context)
        val matches = manager.allProfiles.filter { !it.isListed && it.gameOwnerId == containerId }
        return matches.count { manager.removeProfile(it) }
    }

    fun applyProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        source: ControlsProfile,
        selectedSections: Set<ControlProfileSection> = sectionsOf(readProfileJson(context, source)),
    ): ControlsProfile {
        val sourceJson = readProfileJson(context, source)
        val availableSections = sectionsOf(sourceJson)
        if (selectedSections.isEmpty() || !availableSections.containsAll(selectedSections)) {
            throw IllegalArgumentException("Selected settings are not available in this control profile")
        }
        val selected = selectedProfile(container, manager)
            ?: throw IOException("Unable to create a working control profile")
        val workingJson = workingProfileJson(context, container, manager, selected)
        val workingId = workingJson.getInt("id")
        copySections(sourceJson, workingJson, selectedSections)
        if (ControlProfileSection.ON_SCREEN in selectedSections) {
            workingJson.put(ControlsProfile.KEY_AUTO_FIT_LAYOUT, true)
        }
        workingJson.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        workingJson.put(KEY_LISTED, false)
        workingJson.put(KEY_GAME_OWNER_ID, container.id)
        workingJson.put(KEY_INCLUDED_SECTIONS, sectionArray(allStoredSections(workingJson)))
        recordSectionSource(workingJson, selectedSections, source.id)
        saveWorkingProfile(context, container, workingJson) {
            applyContainerSections(container, sourceJson, selectedSections)
        }
        manager.reloadProfiles()
        return requireNotNull(manager.getProfile(workingId)) {
            "Unable to reload the applied control profile"
        }
    }

    fun createBlank(
        context: Context,
        manager: InputControlsManager,
        name: String,
    ): ControlsProfile {
        val normalizedName = InputControlsManager.normalizeProfileName(name)
        if (manager.hasVisibleProfileNamed(normalizedName, -1)) {
            throw IllegalArgumentException("A control profile with that name already exists")
        }
        val newId = manager.nextProfileId()
        val json = JSONObject().apply {
            put("id", newId)
            put("name", normalizedName)
            put("cursorSpeed", ControlsProfile.DEFAULT_CURSOR_SPEED)
            put("elements", JSONArray())
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_INCLUDED_SECTIONS, sectionArray(setOf(ControlProfileSection.ON_SCREEN)))
            put(KEY_LISTED, true)
        }
        return installNewProfile(context, manager, newId, json)
    }

    fun createAndApply(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        name: String,
        sections: Set<ControlProfileSection>,
        fromCurrent: Boolean,
    ): ControlsProfile {
        val originalProfileId = container.getExtra("profileId", "0")
        val saved = if (fromCurrent) {
            saveCurrentAsProfile(context, container, manager, name, sections)
        } else {
            createBlank(context, manager, name)
        }
        return try {
            applyProfile(context, container, manager, saved, sections)
        } catch (error: Throwable) {
            // applyProfile rolls back its working profile and container changes.
            // Remove the newly-created library entry only when the selection also
            // remained unchanged, so a post-commit reload failure cannot orphan it.
            if (container.getExtra("profileId", "0") == originalProfileId) {
                manager.removeProfile(saved)
            }
            throw error
        }
    }

    fun saveCurrentAsProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        name: String,
        sections: Set<ControlProfileSection>,
    ): ControlsProfile {
        if (sections.isEmpty()) throw IllegalArgumentException("Select at least one control profile section")
        val normalizedName = InputControlsManager.normalizeProfileName(name)
        if (manager.hasVisibleProfileNamed(normalizedName, -1)) {
            throw IllegalArgumentException("A control profile with that name already exists")
        }
        val newId = manager.nextProfileId()
        val json = captureCurrentJson(context, container, manager, normalizedName, sections).apply {
            put("id", newId)
            put(KEY_LISTED, true)
            remove(KEY_LIBRARY_PROFILE_ID)
            remove(KEY_GAME_OWNER_ID)
        }
        return installNewProfile(context, manager, newId, json)
    }

    private fun installNewProfile(
        context: Context,
        manager: InputControlsManager,
        id: Int,
        json: JSONObject,
    ): ControlsProfile {
        val file = ControlsProfile.getProfileFile(context, id)
        try {
            writeProfileJson(context, id, json)
            manager.reloadProfiles()
            return requireNotNull(manager.getProfile(id))
        } catch (error: Throwable) {
            if (file.isFile && !file.delete()) {
                error.addSuppressed(IOException("Unable to remove incomplete control profile: $file"))
            }
            throw error
        }
    }

    fun updateFromCurrent(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        target: ControlsProfile,
        sections: Set<ControlProfileSection>,
    ): ControlsProfile {
        if (sections.isEmpty()) throw IllegalArgumentException("Select at least one control profile section")
        requireMutableProfile(context, target)
        ensureDirectReferenceIsolated(context, container, manager, target.id)
        migrateReferencedLibraryProfiles(context, manager)
        val working = ensureWorkingProfile(context, container, manager)
            ?: throw IOException("Unable to load the working control profile")
        val workingJson = readProfileJson(context, working)
        val captured = captureCurrentJson(context, container, manager, target.name, sections)
        val json = readProfileJson(context, target).apply {
            copySections(captured, this, sections)
            put("id", target.id)
            put("name", target.name)
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_INCLUDED_SECTIONS, sectionArray(sectionsOf(this) + sections))
            put(KEY_LISTED, true)
            remove(KEY_LIBRARY_PROFILE_ID)
            remove(KEY_GAME_OWNER_ID)
            remove(KEY_SECTION_SOURCES)
        }
        // Saving newly selected categories also makes this library entry their source
        // for this game. Other games keep their independent working copies.
        copySections(captured, workingJson, sections)
        workingJson.put(KEY_INCLUDED_SECTIONS, sectionArray(sectionsOf(workingJson) + sections))
        recordSectionSource(workingJson, sections, target.id)
        withProfileWrite(context, target.id, json) {
            writeProfileJson(context, working.id, workingJson)
        }
        manager.reloadProfiles()
        return requireNotNull(manager.getProfile(target.id))
    }

    fun rename(context: Context, manager: InputControlsManager, profile: ControlsProfile, name: String) {
        requireMutableProfile(context, profile)
        val normalizedName = InputControlsManager.normalizeProfileName(name)
        if (manager.hasVisibleProfileNamed(normalizedName, profile.id)) {
            throw IllegalArgumentException("A control profile with that name already exists")
        }
        val json = readProfileJson(context, profile)
        json.put("name", normalizedName)
        writeProfileJson(context, profile.id, json)
        manager.reloadProfiles()
    }

    fun deleteProfile(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        profile: ControlsProfile,
    ) {
        requireMutableProfile(context, profile)
        ensureDirectReferenceIsolated(context, container, manager, profile.id)
        migrateReferencedLibraryProfiles(context, manager)
        if (!manager.removeProfile(profile)) throw IOException("Unable to delete control profile")
    }

    private fun ensureDirectReferenceIsolated(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        profileId: Int,
    ) {
        val selectedId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        if (selectedId == profileId && manager.getProfile(selectedId)?.isListed == true) {
            ensureWorkingProfile(context, container, manager)
        }
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

        val decoded = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val json = JSONObject(decoded)
        validate(json)
        return preview(json)
    }

    fun installImported(manager: InputControlsManager, preview: ControlProfilePreview): ControlsProfile {
        val installed = JSONObject(preview.json.toString()).apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_INCLUDED_SECTIONS, sectionArray(preview.sections))
            remove(ControlsProfile.KEY_AUTO_FIT_LAYOUT)
            if (ControlProfileSection.ON_SCREEN in preview.sections) put(ControlsProfile.KEY_AUTO_FIT_LAYOUT, true)
        }
        validate(installed)
        return requireNotNull(manager.importProfile(installed))
    }

    fun exportProfile(
        context: Context,
        profile: ControlsProfile,
        sections: Set<ControlProfileSection>,
        uri: Uri,
    ) {
        val source = readProfileJson(context, profile)
        if (sections.isEmpty() || !sectionsOf(source).containsAll(sections)) {
            throw IllegalArgumentException("Select settings that exist in this control profile")
        }
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

    fun builtInProfileNames(context: Context): Set<String> = runCatching {
        context.assets.list("inputcontrols/profiles").orEmpty().mapNotNullTo(mutableSetOf()) { assetName ->
            runCatching {
                context.assets.open("inputcontrols/profiles/$assetName").use { asset ->
                    InputControlsManager.loadProfile(context, asset)?.name?.lowercase(Locale.ROOT)
                }
            }.getOrNull()
        }
    }.getOrDefault(emptySet())

    private fun requireMutableProfile(context: Context, profile: ControlsProfile) {
        if (profile.name.lowercase(Locale.ROOT) in builtInProfileNames(context)) {
            throw IllegalArgumentException("Built-in control profiles cannot be changed")
        }
    }

    fun appliedSectionSources(
        context: Context,
        container: Container,
        manager: InputControlsManager,
    ): Map<ControlProfileSection, Int> {
        val selectedId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        val selected = manager.getProfile(selectedId) ?: return emptyMap()
        val workingJson = readProfileJson(context, selected)
        val recorded = if (selected.isListed) {
            sectionsOf(workingJson).associateWith { selected.id }
        } else {
            sectionSourcesOf(workingJson)
        }
        if (recorded.isEmpty()) return emptyMap()
        val currentJson = captureCurrentJson(context, container, manager, selected.name, recorded.keys)
        val sourceJsonById = mutableMapOf<Int, JSONObject>()
        return recorded.filter { (section, sourceId) ->
            val source = manager.getProfile(sourceId) ?: return@filter false
            runCatching {
                val sourceJson = sourceJsonById.getOrPut(sourceId) { readProfileJson(context, source) }
                sectionValuesEqual(sourceJson, currentJson, section)
            }.getOrDefault(false)
        }
    }

    private fun sectionValuesEqual(
        first: JSONObject,
        second: JSONObject,
        section: ControlProfileSection,
    ): Boolean {
        val firstSection = JSONObject().also { copySections(first, it, setOf(section)) }
        val secondSection = JSONObject().also { copySections(second, it, setOf(section)) }
        return jsonValuesEqual(firstSection, secondSection)
    }

    private fun jsonValuesEqual(first: Any?, second: Any?): Boolean = when {
        first is JSONObject && second is JSONObject -> {
            val firstKeys = first.keys().asSequence().toSet()
            val secondKeys = second.keys().asSequence().toSet()
            firstKeys == secondKeys && firstKeys.all { key -> jsonValuesEqual(first.opt(key), second.opt(key)) }
        }
        first is JSONArray && second is JSONArray ->
            first.length() == second.length() &&
                (0 until first.length()).all { index -> jsonValuesEqual(first.opt(index), second.opt(index)) }
        first is Number && second is Number -> first.toDouble() == second.toDouble()
        first === JSONObject.NULL || second === JSONObject.NULL -> first === second
        else -> first == second
    }

    fun readProfileJson(context: Context, profile: ControlsProfile): JSONObject {
        val contents = FileUtils.readString(ControlsProfile.getProfileFile(context, profile.id))
        if (contents.isNullOrBlank()) throw IOException("Unable to read control profile")
        return JSONObject(contents.removePrefix("\uFEFF"))
    }

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
            GyroSettings.fromJsonObject(source.optJSONObject(KEY_GYRO_SETTINGS)).saveTo(container, persist = false)
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

    private fun sectionSourcesOf(json: JSONObject): Map<ControlProfileSection, Int> {
        val stored = json.optJSONObject(KEY_SECTION_SOURCES)
        val result = mutableMapOf<ControlProfileSection, Int>()
        if (stored != null) {
            ControlProfileSection.entries.forEach { section ->
                if (stored.has(section.wireName)) {
                    val sourceId = stored.optInt(section.wireName, -1)
                    if (sourceId >= 0) result[section] = sourceId
                }
            }
        }
        if (result.isEmpty()) {
            val legacySource = json.optInt(KEY_LIBRARY_PROFILE_ID, -1)
            if (legacySource >= 0) allStoredSections(json).forEach { result[it] = legacySource }
        }
        return result
    }

    private fun recordSectionSource(json: JSONObject, sections: Set<ControlProfileSection>, sourceId: Int) {
        val sources = sectionSourcesOf(json).toMutableMap()
        sections.forEach { sources[it] = sourceId }
        writeSectionSources(json, sources)
        val distinctSources = sources.values.toSet()
        if (distinctSources.size == 1) json.put(KEY_LIBRARY_PROFILE_ID, distinctSources.first())
        else json.remove(KEY_LIBRARY_PROFILE_ID)
    }

    private fun writeSectionSources(
        json: JSONObject,
        sources: Map<ControlProfileSection, Int>,
    ) {
        json.put(KEY_SECTION_SOURCES, JSONObject().apply {
            ControlProfileSection.entries.forEach { section ->
                sources[section]?.takeIf { it >= 0 }?.let { put(section.wireName, it) }
            }
        })
    }

    internal fun validate(json: JSONObject) {
        val rawName = json.opt("name") as? String
            ?: throw IllegalArgumentException("Profile has no valid name")
        json.put("name", InputControlsManager.normalizeProfileName(rawName))
        if (json.has(KEY_SCHEMA_VERSION)) {
            val version = requiredInt(json, KEY_SCHEMA_VERSION, 1, Int.MAX_VALUE)
            if (version != SCHEMA_VERSION) {
                throw IllegalArgumentException("Unsupported control profile version")
            }
        }

        val declaredSections = json.optJSONArray(KEY_INCLUDED_SECTIONS)
        if (declaredSections != null) {
            val seen = mutableSetOf<ControlProfileSection>()
            for (index in 0 until declaredSections.length()) {
                val value = declaredSections.opt(index) as? String
                    ?: throw IllegalArgumentException("Profile contains an invalid section name")
                val section = ControlProfileSection.fromWireName(value)
                    ?: throw IllegalArgumentException("Profile contains an unsupported section: $value")
                if (!seen.add(section)) throw IllegalArgumentException("Profile contains a duplicate section: $value")
            }
        }
        else if (json.has(KEY_INCLUDED_SECTIONS)) {
            throw IllegalArgumentException("Profile has invalid included sections")
        }

        val sections = sectionsOf(json)
        if (sections.isEmpty()) throw IllegalArgumentException("Profile contains no settings")
        requireArrayPayload(json, sections, ControlProfileSection.ON_SCREEN, "elements")
        requireArrayPayload(json, sections, ControlProfileSection.PHYSICAL_CONTROLLER, "controllers")
        requireArrayPayload(json, sections, ControlProfileSection.RADIAL_MENU, "radialMenus")
        requireObjectPayload(json, sections, ControlProfileSection.GYRO, KEY_GYRO_SETTINGS)
        requireObjectPayload(json, sections, ControlProfileSection.TOUCHSCREEN, KEY_TOUCHSCREEN_SETTINGS)
        requireObjectPayload(json, sections, ControlProfileSection.SHOOTER, KEY_SHOOTER_SETTINGS)

        if (ControlProfileSection.ON_SCREEN in sections) validateElements(json)
        if (ControlProfileSection.PHYSICAL_CONTROLLER in sections) validateControllers(json)
        if (ControlProfileSection.RADIAL_MENU in sections) validateRadialMenus(json)
        if (ControlProfileSection.GYRO in sections) {
            validatePrimitiveSettings(requireNotNull(json.optJSONObject(KEY_GYRO_SETTINGS)), "gyro")
        }
        if (ControlProfileSection.TOUCHSCREEN in sections) {
            val settings = requireNotNull(json.optJSONObject(KEY_TOUCHSCREEN_SETTINGS))
            requiredBoolean(settings, "enabled")
            val gestures = settings.optJSONObject("gestures")
                ?: throw IllegalArgumentException("Profile has invalid touchscreen gestures")
            validatePrimitiveSettings(gestures, "touchscreen gesture")
        }
        if (ControlProfileSection.SHOOTER in sections) {
            val settings = requireNotNull(json.optJSONObject(KEY_SHOOTER_SETTINGS))
            requiredBoolean(settings, "enabled")
            val config = settings.optJSONObject("config")
                ?: throw IllegalArgumentException("Profile has invalid shooter mode settings")
            validatePrimitiveSettings(config, "shooter mode")
        }
    }

    private fun validateElements(json: JSONObject) {
        optionalFiniteNumber(json, "cursorSpeed", 0.01, 20.0)
        val elements = requireNotNull(json.optJSONArray("elements"))
        if (elements.length() > 256) throw IllegalArgumentException("Profile contains too many on-screen controls")
        val types = ControlElement.Type.values().mapTo(mutableSetOf()) { it.name }
        val shapes = ControlElement.Shape.values().mapTo(mutableSetOf()) { it.name }
        val ranges = ControlElement.Range.values().mapTo(mutableSetOf()) { it.name }
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index)
                ?: throw IllegalArgumentException("On-screen control ${index + 1} is invalid")
            requiredEnum(element, "type", types)
            requiredEnum(element, "shape", shapes)
            requiredBoolean(element, "toggleSwitch")
            requiredFiniteNumber(element, "x", 0.0, 1.0)
            requiredFiniteNumber(element, "y", 0.0, 1.0)
            requiredFiniteNumber(element, "scale", 0.1, 5.0)
            requiredString(element, "text", 128)
            requiredInt(element, "iconId", 0, 255)
            element.optStringOrNull("range")?.let {
                if (it !in ranges) throw IllegalArgumentException("On-screen control has an invalid range")
            }
            optionalInt(element, "orientation", 0, 1)
            optionalBoolean(element, "scrollLocked")
            optionalBoolean(element, "lookThrough")
            optionalBoolean(element, "shooterLookThrough")
            element.optStringOrNull("shooterMovementType")
            element.optStringOrNull("shooterLookType")
            optionalFiniteNumber(element, "shooterLookSensitivity", 0.01, 20.0)
            optionalFiniteNumber(element, "shooterJoystickSize", 0.1, 5.0)
            optionalFiniteNumber(element, "buttonOpacity", -1.0, 1.0)
            optionalFiniteNumber(element, "buttonStrokeScale", 0.5, 2.0)
            validateOptionalColor(element, "buttonColor")
            validateOptionalColor(element, "buttonActiveColor")

            val bindings = element.optJSONArray("bindings")
                ?: throw IllegalArgumentException("On-screen control has invalid bindings")
            if (bindings.length() == 0 || bindings.length() > 16) {
                throw IllegalArgumentException("On-screen control has an invalid number of bindings")
            }
            for (bindingIndex in 0 until bindings.length()) validateBinding(bindings.opt(bindingIndex))
        }
    }

    private fun validateControllers(json: JSONObject) {
        val controllers = requireNotNull(json.optJSONArray("controllers"))
        if (controllers.length() > 32) throw IllegalArgumentException("Profile contains too many controllers")
        for (index in 0 until controllers.length()) {
            val controller = controllers.optJSONObject(index)
                ?: throw IllegalArgumentException("Physical controller ${index + 1} is invalid")
            requiredString(controller, "id", 256)
            requiredString(controller, "name", 128)
            val bindings = controller.optJSONArray("controllerBindings")
                ?: throw IllegalArgumentException("Physical controller has invalid bindings")
            if (bindings.length() > 512) throw IllegalArgumentException("Physical controller has too many bindings")
            for (bindingIndex in 0 until bindings.length()) {
                val binding = bindings.optJSONObject(bindingIndex)
                    ?: throw IllegalArgumentException("Physical controller binding is invalid")
                requiredInt(binding, "keyCode", Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                if (binding.has("bindings")) validateBinding(binding)
                else validateBinding(requiredString(binding, "binding", 64))
            }
        }
    }

    private fun validateRadialMenus(json: JSONObject) {
        val menus = requireNotNull(json.optJSONArray("radialMenus"))
        if (menus.length() > 16) throw IllegalArgumentException("Profile contains too many radial menus")
        for (index in 0 until menus.length()) {
            val menu = menus.optJSONObject(index)
                ?: throw IllegalArgumentException("Radial menu ${index + 1} is invalid")
            requiredString(menu, "id", 128)
            requiredString(menu, "name", 128)
            val slots = menu.optJSONArray("slots")
                ?: throw IllegalArgumentException("Radial menu has invalid slots")
            if (slots.length() > RadialMenu.MAX_SLOTS) throw IllegalArgumentException("Radial menu has too many slots")
            for (slotIndex in 0 until slots.length()) {
                val slot = slots.optJSONObject(slotIndex)
                    ?: throw IllegalArgumentException("Radial menu slot is invalid")
                requiredString(slot, "label", 128)
                if (slot.has("bindings")) validateBinding(slot)
                else validateBinding(requiredString(slot, "binding", 64))
            }
        }
    }

    private fun validateBinding(value: Any?) {
        when (value) {
            is String -> if (runCatching { Binding.valueOf(value) }.isFailure) {
                throw IllegalArgumentException("Profile contains an unsupported binding: $value")
            }
            is JSONArray -> {
                if (value.length() > BindingCombo.MAX_BINDINGS) {
                    throw IllegalArgumentException("A binding combination contains too many bindings")
                }
                for (index in 0 until value.length()) validateBinding(value.opt(index))
            }
            is JSONObject -> {
                val bindings = value.optJSONArray("bindings")
                if (bindings != null) {
                    validateBinding(bindings)
                    value.optStringOrNull("mode")?.let { mode ->
                        if (mode != "simultaneous" && mode != "sequence") {
                            throw IllegalArgumentException("Profile contains an unsupported binding mode")
                        }
                    }
                    optionalInt(
                        value,
                        "sequenceDelayMs",
                        BindingCombo.MIN_SEQUENCE_DELAY_MS,
                        BindingCombo.MAX_SEQUENCE_DELAY_MS,
                    )
                }
                else validateBinding(requiredString(value, "binding", 64))
            }
            else -> throw IllegalArgumentException("Profile contains an invalid binding")
        }
    }

    private fun validatePrimitiveSettings(settings: JSONObject, label: String) {
        if (settings.length() > 96) throw IllegalArgumentException("Profile contains too many $label settings")
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.length > 80) throw IllegalArgumentException("Profile contains an invalid $label setting")
            when (val value = settings.opt(key)) {
                is Boolean -> Unit
                is String -> if (value.length > 256) throw IllegalArgumentException("Profile contains an invalid $label value")
                is Number -> if (!value.toDouble().isFinite()) throw IllegalArgumentException("Profile contains an invalid $label number")
                else -> throw IllegalArgumentException("Profile contains an invalid $label value")
            }
        }
    }

    private fun validateOptionalColor(json: JSONObject, key: String) {
        if (!json.has(key)) return
        val value = json.opt(key)
        val valid = value is Number || value is String && Regex("#?(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(value.trim())
        if (!valid) throw IllegalArgumentException("On-screen control has an invalid color")
    }

    private fun requiredString(json: JSONObject, key: String, maxLength: Int): String {
        val value = json.opt(key) as? String
            ?: throw IllegalArgumentException("Profile has an invalid $key value")
        if (value.length > maxLength) throw IllegalArgumentException("Profile has an invalid $key value")
        return value
    }

    private fun requiredEnum(json: JSONObject, key: String, allowed: Set<String>): String =
        requiredString(json, key, 64).also {
            if (it !in allowed) throw IllegalArgumentException("Profile has an unsupported $key value: $it")
        }

    private fun requiredBoolean(json: JSONObject, key: String): Boolean =
        (json.opt(key) as? Boolean) ?: throw IllegalArgumentException("Profile has an invalid $key value")

    private fun optionalBoolean(json: JSONObject, key: String) {
        if (json.has(key)) requiredBoolean(json, key)
    }

    private fun requiredInt(json: JSONObject, key: String, minimum: Int, maximum: Int): Int {
        val number = json.opt(key) as? Number
            ?: throw IllegalArgumentException("Profile has an invalid $key value")
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < minimum || double > maximum) {
            throw IllegalArgumentException("Profile has an invalid $key value")
        }
        return double.toInt()
    }

    private fun optionalInt(json: JSONObject, key: String, minimum: Int, maximum: Int) {
        if (json.has(key)) requiredInt(json, key, minimum, maximum)
    }

    private fun requiredFiniteNumber(
        json: JSONObject,
        key: String,
        minimum: Double,
        maximum: Double,
    ): Double {
        val value = (json.opt(key) as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Profile has an invalid $key value")
        if (!value.isFinite() || value !in minimum..maximum) {
            throw IllegalArgumentException("Profile has an invalid $key value")
        }
        return value
    }

    private fun optionalFiniteNumber(json: JSONObject, key: String, minimum: Double, maximum: Double) {
        if (json.has(key)) requiredFiniteNumber(json, key, minimum, maximum)
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key)) return null
        return opt(key) as? String ?: throw IllegalArgumentException("Profile has an invalid $key value")
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

    /** Commit the profile first, then the atomic container write; restore both live state and profile on failure. */
    private fun saveWorkingProfile(
        context: Context,
        container: Container,
        json: JSONObject,
        updateSettings: () -> Unit = {},
    ) {
        val oldExtraData = container.extraData?.let { JSONObject(it.toString()) }
        val oldTouchscreen = container.isTouchscreenMode
        val oldGestures = container.gestureConfig
        val oldShooter = container.isShooterMode
        val oldShooterConfig = container.shooterConfig
        try {
            withProfileWrite(context, json.getInt("id"), json) {
                container.putExtra("profileId", json.getInt("id").toString())
                updateSettings()
                if (!container.saveDataChecked()) throw IOException("Unable to save control settings to the container")
            }
        } catch (error: Exception) {
            container.extraData = oldExtraData
            container.setTouchscreenMode(oldTouchscreen)
            container.gestureConfig = oldGestures
            container.setShooterMode(oldShooter)
            container.shooterConfig = oldShooterConfig
            throw error
        }
    }

    /** A disk backup lets rollback use a rename even when the subsequent save fails for lack of space. */
    private fun <T> withProfileWrite(context: Context, id: Int, json: JSONObject, operation: () -> T): T {
        val file = ControlsProfile.getProfileFile(context, id)
        // Keep backups outside the library: its legacy loader reads every file, regardless of extension.
        val backup = if (file.exists()) File.createTempFile("control-profile-$id-", ".rollback", context.filesDir) else null
        var written = false
        var keepBackup = false
        try {
            if (backup != null) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            writeProfileJson(context, id, json)
            written = true
            return operation()
        } catch (error: Exception) {
            if (written) {
                try {
                    if (backup != null) {
                        Files.move(backup.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        Files.delete(file.toPath())
                    }
                } catch (rollbackError: Exception) {
                    keepBackup = true
                    val message = if (backup != null) {
                        "Save failed and the previous control profile could not be restored. Backup: ${backup.name}"
                    } else {
                        "Save failed and the incomplete control profile could not be removed"
                    }
                    throw IOException(message, error).apply {
                        addSuppressed(rollbackError)
                    }
                }
            }
            throw error
        } finally {
            if (!keepBackup) backup?.delete()
        }
    }

    private fun writeProfileJson(context: Context, id: Int, json: JSONObject) {
        if (!FileUtils.writeString(ControlsProfile.getProfileFile(context, id), json.toString())) {
            throw IOException("Unable to save control profile")
        }
    }
}
