package app.gamenative.ui.component.dialog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.ControlsProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlLayoutPreviewTest {
    private fun profile(iconId: Int = 0) = JSONObject().apply {
        put("name", "Preview")
        put("elements", JSONArray().put(JSONObject().apply {
            put("type", "BUTTON")
            put("shape", "CIRCLE")
            put("bindings", JSONArray().put("GAMEPAD_BUTTON_A"))
            put("scale", 1.0)
            put("x", 0.5)
            put("y", 0.5)
            put("toggleSwitch", true)
            put("text", "A")
            put("iconId", iconId)
        }))
    }

    @Test
    fun previewRejectsInputWithoutATouchpadAndDoesNotWriteAProfile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = profile()
        val original = source.toString()
        val view = createControlLayoutPreviewView(context, source)
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        try {
            view.layout(0, 0, 600, 300)
            view.draw(Canvas(bitmap))
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                val event = MotionEvent.obtain(0, 1, action, 300f, 150f, 0)
                try {
                    assertFalse(view.dispatchTouchEvent(event))
                    assertFalse(view.onGenericMotionEvent(event))
                } finally {
                    event.recycle()
                }
            }
            assertFalse(view.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            assertEquals(original, source.toString())
            assertFalse(view.profile.save())
            assertFalse(ControlsProfile.getProfileFile(context, -1).exists())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun previewRefitsAfterWidthAndHeightChangesAndSupportsTinyBounds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = createControlLayoutPreviewView(context, profile())
        for ((width, height) in listOf(600 to 300, 900 to 300, 900 to 180, 60 to 40)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                view.layout(0, 0, width, height)
                view.draw(Canvas(bitmap))
                val element = view.profile.elements.single()
                assertEquals(view.maxWidth / 2f, element.x.toFloat(), view.snappingSize.toFloat())
                assertEquals(view.maxHeight / 2f, element.y.toFloat(), view.snappingSize.toFloat())
                assertTrue(view.snappingSize > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun previewFitsLegacyEdgeControlsWithoutEnablingFittingOnTheSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = profile().apply {
            getJSONArray("elements").getJSONObject(0).put("x", 0.0).put("y", 0.0).put("scale", 5.0)
        }
        val original = source.toString()
        val view = createControlLayoutPreviewView(context, source)
        val bitmap = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)
        try {
            view.layout(0, 0, bitmap.width, bitmap.height)
            view.draw(Canvas(bitmap))
            val bounds = view.profile.elements.single().boundingBox
            assertTrue(bounds.left >= 0 && bounds.top >= 0)
            assertTrue(bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
            assertEquals(original, source.toString())
            assertFalse(source.has(ControlsProfile.KEY_AUTO_FIT_LAYOUT))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun unavailableImportedIconDoesNotCrashRendering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        try {
            for (icon in listOf(39, 40, 127, 255)) {
                val view = createControlLayoutPreviewView(context, profile(icon))
                view.layout(0, 0, 600, 300)
                view.draw(Canvas(bitmap))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
