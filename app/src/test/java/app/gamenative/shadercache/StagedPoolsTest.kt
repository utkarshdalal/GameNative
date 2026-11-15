package app.gamenative.shadercache

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagedPoolsTest {
    @get:Rule val temp = TemporaryFolder()

    private val key = ShaderPool("steam:242760", "dxvk-2.6.1")

    private val staged by lazy { temp.newFolder("staged") }
    private val poolDir by lazy { File(temp.root, "container/.cache/fossilize/pool") }
    private val poolFile by lazy { File(poolDir, "${key.fileName}.foz") }
    private val poolMeta by lazy { File(poolDir, "${key.fileName}.json") }

    private fun stage(version: Long, bytes: ByteArray, size: Long = bytes.size.toLong()) {
        StagedPools.poolFile(staged, key).writeBytes(bytes)
        StagedPools.metaFile(staged, key).writeText(StagedPools.meta(version, "ab", size, 7).toString())
    }

    private fun adopt(localVersion: Long?) = StagedPools.adopt(staged, key, poolDir, poolFile, poolMeta, localVersion)

    @Test fun adoptsAStagedPoolNewerThanTheContainers() {
        poolDir.mkdirs()
        poolFile.writeBytes(byteArrayOf(1))
        val otherLayer = File(poolDir, "${ShaderPool(key.game, "vkd3d-3.0.0").fileName}.foz").apply { writeBytes(byteArrayOf(2)) }
        val bytes = ByteArray(100) { it.toByte() }
        stage(5, bytes)

        val meta = adopt(localVersion = 4)

        assertEquals(5L, meta?.optLong("version"))
        assertArrayEquals(bytes, poolFile.readBytes())
        assertEquals(5L, JSONObject(poolMeta.readText()).getLong("version"))
        assertEquals(100L, JSONObject(poolMeta.readText()).getLong("size"))
        assertTrue(otherLayer.isFile)
        assertTrue(staged.listFiles().isNullOrEmpty())
    }

    @Test fun adoptsIntoAContainerWithoutAPool() {
        stage(3, ByteArray(10))

        assertEquals(3L, adopt(localVersion = null)?.optLong("version"))
        assertTrue(poolFile.isFile)
    }

    @Test fun ignoresAStagedPoolTheContainerAlreadyHas() {
        poolDir.mkdirs()
        poolFile.writeBytes(byteArrayOf(9))
        stage(5, ByteArray(10))

        assertNull(adopt(localVersion = 5))
        assertArrayEquals(byteArrayOf(9), poolFile.readBytes())
        assertFalse(poolMeta.exists())
        assertFalse(StagedPools.poolFile(staged, key).exists())
    }

    @Test fun rejectsAStagedPoolWhoseSizeDoesNotMatch() {
        stage(5, ByteArray(10), size = 11)

        assertNull(adopt(localVersion = null))
        assertFalse(poolFile.exists())
        assertFalse(StagedPools.poolFile(staged, key).exists())
        assertFalse(StagedPools.metaFile(staged, key).exists())
    }

    @Test fun dropsStagedPoolsOfGamesNoLongerInstalled() {
        stage(1, ByteArray(4))
        val other = ShaderPool("gog:1", "dxvk-2.6.1")
        StagedPools.poolFile(staged, other).writeBytes(ByteArray(4))
        File(staged, "${other.fileName}.foz.part").writeBytes(ByteArray(4))

        StagedPools.dropAllExcept(staged, listOf(key))

        assertEquals(setOf("${key.fileName}.foz", "${key.fileName}.json"), staged.list()!!.toSet())
    }

    @Test fun dropsTheGamesStagedPoolsForOtherVersionsOnly() {
        stage(1, ByteArray(4))
        val oldVersion = ShaderPool(key.game, "dxvk-2.4.1")
        val vkd3d = ShaderPool(key.game, "vkd3d-3.0.0")
        val otherGame = ShaderPool("steam:2427600", "dxvk-2.4.1")
        for (pool in listOf(oldVersion, vkd3d, otherGame)) StagedPools.poolFile(staged, pool).writeBytes(ByteArray(4))
        File(staged, "steam_242760.foz").writeBytes(ByteArray(4))

        StagedPools.dropGame(staged, key.game, listOf(key, vkd3d))

        assertEquals(
            setOf("${key.fileName}.foz", "${key.fileName}.json", "${vkd3d.fileName}.foz", "${otherGame.fileName}.foz"),
            staged.list()!!.toSet(),
        )
    }

    @Test fun stagedVersionIgnoresAPoolWhoseSizeDoesNotMatch() {
        stage(5, ByteArray(10), size = 11)

        assertNull(StagedPools.stagedVersion(staged, key))
    }

    @Test fun deletesAPartialDownloadLeftByAnInterruptedInstall() {
        val partial = File(staged, "${key.fileName}.foz.part").apply { writeBytes(ByteArray(4)) }

        assertNull(adopt(localVersion = null))
        assertFalse(partial.exists())
    }

    @Test fun keepsTheContainersPoolWhenTheReplacementCannotBeWritten() {
        poolDir.mkdirs()
        poolFile.writeBytes(byteArrayOf(9))
        poolMeta.writeText(StagedPools.meta(4, "cd", 1, 1).toString())
        File(poolDir, "${poolMeta.name}.adopt").mkdirs()
        stage(5, ByteArray(10))

        assertNull(adopt(localVersion = 4))
        assertArrayEquals(byteArrayOf(9), poolFile.readBytes())
        assertEquals(4L, JSONObject(poolMeta.readText()).getLong("version"))
    }
}
