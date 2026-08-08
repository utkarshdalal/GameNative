package app.gamenative.html5.savesync

import com.winlator.container.Container
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// unit-tests for CloudSource.GreenworksCloud.
// robolectric for Container's static initializer (Environment.getExternalStoragePublicDirectory).
// no Steam mocks needed — these tests cover only the data-class semantics.
@RunWith(RobolectricTestRunner::class)
class CloudSourceTest {

    @Test
    fun greenworksCloud_isSupported_whenObservedTrue() {
        val source = CloudSource.GreenworksCloud(
            appId = "STEAM_1454400",
            container = Container("STEAM_1454400"),
            observed = true,
        )
        assertEquals(true, source.isSupported)
    }

    @Test
    fun greenworksCloud_isSupported_whenObservedFalse() {
        val source = CloudSource.GreenworksCloud(
            appId = "STEAM_1454400",
            container = Container("STEAM_1454400"),
            observed = false,
        )
        assertEquals(false, source.isSupported)
    }

    @Test
    fun greenworksCloud_wineSaveRoots_isEmpty() {
        val source = CloudSource.GreenworksCloud(
            appId = "STEAM_1454400",
            container = Container("STEAM_1454400"),
            observed = true,
        )
        // wineSaveRoots is suspend — runBlocking matches GogRemoteConfig.isSupported runBlocking shape.
        val roots = runBlocking { source.wineSaveRoots() }
        assertTrue("wineSaveRoots must be empty for GreenworksCloud", roots.isEmpty())
    }
}
