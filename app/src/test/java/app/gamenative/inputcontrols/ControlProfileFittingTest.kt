package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileFittingTest {
    @Test
    fun existingLayoutsKeepTheirGeometryAcrossLoadsAndResizesRegardlessOfSchemaVersion() {
        for (version in listOf(null, 1)) {
            Fixture().use { fixture ->
                val original = fixture.json(fixture.source).apply {
                    if (version != null) put("schemaVersion", version)
                }
                assertTrue(FileUtils.writeString(ControlsProfile.getProfileFile(fixture.context, fixture.source.id), original.toString()))
                fixture.render(fixture.source, fitted = false)
                assertEquals(original.toString(), fixture.json(fixture.source).toString())
            }
        }
    }

    @Test
    fun migrationAndSavingCurrentDoNotEnableFitting() {
        Fixture().use { fixture ->
            val working = fixture.own(ControlProfileService.ensureWorkingProfile(fixture.context, fixture.game, fixture.manager)!!)
            val saved = fixture.own(ControlProfileService.saveCurrentAsProfile(
                fixture.context, fixture.game, fixture.manager, "Saved legacy layout", setOf(ControlProfileSection.ON_SCREEN),
            ))
            val duplicate = fixture.own(fixture.manager.duplicateProfile(fixture.source))
            ControlProfileService.updateFromCurrent(
                fixture.context, fixture.game, fixture.manager, saved, setOf(ControlProfileSection.ON_SCREEN),
            )
            for (profile in listOf(working, saved, duplicate)) {
                assertFalse(fixture.json(profile).optBoolean(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
                fixture.render(profile, fitted = false)
            }
        }
    }

    @Test
    fun applyingOnlyOtherCategoriesDoesNotFitTheExistingLayout() {
        Fixture().use { fixture ->
            val snapshot = fixture.own(ControlProfileService.saveCurrentAsProfile(
                fixture.context, fixture.game, fixture.manager, "All settings", ControlProfileSection.entries.toSet(),
            ))
            for (section in ControlProfileSection.entries - ControlProfileSection.ON_SCREEN) {
                val working = fixture.own(ControlProfileService.applyProfile(
                    fixture.context, fixture.game, fixture.manager, snapshot, setOf(section),
                ))
                assertFalse(fixture.json(working).optBoolean(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
                fixture.render(working, fitted = false)
            }
        }
    }

    @Test
    fun explicitLayoutApplicationFitsAcrossReloadsWithoutRewritingAuthoredGeometry() {
        Fixture().use { fixture ->
            val working = fixture.own(ControlProfileService.applyProfile(
                fixture.context, fixture.game, fixture.manager, fixture.source, setOf(ControlProfileSection.ON_SCREEN),
            ))
            assertTrue(fixture.json(working).getBoolean(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
            fixture.render(working, fitted = true)
            assertTrue(working.save())
            val saved = fixture.json(working).getJSONArray("elements").getJSONObject(0)
            assertEquals(0.0, saved.getDouble("x"), 0.0)
            assertEquals(0.0, saved.getDouble("y"), 0.0)
            assertEquals(5.0, saved.getDouble("scale"), 0.0)
            fixture.manager.reloadProfiles()
            fixture.render(fixture.manager.getProfile(working.id)!!, fitted = true)
            fixture.render(fixture.source, fitted = false)
        }
    }

    @Test
    fun importingALayoutEnablesFittingOnlyForTheNewProfile() {
        Fixture().use { fixture ->
            val uri = Uri.fromFile(fixture.root.resolve("import.icp"))
            ControlProfileService.exportProfile(fixture.context, fixture.source, setOf(ControlProfileSection.ON_SCREEN), uri)
            val preview = ControlProfileService.importProfile(fixture.context, uri)
            assertFalse(preview.json.has(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
            val imported = fixture.own(ControlProfileService.installImported(fixture.manager, preview))
            assertTrue(fixture.json(imported).getBoolean(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
            assertEquals(fixture.source.id.toString(), fixture.game.getExtra("profileId", "0"))
            fixture.render(imported, fitted = true)
            fixture.render(fixture.source, fitted = false)
        }
    }

    @Test
    fun gyroOnlyImportCannotEnableLayoutFitting() {
        Fixture().use { fixture ->
            val snapshot = fixture.own(ControlProfileService.saveCurrentAsProfile(
                fixture.context, fixture.game, fixture.manager, "Gyro only", setOf(ControlProfileSection.GYRO),
            ))
            val preview = ControlProfileService.preview(fixture.json(snapshot).put(ControlsProfile.KEY_AUTO_FIT_LAYOUT, true))
            val imported = fixture.own(ControlProfileService.installImported(fixture.manager, preview))
            assertFalse(fixture.json(imported).has(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
            val working = fixture.own(ControlProfileService.applyProfile(
                fixture.context, fixture.game, fixture.manager, imported, setOf(ControlProfileSection.GYRO),
            ))
            fixture.render(working, fitted = false)
        }
    }

    private class Fixture : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("profile-fitting-").toFile()
        private val ids = mutableSetOf<Int>()
        val source = own(manager.createProfile("Legacy edge layout")).also { profile ->
            val data = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("elements", JSONArray().put(JSONObject().apply {
                    put("type", "D_PAD"); put("shape", "CIRCLE")
                    put("bindings", JSONArray(listOf("NONE", "NONE", "NONE", "NONE")))
                    put("x", 0.0); put("y", 0.0); put("scale", 5.0)
                    put("toggleSwitch", false); put("text", ""); put("iconId", 0)
                }))
            }
            assertTrue(FileUtils.writeString(ControlsProfile.getProfileFile(context, profile.id), data.toString()))
        }
        val game = Container(root.name).apply {
            rootDir = root
            putExtra("profileId", source.id.toString())
        }

        fun own(profile: ControlsProfile) = profile.also { ids += it.id }
        fun json(profile: ControlsProfile) = ControlProfileService.readProfileJson(context, profile)

        fun render(profile: ControlsProfile, fitted: Boolean) {
            val view = InputControlsView(context).apply { setProfile(profile) }
            for ((width, height) in listOf(600 to 120, 900 to 400, 900 to 100)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    view.layout(0, 0, width, height)
                    view.draw(Canvas(bitmap))
                    val element = profile.elements.single()
                    if (fitted) {
                        val bounds = element.boundingBox
                        assertTrue(bounds.left >= 0 && bounds.top >= 0)
                        assertTrue(bounds.right <= width && bounds.bottom <= height)
                    } else {
                        assertEquals(0, element.x.toInt())
                        assertEquals(0, element.y.toInt())
                        assertEquals(5f, element.scale, 0f)
                        assertTrue(element.boundingBox.left < 0)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        override fun close() {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }
}
