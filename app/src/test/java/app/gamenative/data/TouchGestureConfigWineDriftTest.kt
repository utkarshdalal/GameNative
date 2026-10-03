package app.gamenative.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

// cursorMode is the one TouchGestureConfig field that is html5-only -- wine TouchpadView never
// reads it (relative-cursor synthesis is a touch.js concern). if this fails, a com.winlator.*
// edit absorbed the html5-only field. file-text grep only, so no TouchpadView class-load.
class TouchGestureConfigWineDriftTest {

    @Test fun touchpad_view_does_not_consume_html5_only_cursor_mode() {
        val source = readTouchpadViewSource()
        assertFalse(
            "TouchpadView.java reads gestureConfig.getCursorMode() — html5-only, must stay out of wine path",
            Regex("""\bgestureConfig\.getCursorMode\(""").containsMatchIn(source),
        )
        assertFalse(
            "TouchpadView.java references CURSOR_MODE_* constants — html5-only",
            source.contains("CURSOR_MODE_"),
        )
    }

    private fun readTouchpadViewSource(): String {
        val candidates = listOf(
            File("src/main/java/com/winlator/widget/TouchpadView.java"),
            File("app/src/main/java/com/winlator/widget/TouchpadView.java"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("TouchpadView.java not found; tried ${candidates.map { it.absolutePath }}")
    }
}
