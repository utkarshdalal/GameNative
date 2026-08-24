package app.gamenative.ui.screen.xr.windows

import com.winlator.container.Container
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowsVrRuntimeConfigTest {

    @Test
    fun `defaults keep runtime enabled and OpenComposite disabled`() {
        val config = WindowsVrRuntimeConfig.from(Container("STEAM_TEST"))

        assertTrue(config.enabled)
        assertFalse(config.openCompositeEnabled)
    }

    @Test
    fun `accessors round trip container settings through canonical keys`() {
        val container = Container("STEAM_TEST")

        WindowsVrRuntimeConfig.setEnabled(container, false)
        WindowsVrRuntimeConfig.setOpenCompositeEnabled(container, true)

        val config = WindowsVrRuntimeConfig.from(container)
        assertFalse(config.enabled)
        assertTrue(config.openCompositeEnabled)
    }
}
