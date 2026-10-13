package com.winlator.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InputControlsViewResizeTest {
    @Test
    fun editModeResizePersistsUnsavedPositionUsingPreviousBounds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = InputControlsView(context)
        view.layout(0, 0, 1000, 500)
        val profile = ControlsProfile(context, InputControlsManager(context).nextProfileId())
        val file = ControlsProfile.getProfileFile(context, profile.id)
        try {
            profile.name = "Resize test"
            val element = ControlElement(view).apply {
                setX(250)
                setY(125)
            }
            profile.addElement(element)
            assertTrue(profile.save())
            view.profile = profile
            view.isEditMode = true

            element.setX(400)
            element.setY(200)
            view.layout(0, 0, 2000, 1000)

            val saved = JSONObject(FileUtils.readString(file)).getJSONArray("elements").getJSONObject(0)
            assertEquals(0.4, saved.getDouble("x"), 0.001)
            assertEquals(0.4, saved.getDouble("y"), 0.001)
            assertEquals(800, profile.elements.single().x.toInt())
            assertEquals(400, profile.elements.single().y.toInt())
        } finally {
            file.delete()
        }
    }
}
