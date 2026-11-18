package com.winlator.inputcontrols

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class ControlsProfileCursorSpeedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profile = ControlsProfile(context, 1)

    @Test
    fun `cursor speed accepts positive finite values`() {
        profile.cursorSpeed = 2.5f

        assertEquals(2.5f, profile.cursorSpeed, 0f)
    }

    @Test
    fun `cursor speed rejects values that would stop or corrupt movement`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f).forEach { value ->
            profile.cursorSpeed = value

            assertEquals(ControlsProfile.DEFAULT_CURSOR_SPEED, profile.cursorSpeed, 0f)
        }
    }

    @Test
    fun `legacy profile without cursor speed loads the default`() {
        val json = """{"id":7,"name":"Legacy"}"""
        val loadedProfile = InputControlsManager.loadProfile(
            context,
            ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)),
        )

        assertNotNull(loadedProfile)
        assertEquals(ControlsProfile.DEFAULT_CURSOR_SPEED, loadedProfile!!.cursorSpeed, 0f)
    }
}
