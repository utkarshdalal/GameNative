package app.gamenative.utils

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.container.ContainerData
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

    private fun intentConfig(wincomponents: String): ContainerData {
        val config = JSONObject()
            .put("screenSize", "1280x720")
            .put("cpuList", "0,1,2,3")
            .put("cpuListWoW64", "0,1,2,3")
            .put("suspendPolicy", "manual")
            .put("wincomponents", wincomponents)
        val intent = Intent("app.gamenative.LAUNCH_GAME")
            .putExtra("app_id", 400)
            .putExtra("container_config", config.toString())
        return IntentLaunchManager.parseLaunchIntent(intent, ApplicationProvider.getApplicationContext())!!.containerConfig!!
    }

    private fun effectiveWithoutContainer(override: ContainerData): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            IntentLaunchManager.applyTemporaryConfigOverride(context, "STEAM_400", override)
            return IntentLaunchManager.getEffectiveContainerConfig(context, "STEAM_400")!!.wincomponents
        } finally {
            IntentLaunchManager.clearTemporaryOverride("STEAM_400")
        }
    }

    @Test
    fun `an intent launch config drops redist components it turns off`() {
        val override = intentConfig("direct3d=1,directsound=1,directinput8=1,vcrun2010=1,xaudio=0,vcrun2022=0")

        assertEquals("direct3d=1,directsound=1,directinput8=1,vcrun2010=1", effectiveWithoutContainer(override))
        assertEquals(
            "direct3d=1,directsound=1,directinput8=1,vcrun2010=1",
            IntentLaunchManager.mergeConfigurations(ContainerData(), override).wincomponents,
        )
    }

    @Test
    fun `an intent string of the defaults plus a redist entry turned off is still given`() {
        val override = intentConfig(Container.DEFAULT_WINCOMPONENTS + ",xaudio=0")
        val base = ContainerData(wincomponents = Container.DEFAULT_WINCOMPONENTS.replace("directshow=0", "directshow=1"))

        assertEquals(Container.DEFAULT_WINCOMPONENTS, IntentLaunchManager.mergeConfigurations(base, override).wincomponents)
    }

    @Test
    fun `an intent string of only redist entries turned off keeps the container's own value`() {
        val override = intentConfig("xaudio=0,vcrun2022=0")
        val base = ContainerData(wincomponents = Container.DEFAULT_WINCOMPONENTS + ",vcrun2022=1")

        assertEquals(base.wincomponents, IntentLaunchManager.mergeConfigurations(base, override).wincomponents)
        assertEquals(Container.DEFAULT_WINCOMPONENTS, effectiveWithoutContainer(override))
    }
}
