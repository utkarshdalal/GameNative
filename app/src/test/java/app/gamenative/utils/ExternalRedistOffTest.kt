package app.gamenative.utils

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalRedistOffTest {

    @Test
    fun `drops only redist components turned off`() {
        assertEquals(
            "direct3d=1,directmusic=0,vcrun2010=0,vcrun2022=1,physx=1",
            RedistComponents.withoutExternalRedistOff(
                "direct3d=1,directmusic=0,vcrun2010=0,vcrun2005=0,vcrun2008=0,vcrun2012=0,vcrun2013=0," +
                    "vcrun2022=1,xaudio=0,physx=1",
            ),
        )
    }

    @Test
    fun `a string without redist components is unchanged`() {
        assertEquals(Container.DEFAULT_WINCOMPONENTS, RedistComponents.withoutExternalRedistOff(Container.DEFAULT_WINCOMPONENTS))
        assertEquals(Container.FALLBACK_WINCOMPONENTS, RedistComponents.withoutExternalRedistOff(Container.FALLBACK_WINCOMPONENTS))
        assertEquals("", RedistComponents.withoutExternalRedistOff(""))
    }

    @Test
    fun `an intent launch config drops redist components it turns off`() {
        val config = JSONObject()
            .put("screenSize", "1280x720")
            .put("cpuList", "0,1,2,3")
            .put("cpuListWoW64", "0,1,2,3")
            .put("suspendPolicy", "manual")
            .put("wincomponents", "direct3d=1,directsound=1,directinput8=1,vcrun2010=1,xaudio=0,vcrun2022=0")
        val intent = Intent("app.gamenative.LAUNCH_GAME")
            .putExtra("app_id", 400)
            .putExtra("container_config", config.toString())

        val request = IntentLaunchManager.parseLaunchIntent(intent, ApplicationProvider.getApplicationContext())

        assertEquals("direct3d=1,directsound=1,directinput8=1,vcrun2010=1", request!!.containerConfig!!.wincomponents)
    }
}
