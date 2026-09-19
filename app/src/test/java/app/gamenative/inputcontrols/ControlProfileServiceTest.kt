package app.gamenative.inputcontrols

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlProfileServiceTest {
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
    fun preview_countsEachControlPayload() {
        val profile = JSONObject(
            """{
                "name":"Mixed",
                "elements":[{},{}],
                "controllers":[
                    {"controllerBindings":[{},{},{}]},
                    {"controllerBindings":[{}]}
                ],
                "radialMenus":[{"slots":[{},{},{}]}]
            }""".trimIndent(),
        )

        val preview = ControlProfileService.preview(profile)

        assertEquals(2, preview.elementCount)
        assertEquals(4, preview.physicalBindingCount)
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
}
