package app.gamenative.shadercache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun installedStoreGamesMatchTheContainerKeys() {
        assertEquals(ShaderCacheKey.storeGameKey("STEAM_221380", localIds), ShaderCacheKey.storeGame("steam", "221380"))
        assertEquals(
            ShaderCacheKey.storeGameKey("AMAZON_12") { _, _ -> ShaderCacheKey.amazonGameId("amzn1.adg.product.08c0fa3b-3448-19b7") },
            ShaderCacheKey.storeGame("amazon", ShaderCacheKey.amazonGameId("amzn1.adg.product.08c0fa3b-3448-19b7")),
        )
        assertEquals("amazon:08c0fa3b-3448-19b7", ShaderCacheKey.storeGame("amazon", ShaderCacheKey.amazonGameId("amzn1.adg.product.08c0fa3b-3448-19b7")))
        assertNull(ShaderCacheKey.storeGame("epic", "bad name"))
    }

    @Test fun poolFileNamesAreTheGameAndTheLayerVersion() {
        assertEquals("steam_221380-dxvk-2.6.1", ShaderPool("steam:221380", "dxvk-2.6.1").fileName)
        assertEquals("custom_ab12-vkd3d-3.0.0", ShaderPool("custom:ab12", "vkd3d-3.0.0").fileName)
    }

    @Test fun poolFilesOfAGameIncludeEarlierFormatsButNotOtherGames() {
        assertTrue(ShaderPool.isFileOf("epic:a", "epic_a-dxvk-2.6.1.foz"))
        assertTrue(ShaderPool.isFileOf("epic:a", "epic_a-vkd3d-3.0.0.json"))
        assertTrue(ShaderPool.isFileOf("epic:a", "epic_a.foz"))
        assertTrue(ShaderPool.isFileOf("epic:a", "epic_a-dxvk-2.6.1-vkd3d-3.0.foz.part"))
        assertFalse(ShaderPool.isFileOf("epic:a", "epic_a-b-dxvk-2.6.1.foz"))
        assertFalse(ShaderPool.isFileOf("epic:a", "epic_ab-dxvk-2.6.1.foz"))
        assertFalse(ShaderPool.isFileOf("steam:1", "steam_12.foz"))
        assertFalse(ShaderPool.isFileOf("epic:a", "epic_a-dxvk-b-dxvk-2.6.1.foz"))
        assertTrue(ShaderPool.isFileOf("epic:a-dxvk-b", "epic_a-dxvk-b-dxvk-2.6.1.foz"))
    }

    @Test fun dxvkVersions() {
        assertEquals("dxvk-2.4.1", ShaderCacheKey.dxvkKey("2.4.1-gplasync"))
        assertEquals("dxvk-1.10.3", ShaderCacheKey.dxvkKey("async-1.10.3"))
        assertEquals("dxvk-2.7.1", ShaderCacheKey.dxvkKey("2.7.1-1-gplasync-0"))
        assertEquals("dxvk-1.11.1", ShaderCacheKey.dxvkKey("1.11.1-sarek"))
        assertEquals("dxvk-2.6.1", ShaderCacheKey.dxvkKey("2.6.1-gplasync"))
        assertEquals("dxvk-2.7.1", ShaderCacheKey.dxvkKey("2.7.1"))
        assertEquals("dxvk-3.1.1", ShaderCacheKey.dxvkKey("3.1.1"))
        assertNull(ShaderCacheKey.dxvkKey(null))
        assertNull(ShaderCacheKey.dxvkKey("custom"))
    }

    @Test fun vkd3dVersions() {
        assertEquals("vkd3d-2.14.1", ShaderCacheKey.vkd3dKey("2.14.1"))
        assertEquals("vkd3d-3.0.0", ShaderCacheKey.vkd3dKey("3.0b"))
        assertEquals("vkd3d-3.0.1", ShaderCacheKey.vkd3dKey("3.0.1"))
        assertEquals("vkd3d-2.12.0", ShaderCacheKey.vkd3dKey("2.12"))
        assertEquals("vkd3d-3.1.0", ShaderCacheKey.vkd3dKey("3.1"))
        assertEquals("vkd3d-2.6.0", ShaderCacheKey.vkd3dKey("2.6"))
        assertEquals("vkd3d-2.13.0", ShaderCacheKey.vkd3dKey("2.13"))
        assertEquals("vkd3d-2.14.1", ShaderCacheKey.vkd3dKey("vkd3d-proton-2.14.1"))
        assertNull(ShaderCacheKey.vkd3dKey(""))
    }

    @Test fun layersFollowTheWrapper() {
        val config = "version=async-1.10.3,vkd3dVersion=3.0b"
        assertEquals(ShaderCacheKey("g", "dxvk-1.10.3", null), ShaderCacheKey.of("g", "dxvk", config, "wrapper") { true })
        assertEquals(ShaderCacheKey("g", null, null), ShaderCacheKey.of("g", "wined3d", config, "wrapper") { true })
        assertEquals(ShaderCacheKey("g", null, null), ShaderCacheKey.of("g", "cnc-ddraw", config, "wrapper") { true })
        assertTrue(ShaderCacheKey.of("g", "wined3d", config, "wrapper") { true }.pools.isEmpty())
    }

    @Test fun vkd3dContainersAlsoLoadTheDxvkTheLaunchPicks() {
        val old = "version=async-1.10.3,vkd3dVersion=3.0b"
        assertEquals(ShaderCacheKey("g", "dxvk-2.6.1", "vkd3d-3.0.0"), ShaderCacheKey.of("g", "vkd3d", old, "wrapper") { false })
        assertEquals(ShaderCacheKey("g", "dxvk-2.6.1", "vkd3d-2.14.1"), ShaderCacheKey.of("g", "vkd3d", "vkd3dVersion=2.14.1", "wrapper") { false })
        assertEquals(
            ShaderCacheKey("g", "dxvk-2.7.1", "vkd3d-3.0.0"),
            ShaderCacheKey.of("g", "vkd3d", "version=2.7.1-1-gplasync-0,vkd3dVersion=3.0b", "wrapper") { false },
        )
        assertEquals(
            ShaderCacheKey("g", "dxvk-1.10.3", "vkd3d-3.0.0"),
            ShaderCacheKey.of("g", "vkd3d", "version=2.7.1,vkd3dVersion=3.0b", "vortek") { true },
        )
        assertEquals(
            listOf(ShaderPool("g", "dxvk-1.10.3"), ShaderPool("g", "vkd3d-3.0.0")),
            ShaderCacheKey.of("g", "vkd3d", "version=2.7.1,vkd3dVersion=3.0b", "vortek") { true }.pools,
        )
    }

    @Test fun observedVersionsReplaceConfiguredOnesOnlyForTheSameSettings() {
        val key = ShaderCacheKey("g", "dxvk-2.4.1", "vkd3d-3.0.0")
        val observed = """{"wrapper":"vkd3d|2.4.1-gplasync|3.0b|wrapper","dxvk":"dxvk-2.4.0","vkd3d":""}"""
        assertEquals(ShaderCacheKey("g", "dxvk-2.4.0", "vkd3d-3.0.0"), key.withObserved(observed, "vkd3d|2.4.1-gplasync|3.0b|wrapper"))
        assertEquals(key, key.withObserved(observed, "vkd3d|2.7.1|3.0b|wrapper"))
        assertEquals(key, key.withObserved("", "vkd3d|2.4.1-gplasync|3.0b|wrapper"))
        assertEquals(ShaderCacheKey("g", null, null), ShaderCacheKey("g", null, null).withObserved(observed, "vkd3d|2.4.1-gplasync|3.0b|wrapper"))
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
