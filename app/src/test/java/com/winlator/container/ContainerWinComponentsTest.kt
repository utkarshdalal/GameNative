package com.winlator.container

import app.gamenative.ui.component.dialog.winComponentsItemTitleRes
import app.gamenative.utils.PhysXLayout
import app.gamenative.utils.RedistInstallerExtractor
import com.winlator.core.KeyValueSet
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

        assertEquals(stored, normalized(stored))
        assertEquals(stored, normalized(normalized(stored)))
    }

    @Test
    fun `obsolete components are still dropped`() {
        val stored = "direct3d=1,directx=1,vcrun2022=1,dotnet48=1"

        assertEquals(
            "direct3d=1,directsound=1,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0," +
                "vcrun2010=1,wmdecoder=1,opengl=0,vcrun2022=1",
            normalized(stored),
        )
    }

    @Test
    fun `default components keep their stored values and order`() {
        val stored = "opengl=1,vcrun2013=0,direct3d=0,vcrun2010=0,vcrun2005=1,directsound=0"

        assertEquals(
            "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0," +
                "vcrun2010=0,wmdecoder=1,opengl=1,vcrun2013=0,vcrun2005=1",
            normalized(stored),
        )
    }

    @Test
    fun `a container without redist components is unchanged`() {
        assertEquals(Container.DEFAULT_WINCOMPONENTS, normalized(Container.DEFAULT_WINCOMPONENTS))
    }

    @Test
    fun `a repeated redist component keeps its first value`() {
        assertEquals(
            Container.DEFAULT_WINCOMPONENTS + ",vcrun2022=0",
            normalized(Container.DEFAULT_WINCOMPONENTS + ",vcrun2022=0,vcrun2022=1"),
        )
    }

    @Test
    fun `kept components are every detectable redist outside the defaults`() {
        val defaults = KeyValueSet(Container.DEFAULT_WINCOMPONENTS).map { it[0] }.toSet()
        val detectable = RedistInstallerExtractor.FAMILY_MAJOR_VERSIONS.keys + PhysXLayout.COMPONENT + "xaudio"

        assertEquals(detectable - defaults, Container.REDIST_WINCOMPONENTS)
    }

    @Test
    fun `every kept component has a settings title`() {
        Container.REDIST_WINCOMPONENTS.forEach { winComponentsItemTitleRes(it) }
    }
}
