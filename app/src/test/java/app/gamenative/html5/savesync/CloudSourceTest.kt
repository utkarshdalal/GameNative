package app.gamenative.html5.savesync

import com.winlator.container.Container
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// robolectric for Container's static initializer (Environment.getExternalStoragePublicDirectory).
@RunWith(RobolectricTestRunner::class)
class CloudSourceTest {

    // store managers come from Hilt, not a running FGS (android can stop a dataSync FGS right before
    // the exit-time lookup). robolectric has no Hilt graph: an unavailable graph must fail soft, not
    // throw out of teardown.
    @Test
    fun gogAndEpic_failSoft_withoutAHiltGraph() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(false, CloudSource.GogRemoteConfig(ctx, "GOG_1674557514").isSupported)
        assertEquals(false, CloudSource.EpicSavedGames(ctx, "EPIC_1").isSupported)
        assertTrue(runBlocking { CloudSource.GogRemoteConfig(ctx, "GOG_1674557514").wineSaveRoots() }.isEmpty())
        assertTrue(runBlocking { CloudSource.EpicSavedGames(ctx, "EPIC_1").wineSaveRoots() }.isEmpty())
    }

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
        val roots = runBlocking { source.wineSaveRoots() }
        assertTrue("wineSaveRoots must be empty for GreenworksCloud", roots.isEmpty())
    }
}
