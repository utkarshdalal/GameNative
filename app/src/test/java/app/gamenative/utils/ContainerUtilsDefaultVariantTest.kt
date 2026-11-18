package app.gamenative.utils

import app.gamenative.PrefManager
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// the runtime choice is per game: an html5 global default would make every new container webview, with no
// wine prefix for XServer to boot.
@RunWith(RobolectricTestRunner::class)
class ContainerUtilsDefaultVariantTest {

    @Test
    fun setDefaultContainerData_rejectsHtml5Variant() {
        installFakePrefManager(FakeDataStore()).use {
            ContainerUtils.setDefaultContainerData(
                ContainerData(containerVariant = Container.GLIBC, screenSize = "1280x720"),
            )
            awaitUntil { PrefManager.screenSize == "1280x720" }

            ContainerUtils.setDefaultContainerData(
                ContainerData(containerVariant = Container.CONTAINER_VARIANT_HTML5, screenSize = "640x480"),
            )
            // writes land in order, so once this marker is visible the rejected save had its chance too.
            ContainerUtils.setDefaultContainerData(
                ContainerData(containerVariant = Container.GLIBC, screenSize = "1280x720", envVars = "MARK=1"),
            )
            awaitUntil { PrefManager.envVars == "MARK=1" }

            assertEquals(Container.GLIBC, PrefManager.containerVariant)
            assertEquals("1280x720", PrefManager.screenSize)
        }
    }
}
