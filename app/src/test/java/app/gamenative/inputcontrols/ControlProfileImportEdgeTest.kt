package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import java.io.IOException
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
class ControlProfileImportEdgeTest {
    @Test
    fun allBundledProfilesPassTheImportValidator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = context.assets.list("inputcontrols/profiles").orEmpty()
        assertTrue(names.isNotEmpty())
        names.forEach { name ->
            val json = context.assets.open("inputcontrols/profiles/$name").bufferedReader().use { JSONObject(it.readText()) }
            try {
                ControlProfileService.validate(json)
            } catch (error: Exception) {
                throw AssertionError("Bundled profile $name cannot be re-imported", error)
            }
        }
    }

    @Test
    fun acceptedUnsignedIconIdRemainsImportableAfterRuntimeSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val json = profile().apply { getJSONArray("elements").getJSONObject(0).put("iconId", 255) }
        ControlProfileService.validate(json)
        val imported = ControlProfileService.installImported(manager, ControlProfileService.preview(json))
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        try {
            val view = InputControlsView(context)
            view.layout(0, 0, 600, 300)
            view.setProfile(imported)
            view.draw(Canvas(bitmap))
            assertEquals(1, imported.elements.size)
            assertTrue(imported.save())
            val saved = ControlProfileService.readProfileJson(context, imported)
            assertEquals(255, saved.getJSONArray("elements").getJSONObject(0).getInt("iconId"))
            ControlProfileService.validate(saved)
        } finally {
            bitmap.recycle()
            ControlsProfile.getProfileFile(context, imported.id).delete()
        }
    }

    @Test
    fun importHandlesBomAndRejectsOversizeAndMalformedFilesWithoutInstallingAnything() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val before = manager.allProfiles.map { it.id }.toSet()
        val file = Files.createTempFile("control-import-boundary-", ".icp").toFile()
        try {
            FileUtils.writeString(file, "\uFEFF${profile()}")
            assertEquals("Edge profile", ControlProfileService.importProfile(context, Uri.fromFile(file)).name)
            FileUtils.writeString(file, " ".repeat(ControlProfileService.MAX_IMPORT_BYTES + 1))
            assertThrows(IOException::class.java) { ControlProfileService.importProfile(context, Uri.fromFile(file)) }
            FileUtils.writeString(file, "{not valid json")
            assertThrows(Exception::class.java) { ControlProfileService.importProfile(context, Uri.fromFile(file)) }
            manager.reloadProfiles()
            assertEquals(before, manager.allProfiles.map { it.id }.toSet())
        } finally {
            file.delete()
        }
    }

    @Test
    fun duplicateAndImportUseUniqueNamesAndRemovePrivateOwnership() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val ids = mutableSetOf<Int>()
        try {
            val json = profile().apply {
                put("listed", false); put("gameOwnerId", "not-this-game")
                put("libraryProfileId", 123); put("sectionSources", JSONObject().put("onScreen", 123))
            }
            val first = ControlProfileService.installImported(manager, ControlProfileService.preview(json)).also { ids += it.id }
            val second = manager.duplicateProfile(first).also { ids += it.id }
            val third = ControlProfileService.installImported(manager, ControlProfileService.preview(json)).also { ids += it.id }
            assertEquals(3, setOf(first.name, second.name, third.name).size)
            listOf(first, second, third).forEach { profile ->
                val stored = ControlProfileService.readProfileJson(context, profile)
                assertTrue(profile.isListed)
                listOf("gameOwnerId", "libraryProfileId", "sectionSources").forEach { assertFalse(stored.has(it)) }
            }
            val before = ControlProfileService.readProfileJson(context, second).toString()
            assertThrows(IllegalArgumentException::class.java) { ControlProfileService.rename(context, manager, second, first.name.uppercase()) }
            assertThrows(IllegalArgumentException::class.java) { ControlProfileService.rename(context, manager, second, " \t\n ") }
            assertEquals(before, ControlProfileService.readProfileJson(context, second).toString())
        } finally {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
        }
    }

    private fun profile() = JSONObject().apply {
        put("name", "Edge profile"); put("schemaVersion", 1)
        put("includedSections", JSONArray().put("onScreen"))
        put("elements", JSONArray().put(JSONObject().apply {
            put("type", "BUTTON"); put("shape", "CIRCLE"); put("bindings", JSONArray().put("KEY_A"))
            put("x", 0.5); put("y", 0.5); put("scale", 1.0)
            put("toggleSwitch", false); put("text", "A"); put("iconId", 0)
        }))
    }
}
