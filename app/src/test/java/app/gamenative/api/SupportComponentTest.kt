package app.gamenative.api

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SupportComponentTest {

    private val sha = "a".repeat(64)

    private fun card(
        type: String = "turnip",
        format: String = "adrenotools-zip",
        version: String = "turnip-pr12-a1b2c3d4",
        key: String = "graphicsDriverConfig.version",
        value: String = version,
        size: Long = 2574958L,
        url: String = "https://example.com/a.zip",
        hash: String = sha,
    ): JSONObject = JSONObject(
        """
        {"v":1,"componentId":"4f9c1e2a-0b6d-4c1e-9a7f-2d3b4c5d6e7f","summary":"  Fixes GMEM tiling  ",
         "component":{"type":"$type","packageFormat":"$format","versionName":"$version",
           "artifactKey":"artifacts/x/0-$version.zip","artifactUrl":"$url","sha256":"$hash","size":$size,
           "source":{"repo":"GameNative/mesa-turnip","ref":"pr12","run":"https://github.com/GameNative/mesa-turnip/actions/runs/1"}},
         "apply":{"key":"$key","value":"$value"},
         "rerun":true,"run":{"minSeconds":120,"instruction":"Play the first level"}}
        """.trimIndent(),
    )

    @Test
    fun parsesTurnipCard() {
        val component = SupportComponent.parse(card())
        assertNotNull(component)
        component!!
        assertEquals(SupportComponent.Type.TURNIP, component.type)
        assertEquals(SupportComponent.PackageFormat.ADRENOTOOLS_ZIP, component.packageFormat)
        assertEquals("turnip-pr12-a1b2c3d4", component.versionName)
        assertEquals("graphicsDriverConfig.version", component.applyKey)
        assertEquals("Fixes GMEM tiling", component.summary)
        assertEquals("GameNative/mesa-turnip", component.sourceRepo)
        assertEquals("pr12", component.sourceRef)
        assertEquals(2574958L, component.size)
        assertTrue(component.rerun)
        assertEquals(120, component.run?.minSeconds)
    }

    @Test
    fun parsesContentPackageTypes() {
        val cases = listOf(
            "fexcore" to "fexcoreVersion",
            "box64" to "box64Version",
            "dxvk" to "dxwrapperConfig.version",
            "vkd3d" to "dxwrapperConfig.vkd3dVersion",
            "proton" to "wineVersion",
        )
        for ((type, key) in cases) {
            val component = SupportComponent.parse(card(type = type, format = "content-package", version = "$type-test-1", key = key))
            assertNotNull(type, component)
        }
        assertNotNull(SupportComponent.parse(card(type = "wrapper")))
    }

    @Test
    fun rejectsKeyThatDoesNotMatchType() {
        assertNull(SupportComponent.parse(card(key = "fexcoreVersion")))
        assertNull(SupportComponent.parse(card(type = "dxvk", format = "content-package", key = "wineVersion")))
        assertNull(SupportComponent.parse(card(key = "envVars")))
    }

    @Test
    fun rejectsFormatThatDoesNotMatchType() {
        assertNull(SupportComponent.parse(card(format = "content-package")))
        assertNull(SupportComponent.parse(card(type = "box64", format = "adrenotools-zip", key = "box64Version")))
        assertNull(SupportComponent.parse(card(type = "mesa")))
    }

    @Test
    fun rejectsValueThatDiffersFromVersionName() {
        assertNull(SupportComponent.parse(card(value = "turnip-pr12-other")))
    }

    @Test
    fun rejectsBadArtifactFields() {
        assertNull(SupportComponent.parse(card(hash = "abc")))
        assertNull(SupportComponent.parse(card(size = 0L)))
        assertNull(SupportComponent.parse(card(size = SupportComponent.MAX_SIZE + 1)))
        assertNull(SupportComponent.parse(card(url = "http://example.com/a.zip")))
        assertNotNull(SupportComponent.parse(card(size = SupportComponent.MAX_SIZE)))
    }

    @Test
    fun rejectsUnsafeVersionNames() {
        assertNull(SupportComponent.parse(card(version = "a,b=c")))
        assertNull(SupportComponent.parse(card(version = "../x")))
        assertFalse(SupportComponent.isValidVersion(""))
        assertTrue(SupportComponent.isValidVersion("Proton-10.0-arm64ec_1+dbg"))
    }

    @Test
    fun rejectsWrongEnvelope() {
        assertNull(SupportComponent.parse(null))
        assertNull(SupportComponent.parse(card().put("v", 2)))
        assertNull(SupportComponent.parse(card().put("componentId", "bad id!")))
        assertNull(SupportComponent.parse(card().apply { remove("apply") }))
    }

    @Test
    fun lowercasesSha() {
        val component = SupportComponent.parse(card(hash = "A".repeat(64)))
        assertEquals("a".repeat(64), component?.sha256)
    }
}
