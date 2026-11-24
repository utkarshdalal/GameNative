package com.winlator.container

import app.gamenative.ui.component.dialog.winComponentsItemTitleRes
import app.gamenative.utils.PhysXLayout
import app.gamenative.utils.RedistInstallerExtractor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerWinComponentsTest {

    private fun normalized(wincomponents: String): String {
        val data = JSONObject().put("wincomponents", wincomponents)
        Container.checkObsoleteOrMissingProperties(data)
        return data.getString("wincomponents")
    }

    @Test
    fun `detected redist components survive a load`() {
        val stored = "direct3d=1,directsound=1,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0," +
            "vcrun2010=1,wmdecoder=1,opengl=0,vcrun2022=1,physx=1,xaudio=0"

        val expected = "direct3d=1,directsound=1,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0," +
            "vcrun2010=1,wmdecoder=1,opengl=0,xaudio=0,vcrun2022=1,physx=1"
        assertEquals(expected, normalized(stored))
        assertEquals(expected, normalized(normalized(stored)))
    }

    @Test
    fun `obsolete components are still dropped`() {
        val stored = "direct3d=1,directx=1,vcrun2022=1,dotnet48=1"

        assertEquals(
            "direct3d=1,directsound=1,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0," +
                "vcrun2010=1,wmdecoder=1,opengl=0,xaudio=1,vcrun2022=1",
            normalized(stored),
        )
    }

    @Test
    fun `default components keep their stored values and order`() {
        val stored = "opengl=1,vcrun2013=0,direct3d=0,vcrun2010=0,vcrun2005=1,directsound=0"

        assertEquals(
            "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0," +
                "vcrun2010=0,wmdecoder=1,opengl=1,xaudio=1,vcrun2013=0,vcrun2005=1",
            normalized(stored),
        )
    }

    @Test
    fun `a container without redist components is unchanged`() {
        assertEquals(Container.DEFAULT_WINCOMPONENTS, normalized(Container.DEFAULT_WINCOMPONENTS))
    }

    @Test
    fun `an existing container gains xaudio without losing explicit off`() {
        val oldDefaults = Container.DEFAULT_WINCOMPONENTS.removeSuffix(",xaudio=1")
        assertEquals(Container.DEFAULT_WINCOMPONENTS, normalized(oldDefaults))
        assertEquals(oldDefaults + ",xaudio=0", normalized(oldDefaults + ",xaudio=0"))
    }

    @Test
    fun `a repeated redist component keeps its first value`() {
        assertEquals(
            Container.DEFAULT_WINCOMPONENTS + ",vcrun2022=0",
            normalized(Container.DEFAULT_WINCOMPONENTS + ",vcrun2022=0,vcrun2022=1"),
        )
    }

    @Test
    fun `redist components include xaudio even though it is now a default`() {
        val detectable = RedistInstallerExtractor.FAMILY_MAJOR_VERSIONS.keys + PhysXLayout.COMPONENT + "xaudio"

        assertEquals(detectable, Container.REDIST_WINCOMPONENTS)
    }

    @Test
    fun `every kept component has a settings title`() {
        Container.REDIST_WINCOMPONENTS.forEach { winComponentsItemTitleRes(it) }
    }
}
