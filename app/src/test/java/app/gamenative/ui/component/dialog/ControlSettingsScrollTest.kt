package app.gamenative.ui.component.dialog

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.gamenative.data.GyroSettings
import app.gamenative.data.ShooterModeConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h400dp-land")
class ControlSettingsScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun gyroBottomDoesNotJumpWhenStartingAnotherTouch() {
        compose.setContent {
            MaterialTheme {
                GyroSettingsDialog(GyroSettings(mode = GyroSettings.MODE_MOUSE), true,
                    { true }, {}, {}, {})
            }
        }
        assertBottomStaysPut("Invert vertical gyro")
    }

    @Test
    fun shooterBottomDoesNotJumpWhenStartingAnotherTouch() {
        compose.setContent {
            MaterialTheme { ShooterModeSettingsDialog(ShooterModeConfig(), 0.4f, {}, {}) }
        }
        assertBottomStaysPut("Outer Ring Sprint")
    }

    @Test
    fun touchingPreviewLeavesScrollingToItsParent() {
        compose.setContent {
            MaterialTheme {
                ControlSettingsDialog({}) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { ControlLayoutPreview(JSONObject("{\"name\":\"Preview\",\"elements\":[]}"), "1280x720") }
                        item { Text("After preview") }
                    }
                }
            }
        }
        val list = compose.onNode(hasScrollAction())
        list.performTouchInput { click(center) }
        list.performTouchInput { swipeUp() }
        compose.onNodeWithText("After preview").assertIsDisplayed()
    }

    private fun assertBottomStaysPut(label: String) {
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText(label))
        repeat(3) {
            list.performTouchInput { swipe(Offset(2f, height - 15f), Offset(2f, 20f), 300) }
        }
        val bottom = compose.onNodeWithText(label)
        val before = bottom.fetchSemanticsNode().boundsInRoot.top
        repeat(3) {
            list.performTouchInput { click(Offset(2f, height - 15f)) }
            compose.waitForIdle()
            assertEquals(before, bottom.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }
}
