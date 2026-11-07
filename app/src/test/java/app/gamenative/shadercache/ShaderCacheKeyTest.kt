package app.gamenative.shadercache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShaderCacheKeyTest {
    @get:Rule val temp = TemporaryFolder()

    private val localIds: (String, String) -> String? = { _, id -> id }

    @Test fun storeIds() {
        assertEquals("steam:221380", ShaderCacheKey.storeGameKey("STEAM_221380", localIds))
        assertEquals("gog:1207658924", ShaderCacheKey.storeGameKey("GOG_1207658924", localIds))
        assertEquals("epic:Quail", ShaderCacheKey.storeGameKey("EPIC_Quail", localIds))
        assertEquals("amazon:08c0fa3b-3448-19b7", ShaderCacheKey.storeGameKey("AMAZON_08c0fa3b-3448-19b7", localIds))
        assertNull(ShaderCacheKey.storeGameKey("STEAM_", localIds))
        assertNull(ShaderCacheKey.storeGameKey("STEAM_12 3", localIds))
        assertNull(ShaderCacheKey.storeGameKey("STEAM_" + "1".repeat(65), localIds))
        assertNull(ShaderCacheKey.storeGameKey("UNKNOWN_1", localIds))
        assertNull(ShaderCacheKey.storeGameKey("EPIC_7") { _, _ -> null })
    }

    @Test fun dxvkVersions() {
        assertEquals("dxvk-2.7.1", ShaderCacheKey.dxvkKey("2.7.1-1-gplasync-0"))
        assertEquals("dxvk-2.7.1", ShaderCacheKey.dxvkKey("dxvk-2.7.1"))
        assertEquals("dxvk-2.4", ShaderCacheKey.dxvkKey("v2.4-async"))
        assertEquals("dxvk-none", ShaderCacheKey.dxvkKey(null))
        assertEquals("dxvk-custom", ShaderCacheKey.dxvkKey(" Custom "))
    }

    @Test fun vkd3dVersions() {
        assertEquals("vkd3d-3.0b", ShaderCacheKey.vkd3dKey("3.0b"))
        assertEquals("vkd3d-2.14.1", ShaderCacheKey.vkd3dKey("vkd3d-proton-2.14.1"))
        assertEquals("vkd3d-2.14.1", ShaderCacheKey.vkd3dKey("2.14.1-1-build"))
        assertNotEquals(ShaderCacheKey.vkd3dKey("3.0"), ShaderCacheKey.vkd3dKey("3.0b"))
        assertEquals("vkd3d-none", ShaderCacheKey.vkd3dKey(""))
    }

    @Test fun customExeHashCoversOnlyTheFirstMebibyteAndTheSize() {
        val mib = 1 shl 20
        fun exe(name: String, bytes: ByteArray) = File(temp.root, name).apply { writeBytes(bytes) }
        val base = ByteArray(mib + 16) { (it % 251).toByte() }
        val tailChanged = base.copyOf().also { it[mib] = 1 }
        val headChanged = base.copyOf().also { it[mib - 1] = 1 }
        val key = ShaderCacheKey.customExeKey(exe("a.exe", base))
        assertEquals(key, ShaderCacheKey.customExeKey(exe("b.exe", tailChanged)))
        assertNotEquals(key, ShaderCacheKey.customExeKey(exe("c.exe", headChanged)))
        assertNotEquals(key, ShaderCacheKey.customExeKey(exe("d.exe", base.copyOf(mib + 17))))
        assertEquals("custom:".length + 64, key.length)
    }
}
