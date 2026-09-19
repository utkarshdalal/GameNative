package app.gamenative.ui.component.dialog

import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProfilePreviewGeometryTest {
    @Test
    fun oversizedControlFitsInsideWidePreview() {
        val (halfWidth, halfHeight) = fitPreviewHalfExtents(
            rawHalfWidth = 220f,
            rawHalfHeight = 220f,
            canvasWidth = 300f,
            canvasHeight = 100f,
        )

        assertTrue(halfWidth <= 150f)
        assertTrue(halfHeight <= 50f)
    }
}
