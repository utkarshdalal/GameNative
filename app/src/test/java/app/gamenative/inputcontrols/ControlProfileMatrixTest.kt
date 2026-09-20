package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GyroSettings
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.TouchGestureConfig
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises every non-empty selection, not just individual categories or all six. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileMatrixTest(private val mask: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "category mask {0}")
        fun selections(): List<Array<Int>> = (1 until 64).map { arrayOf(it) }
    }

    @Test
    fun selectedCategoriesRoundTripAndApplyWithoutChangingOtherCategoriesOrGames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("control-matrix-$mask-").toFile()
        val ids = mutableSetOf<Int>()
        val all = ControlProfileSection.entries.toSet()
        val selected = all.filterTo(mutableSetOf()) { mask and (1 shl it.ordinal) != 0 }
        fun container(id: String) = Container(id).apply { rootDir = root.resolve(id).apply { mkdirs() } }
        fun install(json: JSONObject): ControlsProfile =
            ControlProfileService.installImported(manager, ControlProfileService.preview(json)).also { ids += it.id }

        try {
            val baselineJson = fixture("Baseline $mask", false)
            val incomingJson = fixture("Incoming $mask", true)
            val baseline = install(baselineJson)
            val incoming = install(incomingJson)
            val game = container("matrix-game-$mask")
            val other = container("matrix-other-$mask")
            val working = ControlProfileService.applyProfile(context, game, manager, baseline).also { ids += it.id }
            val otherWorking = ControlProfileService.applyProfile(context, other, manager, baseline).also { ids += it.id }
            val otherBefore = ControlProfileService.readProfileJson(context, otherWorking).toString()

            val uri = Uri.fromFile(root.resolve("selected.icp"))
            ControlProfileService.exportProfile(context, incoming, selected, uri)
            val exported = ControlProfileService.importProfile(context, uri)
            assertEquals(selected, exported.sections)
            payloadKeys.forEach { (section, keys) ->
                keys.forEach { key -> assertEquals("export $key", section in selected, exported.json.has(key)) }
            }
            listOf("listed", "libraryProfileId", "gameOwnerId", "sectionSources").forEach {
                assertFalse("private metadata $it", exported.json.has(it))
            }
            val imported = install(exported.json)
            assertNotEquals(incoming.id, imported.id)
            assertNotEquals(incoming.name, imported.name)
            val applied = ControlProfileService.applyProfile(context, game, manager, imported, selected)
            assertEquals(working.id, applied.id)
            val actual = ControlProfileService.readProfileJson(context, applied)
            ControlProfileService.validate(actual)
            payloadKeys.forEach { (section, keys) ->
                val expected = if (section in selected) incomingJson else baselineJson
                keys.forEach { key -> assertJsonValueEquals("apply $key", expected.get(key), actual.get(key)) }
            }
            assertEquals(
                all.associateWith { if (it in selected) imported.id else baseline.id },
                ControlProfileService.appliedSectionSources(context, game, manager),
            )
            assertEquals(otherBefore, ControlProfileService.readProfileJson(context, otherWorking).toString())
            assertEquals(gyro(ControlProfileSection.GYRO in selected), GyroSettings.fromContainer(game))
            assertEquals(ControlProfileSection.TOUCHSCREEN in selected, game.isTouchscreenMode)
            assertEquals(touch(ControlProfileSection.TOUCHSCREEN in selected), TouchGestureConfig.fromJson(game.gestureConfig))
            assertEquals(ControlProfileSection.SHOOTER in selected, game.isShooterMode)
            assertEquals(shooter(ControlProfileSection.SHOOTER in selected), ShooterModeConfig.fromJson(game.shooterConfig))

            // Capture reads the current per-game values rather than stale library settings.
            val captured = ControlProfileService.saveCurrentAsProfile(context, game, manager, "Captured $mask", selected)
                .also { ids += it.id }
            val capturedJson = ControlProfileService.readProfileJson(context, captured)
            assertEquals(selected, ControlProfileService.sectionsOf(capturedJson))
            payloadKeys.filterKeys { it in selected }.values.flatten().forEach { key ->
                assertJsonValueEquals("capture $key", actual.get(key), capturedJson.get(key))
            }

            // Updating/deleting the shared source must leave this game's working copy intact.
            val workingBefore = actual.toString()
            ControlProfileService.updateFromCurrent(context, other, manager, imported, selected)
            assertEquals(workingBefore, ControlProfileService.readProfileJson(context, applied).toString())
            ControlProfileService.deleteProfile(context, other, manager, imported)
            assertEquals(workingBefore, ControlProfileService.readProfileJson(context, applied).toString())
        } finally {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }

    private fun assertJsonValueEquals(label: String, expected: Any, actual: Any) {
        // JSON serializers legitimately write integral doubles as integers (1.0 -> 1).
        assertEquals(label, JSONObject().put("value", expected).toString(), JSONObject().put("value", actual).toString())
    }

    private val payloadKeys = mapOf(
        ControlProfileSection.ON_SCREEN to listOf("cursorSpeed", "elements"),
        ControlProfileSection.PHYSICAL_CONTROLLER to listOf("controllers"),
        ControlProfileSection.RADIAL_MENU to listOf("radialMenus"),
        ControlProfileSection.GYRO to listOf("gyroSettings"),
        ControlProfileSection.TOUCHSCREEN to listOf("touchscreenSettings"),
        ControlProfileSection.SHOOTER to listOf("shooterSettings"),
    )

    private fun gyro(alternate: Boolean) = GyroSettings(
        mode = GyroSettings.MODE_MOUSE,
        lastTarget = GyroSettings.MODE_MOUSE,
        sensitivity = if (alternate) 2.25f else 1.25f,
        invertY = alternate,
        activationMode = GyroSettings.ACTIVATION_TOGGLE,
    )

    private fun touch(alternate: Boolean) = TouchGestureConfig(longPressEnabled = true, longPressDelay = if (alternate) 700 else 500)
    private fun shooter(alternate: Boolean) = ShooterModeConfig(lookSensitivityX = if (alternate) 2.5f else 1.5f, invertLookY = alternate)

    private fun fixture(name: String, alternate: Boolean) = JSONObject().apply {
        put("schemaVersion", 1)
        put("name", name)
        put("includedSections", JSONArray(ControlProfileSection.entries.map { it.wireName }))
        put("cursorSpeed", if (alternate) 1.5 else 1.0)
        put("elements", JSONArray().put(JSONObject().apply {
            put("type", "BUTTON"); put("shape", "CIRCLE"); put("toggleSwitch", false)
            put("x", 0.8); put("y", 0.4); put("scale", 1.0); put("iconId", 0)
            put("text", if (alternate) "Alternate" else "Baseline")
            put("buttonColor", if (alternate) "#123456" else "#654321")
            put("buttonActiveColor", "#ABCDEF"); put("buttonOpacity", 0.65); put("buttonStrokeScale", 1.25)
            put("lookThrough", true)
            put("bindings", JSONArray().put(if (alternate) "KEY_B" else "KEY_A"))
        }))
        put("controllers", JSONArray().put(JSONObject().apply {
            put("id", "matrix-controller"); put("name", "Matrix controller")
            put("controllerBindings", JSONArray().put(JSONObject().put("keyCode", 96).put("binding", if (alternate) "KEY_B" else "KEY_A")))
        }))
        put("radialMenus", JSONArray().put(JSONObject().apply {
            put("id", "default"); put("name", "Radial menu")
            put("slots", JSONArray().put(JSONObject().put("label", name).put("binding", if (alternate) "KEY_B" else "KEY_A")))
        }))
        put("gyroSettings", gyro(alternate).toJsonObject())
        put("touchscreenSettings", JSONObject().put("enabled", alternate).put("gestures", JSONObject(touch(alternate).toJson())))
        put("shooterSettings", JSONObject().put("enabled", alternate).put("config", JSONObject(shooter(alternate).toJson())))
    }
}
