package app.gamenative.utils.launchdependencies

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BionicSteamAssetsDependencyTest {

    private fun container(wineVersion: String) =
        Container("test-id").apply { setWineVersion(wineVersion) }

    @Test
    fun `bundled lsteamclient is detected for 11_0_2 and 11_0_3 only`() {
        assertTrue(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-2-arm64ec-1"))
        assertTrue(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-2-x86_64-13"))
        assertTrue(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-3-arm64ec-1"))
        assertTrue(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-3-x86_64-1"))

        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-1-arm64ec-1"))
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-1-x86_64-1"))
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-10.0-4-arm64ec-1"))
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-9.0-x86_64"))
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("wine"))
    }

    @Test
    fun `bundled lsteamclient covers every previously hardcoded 11_0_2 build`() {
        val previouslyHardcoded = listOf(
            "proton-11.0-2-x86_64-1", "proton-11.0-2-arm64ec-1",
            "proton-11.0-2.3-arm64ec-1", "proton-11.0-2.4-arm64ec-1",
            "proton-11.0-2-x86_64-6", "proton-11.0-2-arm64ec-6",
            "proton-11.0-2-x86_64-7", "proton-11.0-2-arm64ec-7",
            "proton-11.0-2-x86_64-8", "proton-11.0-2-arm64ec-8",
            "proton-11.0-2-x86_64-9", "proton-11.0-2-arm64ec-9",
            "proton-11.0-2-x86_64-10", "proton-11.0-2-arm64ec-10",
            "proton-11.0-2-x86_64-11", "proton-11.0-2-arm64ec-11",
            "proton-11.0-2-x86_64-12", "proton-11.0-2-arm64ec-12",
            "proton-11.0-2-x86_64-13", "proton-11.0-2-arm64ec-13",
        )
        for (wineVersion in previouslyHardcoded) {
            assertTrue(wineVersion, BionicSteamAssetsDependency.bundlesLsteamclient(wineVersion))
            assertNull(wineVersion, BionicSteamAssetsDependency.lsteamclientArchiveFor(container(wineVersion)))
        }
    }

    @Test
    fun `prefix match does not bleed into a higher minor or patch digit`() {
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-20-arm64ec-1"))
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-30-x86_64-1"))
        assertTrue(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-2.9-arm64ec-1"))
        assertTrue(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-3.4-x86_64-2"))
        assertEquals(
            "lsteamclient-arm64ec-proton11.tzst",
            BionicSteamAssetsDependency.lsteamclientArchiveFor(container("proton-11.0-20-arm64ec-1")),
        )
    }

    @Test
    fun `archive is overlaid for every proton that does not bundle lsteamclient`() {
        assertEquals(
            "lsteamclient-arm64ec-proton11.tzst",
            BionicSteamAssetsDependency.lsteamclientArchiveFor(container("proton-11.0-1-arm64ec-1")),
        )
        assertEquals(
            "lsteamclient-x86_64-proton11.tzst",
            BionicSteamAssetsDependency.lsteamclientArchiveFor(container("proton-11.0-1-x86_64-1")),
        )
        assertEquals(
            "lsteamclient-arm64ec-proton10.tzst",
            BionicSteamAssetsDependency.lsteamclientArchiveFor(container("proton-10.0-4-arm64ec-1")),
        )
        assertEquals(
            "lsteamclient-x86_64-proton10.tzst",
            BionicSteamAssetsDependency.lsteamclientArchiveFor(container("proton-10.0-4-x86_64-1")),
        )
        assertEquals(
            "lsteamclient-x86_64-proton9.tzst",
            BionicSteamAssetsDependency.lsteamclientArchiveFor(container("proton-9.0-x86_64")),
        )
        assertNull(BionicSteamAssetsDependency.lsteamclientArchiveFor(container("wine")))
    }

    @Test
    fun `proton11 steam exe is used for 11_0_1 11_0_2 and 11_0_3`() {
        val proton11 = listOf(
            "proton-11.0-1-x86_64-1", "proton-11.0-1-arm64ec-1",
            "proton-11.0-2-x86_64-1", "proton-11.0-2-arm64ec-13",
            "proton-11.0-2.4-arm64ec-1", "proton-11.0-3-x86_64-1",
            "proton-11.0-3-arm64ec-1",
        )
        for (wineVersion in proton11) {
            assertEquals(wineVersion, "steam-proton11.exe", BionicSteamAssetsDependency.steamExeAssetFor(container(wineVersion)))
        }
    }

    @Test
    fun `default steam exe is used outside the proton11 prefix set`() {
        val others = listOf(
            "proton-9.0-x86_64", "proton-9.0-arm64ec",
            "proton-10.0-4-x86_64-1", "proton-10.0-4-arm64ec-1",
            "proton-11.0-arm64ec", "proton-11.0-20-arm64ec-1", "wine",
        )
        for (wineVersion in others) {
            assertEquals(wineVersion, "steam.exe", BionicSteamAssetsDependency.steamExeAssetFor(container(wineVersion)))
        }
    }

    @Test
    fun `proton11 steam exe acceptance does not depend on the lsteamclient set`() {
        assertEquals("steam-proton11.exe", BionicSteamAssetsDependency.steamExeAssetFor(container("proton-11.0-1-arm64ec-1")))
        assertFalse(BionicSteamAssetsDependency.bundlesLsteamclient("proton-11.0-1-arm64ec-1"))
    }

    @Test
    fun `bionic steam exe names stay unchanged`() {
        assertEquals(
            listOf("steam.exe", "steam-proton11.exe"),
            BionicSteamAssetsDependency.bionicSteamExeNames(),
        )
    }
}
