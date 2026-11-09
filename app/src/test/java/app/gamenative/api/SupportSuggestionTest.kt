package app.gamenative.api

import android.app.Application
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SupportSuggestionTest {

    private fun displayServerCard(to: String): SupportSuggestion? = SupportSuggestion.parse(
        JSONObject().put("v", 1).put(
            "changes",
            JSONArray().put(JSONObject().put("key", "displayServer").put("to", to)),
        ),
    )

    @Test
    fun `display server card accepts both display paths`() {
        assertTrue(displayServerCard(Container.DISPLAY_SERVER_X11)!!.applicable)
        assertTrue(displayServerCard(Container.DISPLAY_SERVER_GAMESCOPE)!!.applicable)
    }

    @Test
    fun `display server card rejects an unknown value`() {
        assertFalse(displayServerCard("wayland")!!.applicable)
    }

    @Test
    fun `display server change reaches the container data and reads back`() {
        val before = ContainerData(displayServer = Container.DISPLAY_SERVER_GAMESCOPE)
        val after = ContainerUtils.applyBestConfigMapToContainerData(
            before,
            mapOf("displayServer" to Container.DISPLAY_SERVER_X11),
        )
        assertEquals(Container.DISPLAY_SERVER_X11, after.displayServer)
        assertEquals(Container.DISPLAY_SERVER_X11, SuggestionConfigKeys.configValueOf(after, "displayServer"))
    }
}
