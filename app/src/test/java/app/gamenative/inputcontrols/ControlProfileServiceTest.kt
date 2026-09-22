package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GyroSettings
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.TouchGestureConfig
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileServiceTest {
    @Test
    fun bundledProfilesCannotBeRenamedOrDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val builtInNames = ControlProfileService.builtInProfileNames(context)
        val builtIn = manager.profiles.first { it.name.lowercase(Locale.ROOT) in builtInNames }
        val root = Files.createTempDirectory("built-in-profile").toFile()
        val container = Container("built-in-profile").apply { rootDir = root }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ControlProfileService.rename(context, manager, builtIn, "Renamed built-in")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ControlProfileService.deleteProfile(context, container, manager, builtIn)
            }
            assertTrue(ControlsProfile.getProfileFile(context, builtIn.id).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saveCurrentAddsNewCategoriesAndRoundTripsWithoutChangingAnotherGame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("profile-category-update").toFile()
        val first = Container("category-first").apply { rootDir = root.resolve("first").apply { mkdirs() } }
        val other = Container("category-other").apply {
            rootDir = root.resolve("other").apply { mkdirs() }
            setShooterMode(false)
            setTouchscreenMode(false)
        }
        val ids = mutableSetOf<Int>()
        try {
            val target = ControlProfileService.createBlank(context, manager, "Add categories").also { ids += it.id }
            val firstWorking = ControlProfileService.applyProfile(context, first, manager, target).also { ids += it.id }
            val otherWorking = ControlProfileService.applyProfile(context, other, manager, target).also { ids += it.id }
            val otherBefore = ControlProfileService.readProfileJson(context, otherWorking).toString()
            val gyro = GyroSettings(mode = GyroSettings.MODE_MOUSE, lastTarget = GyroSettings.MODE_MOUSE,
                sensitivity = 2.25f, invertY = true, activationMode = GyroSettings.ACTIVATION_TOGGLE)
            val shooter = ShooterModeConfig(lookSensitivityX = 2.5f, invertLookY = true, movementZoneSplit = 0.4f)
            val touch = TouchGestureConfig(longPressEnabled = true, longPressDelay = 700)
            gyro.saveTo(first, persist = false)
            first.setShooterMode(true)
            first.setShooterConfig(shooter.toJson())
            first.setTouchscreenMode(true)
            first.setGestureConfig(touch.toJson())
            val added = setOf(ControlProfileSection.GYRO, ControlProfileSection.SHOOTER, ControlProfileSection.TOUCHSCREEN)
            val saved = ControlProfileService.updateFromCurrent(context, first, manager, target, added)
            val expectedSections = added + ControlProfileSection.ON_SCREEN
            assertEquals(expectedSections, ControlProfileService.sectionsOf(ControlProfileService.readProfileJson(context, saved)))
            val sources = ControlProfileService.appliedSectionSources(context, first, manager)
            expectedSections.forEach { assertEquals(saved.id, sources[it]) }
            assertEquals(firstWorking.id.toString(), first.getExtra("profileId", "0"))
            assertEquals(otherBefore, ControlProfileService.readProfileJson(context, otherWorking).toString())
            assertEquals(GyroSettings.MODE_DISABLED, GyroSettings.fromContainer(other).mode)

            val uri = Uri.fromFile(root.resolve("roundtrip.icp"))
            ControlProfileService.exportProfile(context, saved, expectedSections, uri)
            val imported = ControlProfileService.importProfile(context, uri)
            assertEquals(gyro, GyroSettings.fromJsonObject(imported.json.getJSONObject("gyroSettings")))
            assertEquals(shooter, ShooterModeConfig.fromJson(imported.json.getJSONObject("shooterSettings").getJSONObject("config").toString()))
            assertEquals(touch, TouchGestureConfig.fromJson(imported.json.getJSONObject("touchscreenSettings").getJSONObject("gestures").toString()))

            // Applying just gyro must not import shooter or touchscreen settings with it.
            ControlProfileService.applyProfile(context, other, manager, saved, setOf(ControlProfileSection.GYRO))
            assertEquals(gyro, GyroSettings.fromContainer(other))
            assertFalse(other.isShooterMode)
            assertFalse(other.isTouchscreenMode)
        } finally {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeSaveKeepsExplicitEmptyCategoriesValid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = ControlsProfile.getProfileFile(context, 900_007)
        try {
            FileUtils.writeString(file, JSONObject().apply {
                put("id", 900_007)
                put("name", "Empty categories")
                put("schemaVersion", ControlProfileService.SCHEMA_VERSION)
                put("includedSections", JSONArray().put("onScreen").put("physicalController").put("radialMenu"))
                put("elements", JSONArray())
                put("controllers", JSONArray())
                put("radialMenus", JSONArray())
            }.toString())
            val profile = InputControlsManager.loadProfile(context, file)!!
            profile.controllers.clear()
            profile.loadRadialMenus().clear()
            assertTrue(profile.save())
            val json = JSONObject(FileUtils.readString(file))
            ControlProfileService.validate(json)
            assertEquals(0, json.getJSONArray("controllers").length())
            assertEquals(0, json.getJSONArray("radialMenus").length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun inMemoryProfileElementsReloadWithoutCreatingAProfileFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profileId = -1
        val profileFile = ControlsProfile.getProfileFile(context, profileId)
        val profile = ControlsProfile(context, profileId).apply { name = "Preview" }
        val source = JSONObject().apply {
            put(
                "elements",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "BUTTON")
                        put("shape", "CIRCLE")
                        put("bindings", JSONArray().put("GAMEPAD_BUTTON_A"))
                        put("scale", 1.0)
                        put("x", 0.5)
                        put("y", 0.5)
                        put("toggleSwitch", false)
                        put("text", "")
                        put("iconId", 0)
                    },
                ),
            )
        }
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        val view = InputControlsView(context).apply {
            layout(0, 0, bitmap.width, bitmap.height)
            draw(Canvas(bitmap))
        }

        try {
            profileFile.delete()
            profile.loadElementsFromJson(view, source)
            assertEquals(1, profile.elements.size)

            profile.loadElements(view)

            assertEquals(1, profile.elements.size)
            assertFalse(profileFile.exists())
        } finally {
            bitmap.recycle()
            profileFile.delete()
        }
    }

    @Test
    fun runtimeProfileSave_preservesExtendedIcpSections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profileFile = ControlsProfile.getProfileFile(context, 900_001)
        val source = JSONObject().apply {
            put("id", 900_001)
            put("name", "Extended")
            put("schemaVersion", ControlProfileService.SCHEMA_VERSION)
            put("includedSections", org.json.JSONArray().put("onScreen").put("gyro"))
            put("elements", org.json.JSONArray())
            put("gyroSettings", JSONObject().put("sensitivity", 2.0))
        }

        try {
            FileUtils.writeString(profileFile, source.toString())
            val profile = InputControlsManager.loadProfile(context, profileFile)!!

            profile.save()

            val saved = JSONObject(FileUtils.readString(profileFile))
            assertEquals(ControlProfileService.SCHEMA_VERSION, saved.getInt("schemaVersion"))
            assertEquals("gyro", saved.getJSONArray("includedSections").getString(1))
            assertEquals(2.0, saved.getJSONObject("gyroSettings").getDouble("sensitivity"), 0.0)
        } finally {
            profileFile.delete()
        }
    }

    @Test
    fun ensureWorkingProfile_createsOneHiddenCopyForTheGame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val source = manager.getProfile(0)!!
        val containerRoot = Files.createTempDirectory("control-profile-container").toFile()
        val container = Container("game-123").apply {
            rootDir = containerRoot
            putExtra("profileId", source.id.toString())
        }
        var working: ControlsProfile? = null

        try {
            val created = ControlProfileService.ensureWorkingProfile(context, container, manager)!!
            working = created
            val sameWorking = ControlProfileService.ensureWorkingProfile(context, container, manager)!!

            assertFalse(created.isListed)
            assertEquals(source.id, created.libraryProfileId)
            assertEquals(container.id, created.gameOwnerId)
            assertEquals(created.id, sameWorking.id)
            assertEquals(created.id.toString(), container.getExtra("profileId", ""))
            assertFalse(manager.profiles.any { it.id == created.id })
        } finally {
            working?.let { ControlsProfile.getProfileFile(context, it.id).delete() }
            containerRoot.deleteRecursively()
        }
    }

    @Test
    fun legacyProfileSections_areInferredFromStoredPayloads() {
        val profile = JSONObject(
            """{
                "name":"Legacy",
                "elements":[],
                "controllers":[],
                "radialMenus":[]
            }""".trimIndent(),
        )

        assertEquals(
            setOf(
                ControlProfileSection.ON_SCREEN,
                ControlProfileSection.PHYSICAL_CONTROLLER,
                ControlProfileSection.RADIAL_MENU,
            ),
            ControlProfileService.sectionsOf(profile),
        )
    }

    @Test
    fun declaredSections_overrideLegacyInference() {
        val profile = JSONObject(
            """{
                "name":"Gyro only",
                "includedSections":["gyro"],
                "elements":[],
                "gyroSettings":{}
            }""".trimIndent(),
        )

        assertEquals(
            setOf(ControlProfileSection.GYRO),
            ControlProfileService.sectionsOf(profile),
        )
    }

    @Test
    fun preview_countsOnlyTheActiveControllerSetAndDefaultRadialMenu() {
        val profile = JSONObject(
            """{
                "name":"Mixed",
                "elements":[{},{}],
                "controllers":[
                    {"id":"device-specific","controllerBindings":[{},{},{}]},
                    {"id":"*","controllerBindings":[{}]}
                ],
                "radialMenus":[{"slots":[{},{},{}]},{"slots":[{}]}]
            }""".trimIndent(),
        )

        val preview = ControlProfileService.preview(profile)

        assertEquals(2, preview.elementCount)
        assertEquals(1, preview.physicalBindingCount)
        assertEquals(3, preview.radialSlotCount)
    }

    @Test
    fun profileLoader_readsPerGameWorkingCopyMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = """{
            "id":42,
            "name":"Working copy",
            "listed":false,
            "libraryProfileId":7,
            "gameOwnerId":"game-123",
            "elements":[]
        }""".trimIndent()

        val profile = InputControlsManager.loadProfile(
            context,
            ByteArrayInputStream(json.toByteArray()),
        )!!

        assertEquals(42, profile.id)
        assertEquals("Working copy", profile.name)
        assertFalse(profile.isListed)
        assertEquals(7, profile.libraryProfileId)
        assertEquals("game-123", profile.gameOwnerId)
    }

    @Test
    fun validation_rejectsUnknownNestedControlEnums() {
        val profile = JSONObject().apply {
            put("schemaVersion", ControlProfileService.SCHEMA_VERSION)
            put("name", "Malformed")
            put("includedSections", JSONArray().put("onScreen"))
            put(
                "elements",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "BUTTON")
                        put("shape", "NOT_A_SHAPE")
                        put("toggleSwitch", false)
                        put("x", 0.5)
                        put("y", 0.5)
                        put("scale", 1.0)
                        put("text", "")
                        put("iconId", 0)
                        put("bindings", JSONArray().put("NONE"))
                    },
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            ControlProfileService.validate(profile)
        }
    }

    @Test
    fun profileLoader_ignoresMalformedFieldTypes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val malformed = """{"id":42,"name":{},"elements":[]}"""
        val missingId = """{"name":"Missing ID","elements":[]}"""

        assertNull(
            InputControlsManager.loadProfile(
                context,
                ByteArrayInputStream(malformed.toByteArray()),
            ),
        )
        assertNull(
            InputControlsManager.loadProfile(
                context,
                ByteArrayInputStream(missingId.toByteArray()),
            ),
        )
    }

    @Test
    fun installedLoader_ignoresTemporaryAndMismatchedProfileFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profilesDir = InputControlsManager.getProfilesDir(context)
        val mismatchedId = 999_999_990
        val embeddedId = mismatchedId + 1
        val mismatched = ControlsProfile.getProfileFile(context, mismatchedId)
        val temporary = profilesDir.resolve("controls-${embeddedId + 1}-interrupted.tmp")
        try {
            assertTrue(FileUtils.writeString(mismatched, """{"id":$embeddedId,"name":"Mismatched"}"""))
            assertTrue(FileUtils.writeString(temporary, """{"id":${embeddedId + 1},"name":"Temporary"}"""))
            val manager = InputControlsManager(context)
            assertNull(manager.getProfile(embeddedId))
            assertNull(manager.getProfile(embeddedId + 1))
        } finally {
            mismatched.delete()
            temporary.delete()
        }
    }

    @Test
    fun partialApplications_trackEachSectionSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val firstId = manager.nextProfileId()
        val secondId = manager.nextProfileId()
        val firstFile = ControlsProfile.getProfileFile(context, firstId)
        val secondFile = ControlsProfile.getProfileFile(context, secondId)
        val containerRoot = Files.createTempDirectory("control-profile-sections").toFile()
        val container = Container("section-game").apply {
            rootDir = containerRoot
            putExtra("profileId", "0")
        }

        try {
            FileUtils.writeString(
                firstFile,
                JSONObject().apply {
                    put("id", firstId)
                    put("name", "Layout source")
                    put("listed", true)
                    put("includedSections", JSONArray().put("onScreen").put("gyro"))
                    put("cursorSpeed", 1.5)
                    put("elements", JSONArray())
                    put("gyroSettings", JSONObject().put("sensitivity", 0.75))
                }.toString(),
            )
            FileUtils.writeString(
                secondFile,
                JSONObject().apply {
                    put("id", secondId)
                    put("name", "Gyro source")
                    put("listed", true)
                    put("includedSections", JSONArray().put("gyro"))
                    put(
                        "gyroSettings",
                        GyroSettings(mode = GyroSettings.MODE_MOUSE, sensitivity = 2f).toJsonObject(),
                    )
                }.toString(),
            )
            manager.reloadProfiles()

            ControlProfileService.applyProfile(
                context,
                container,
                manager,
                manager.getProfile(firstId)!!,
                setOf(ControlProfileSection.ON_SCREEN),
            )
            ControlProfileService.applyProfile(
                context,
                container,
                manager,
                manager.getProfile(secondId)!!,
                setOf(ControlProfileSection.GYRO),
            )

            val sources = ControlProfileService.appliedSectionSources(context, container, manager)
            assertEquals(firstId, sources[ControlProfileSection.ON_SCREEN])
            assertEquals(secondId, sources[ControlProfileSection.GYRO])
            GyroSettings(mode = GyroSettings.MODE_MOUSE, sensitivity = 3f).saveTo(container, persist = false)
            assertNull(
                ControlProfileService.appliedSectionSources(context, container, manager)[ControlProfileSection.GYRO],
            )
            GyroSettings(mode = GyroSettings.MODE_MOUSE, sensitivity = 2f).saveTo(container, persist = false)
            val working = manager.getProfile(container.getExtra("profileId", "0").toInt())!!
            val workingJson = ControlProfileService.readProfileJson(context, working)
            assertEquals(1.5, workingJson.getDouble("cursorSpeed"), 0.0)
            assertEquals(2.0, workingJson.getJSONObject("gyroSettings").getDouble("sensitivity"), 0.0)

            ControlProfileService.updateFromCurrent(
                context,
                container,
                manager,
                manager.getProfile(firstId)!!,
                setOf(ControlProfileSection.ON_SCREEN),
            )
            val updatedSource = JSONObject(FileUtils.readString(firstFile))
            assertEquals(0.75, updatedSource.getJSONObject("gyroSettings").getDouble("sensitivity"), 0.0)
        } finally {
            val workingId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
            if (workingId != 0) ControlsProfile.getProfileFile(context, workingId).delete()
            firstFile.delete()
            secondFile.delete()
            containerRoot.deleteRecursively()
        }
    }

    @Test
    fun newlySavedProfile_canBeAppliedForEveryCapturedSection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val containerRoot = Files.createTempDirectory("control-profile-create-apply").toFile()
        val container = Container("created-profile-game").apply {
            rootDir = containerRoot
            putExtra("profileId", "0")
            setTouchscreenMode(true)
            setShooterMode(true)
        }
        val sections = ControlProfileSection.entries.toSet()
        var savedId = 0
        var workingId = 0

        try {
            GyroSettings(
                mode = GyroSettings.MODE_MOUSE,
                sensitivity = 2.25f,
            ).saveTo(container, persist = false)

            val saved = ControlProfileService.saveCurrentAsProfile(
                context,
                container,
                manager,
                "Created and applied",
                sections,
            )
            savedId = saved.id
            val applied = ControlProfileService.applyProfile(
                context,
                container,
                manager,
                saved,
                sections,
            )
            workingId = applied.id

            assertTrue(saved.isListed)
            assertFalse(applied.isListed)
            assertNotEquals(saved.id, applied.id)
            assertEquals(applied.id.toString(), container.getExtra("profileId", "0"))
            assertEquals(
                sections.associateWith { saved.id },
                ControlProfileService.appliedSectionSources(context, container, manager),
            )
            val savedJson = ControlProfileService.readProfileJson(context, saved)
            assertEquals(sections, ControlProfileService.sectionsOf(savedJson))
            assertEquals(2.25, savedJson.getJSONObject("gyroSettings").getDouble("sensitivity"), 0.0)
            assertTrue(savedJson.getJSONObject("touchscreenSettings").getBoolean("enabled"))
            assertTrue(savedJson.getJSONObject("shooterSettings").getBoolean("enabled"))
        } finally {
            if (workingId != 0) ControlsProfile.getProfileFile(context, workingId).delete()
            if (savedId != 0) ControlsProfile.getProfileFile(context, savedId).delete()
            containerRoot.deleteRecursively()
        }
    }

    @Test
    fun migration_givesEachLegacyContainerAnIndependentCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val sourceId = manager.nextProfileId()
        val sourceFile = ControlsProfile.getProfileFile(context, sourceId)
        val firstRoot = Files.createTempDirectory("control-profile-first").toFile()
        val secondRoot = Files.createTempDirectory("control-profile-second").toFile()
        val first = Container("first-game").apply {
            rootDir = firstRoot
            putExtra("profileId", sourceId.toString())
        }
        val second = Container("second-game").apply {
            rootDir = secondRoot
            putExtra("profileId", sourceId.toString())
        }

        try {
            FileUtils.writeString(
                sourceFile,
                JSONObject().apply {
                    put("id", sourceId)
                    put("name", "Shared legacy profile")
                    put("listed", true)
                    put("elements", JSONArray())
                }.toString(),
            )
            manager.reloadProfiles()

            assertEquals(
                2,
                ControlProfileService.migrateReferencedLibraryProfiles(
                    context,
                    manager,
                    listOf(first, second),
                ),
            )
            val firstWorkingId = first.getExtra("profileId", "0").toInt()
            val secondWorkingId = second.getExtra("profileId", "0").toInt()
            assertNotEquals(sourceId, firstWorkingId)
            assertNotEquals(sourceId, secondWorkingId)
            assertNotEquals(firstWorkingId, secondWorkingId)
            assertEquals("first-game", manager.getProfile(firstWorkingId)?.gameOwnerId)
            assertEquals("second-game", manager.getProfile(secondWorkingId)?.gameOwnerId)
        } finally {
            listOf(first, second).forEach {
                val id = it.getExtra("profileId", "0").toIntOrNull() ?: 0
                if (id != sourceId && id != 0) ControlsProfile.getProfileFile(context, id).delete()
            }
            sourceFile.delete()
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    @Test
    fun automaticFit_doesNotRewriteAuthoredLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profileId = 900_002
        val file = ControlsProfile.getProfileFile(context, profileId)
        val bitmap = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)
        val profileJson = JSONObject().apply {
            put("id", profileId)
            put("name", "Large control")
            put(ControlsProfile.KEY_AUTO_FIT_LAYOUT, true)
            put(
                "elements",
                JSONArray().put(
                    JSONObject().apply {
                        put("type", "D_PAD")
                        put("shape", "CIRCLE")
                        put("bindings", JSONArray().put("NONE").put("NONE").put("NONE").put("NONE"))
                        put("scale", 5.0)
                        put("x", 0.5)
                        put("y", 0.5)
                        put("toggleSwitch", false)
                        put("text", "")
                        put("iconId", 0)
                    },
                ),
            )
        }

        try {
            assertTrue(FileUtils.writeString(file, profileJson.toString()))
            val profile = InputControlsManager.loadProfile(context, file)!!
            val view = InputControlsView(context).apply {
                layout(0, 0, bitmap.width, bitmap.height)
                draw(Canvas(bitmap))
            }

            profile.loadElements(view)
            val fittedBounds = profile.elements.single().boundingBox
            assertTrue(fittedBounds.left >= 0)
            assertTrue(fittedBounds.top >= 0)
            assertTrue(fittedBounds.right <= view.maxWidth)
            assertTrue(fittedBounds.bottom <= view.maxHeight)
            assertTrue(profile.save())

            val savedElement = JSONObject(FileUtils.readString(file))
                .getJSONArray("elements")
                .getJSONObject(0)
            assertEquals(0.5, savedElement.getDouble("x"), 0.0)
            assertEquals(0.5, savedElement.getDouble("y"), 0.0)
            assertEquals(5.0, savedElement.getDouble("scale"), 0.0)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun exportedProfile_importsWithTheSameSelectedSections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val profileId = manager.nextProfileId()
        val profileFile = ControlsProfile.getProfileFile(context, profileId)
        val exportFile = Files.createTempFile("control-profile-roundtrip", ".icp").toFile()

        try {
            assertTrue(
                FileUtils.writeString(
                    profileFile,
                    JSONObject().apply {
                        put("id", profileId)
                        put("name", "Round trip")
                        put("listed", true)
                        put("includedSections", JSONArray().put("onScreen").put("gyro"))
                        put("cursorSpeed", 1.25)
                        put("elements", JSONArray())
                        put("gyroSettings", JSONObject().put("sensitivity", 1.75))
                    }.toString(),
                ),
            )
            manager.reloadProfiles()
            val profile = manager.getProfile(profileId)!!

            ControlProfileService.exportProfile(
                context,
                profile,
                setOf(ControlProfileSection.GYRO),
                Uri.fromFile(exportFile),
            )
            val imported = ControlProfileService.importProfile(context, Uri.fromFile(exportFile))

            assertEquals(setOf(ControlProfileSection.GYRO), imported.sections)
            assertEquals(1.75, imported.json.getJSONObject("gyroSettings").getDouble("sensitivity"), 0.0)
            assertFalse(imported.json.has("elements"))
        } finally {
            profileFile.delete()
            exportFile.delete()
        }
    }

    @Test
    fun profileIds_areNotReusedWhileWorkingCopiesReferenceThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workingId = 900_003
        val referencedId = 950_000
        val file = ControlsProfile.getProfileFile(context, workingId)

        try {
            assertTrue(
                FileUtils.writeString(
                    file,
                    JSONObject().apply {
                        put("id", workingId)
                        put("name", "Working copy")
                        put("listed", false)
                        put("gameOwnerId", "game")
                        put("sectionSources", JSONObject().put("onScreen", referencedId))
                        put("elements", JSONArray())
                    }.toString(),
                ),
            )
            val manager = InputControlsManager(context)

            assertTrue(manager.nextProfileId() > referencedId)
        } finally {
            file.delete()
        }
    }
}
