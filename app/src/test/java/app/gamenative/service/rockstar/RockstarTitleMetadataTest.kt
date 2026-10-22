package app.gamenative.service.rockstar

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RockstarTitleMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rglm(json: String, iv: ByteArray = ByteArray(16) { (it * 7 + 3).toByte() }): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), IvParameterSpec(iv))
        val payload = cipher.doFinal(json.toByteArray())
        val header = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            .put("RGLM".toByteArray()).putInt(1).putInt(iv.size + payload.size).array()
        return header + iv + payload
    }

    private val gta5 = """
        {
        	"metadataVersion": 1,
        	"titleId": "gta5", // launcher title
        	"rosTitleId": 11,
        	/* save layout */
        	"appdataFolderName": "GTA V",
        	"cloudSaveRoot": "docs//Rockstar Games",
        	"cloudSaveFiles": [
        		"SGTA50000",
        		"SGTA50015",
        	],
        	"platforms": {
        		"default": {
        			"cloud": true,
        			"imageUrl": "https://example.invalid/a.png",
        		},
        	},
        	"gamePlatform": "steam",
        }
    """.trimIndent().replace("\n", "\r\n")

    private val lanoire = """
        {
        	"titleId": "lanoire",
        	"rosTitleId": 9,
        	"appdataFolderName": "L.A. Noire",
        	"cloudSaveFolder": "L.A. Noire Saves",
        	"cloudSaveFiles": ["prefs.lanoire", "SaveGame1.lanoire"],
        	"platforms": {
        		"steam": { "cloud": false },
        		"default": { "cloud": true }
        	},
        	"gamePlatform": "steam",
        	"steamAppIds": [110800]
        }
    """.trimIndent()

    @Test fun parsesGta5LikeRecord() {
        val title = RockstarTitleMetadata.parse(rglm(gta5))!!
        assertEquals("gta5", title.titleId)
        assertEquals(11, title.rosTitleId)
        assertEquals("steam", title.gamePlatform)
        assertEquals("GTA V", title.appdataFolderName)
        assertEquals("docs//Rockstar Games", title.cloudSaveRoot)
        assertNull(title.cloudSaveFolder)
        assertEquals("GTA V", title.saveFolderName)
        assertEquals(listOf("SGTA50000", "SGTA50015"), title.cloudSaveFiles)
        assertFalse(title.cloudSaveCompression)
        assertEquals(mapOf("default" to true), title.platformCloud)
        assertTrue(title.cloudEnabled())
    }

    @Test fun parsesLaNoireLikeRecordWithSteamCloudDisabled() {
        val title = RockstarTitleMetadata.parse(rglm(lanoire))!!
        assertEquals("lanoire", title.titleId)
        assertEquals("L.A. Noire Saves", title.saveFolderName)
        assertEquals(listOf("prefs.lanoire", "SaveGame1.lanoire"), title.cloudSaveFiles)
        assertEquals(mapOf("steam" to false, "default" to true), title.platformCloud)
        assertFalse(title.cloudEnabled())
        assertTrue(title.cloudEnabled("default"))
        assertTrue(title.cloudEnabled("epic"))
    }

    @Test fun readsCompressionFlag() {
        val json = """{"titleId":"gta4","rosTitleId":1,"cloudSaveCompression":true,"cloudSaveFiles":["SGTA400","ProfileSettings"]}"""
        val title = RockstarTitleMetadata.parse(rglm(json))!!
        assertTrue(title.cloudSaveCompression)
        assertNull(title.gamePlatform)
        assertFalse(title.cloudEnabled())
    }

    @Test fun missingCloudFieldsGiveEmptyDefaults() {
        val title = RockstarTitleMetadata.parse(rglm("""{"titleId":"bully","rosTitleId":20}"""))!!
        assertEquals(emptyList<String>(), title.cloudSaveFiles)
        assertEquals(emptyMap<String, Boolean>(), title.platformCloud)
        assertNull(title.saveFolderName)
        assertNull(title.cloudSaveRoot)
        assertFalse(title.cloudSaveCompression)
        assertFalse(title.cloudEnabled())
    }

    @Test fun rejectsRecordsWithoutIdentity() {
        assertNull(RockstarTitleMetadata.parse(rglm("""{"rosTitleId":11}""")))
        assertNull(RockstarTitleMetadata.parse(rglm("""{"titleId":"gta5"}""")))
        assertNull(RockstarTitleMetadata.parse(rglm("""["gta5"]""")))
    }

    @Test fun truncatedOrGarbageInputReturnsNull() {
        val good = rglm(gta5)
        assertNull(RockstarTitleMetadata.parse(good.copyOf(good.size - 16)))
        assertNull(RockstarTitleMetadata.parse(good.copyOf(good.size - 1)))
        assertNull(RockstarTitleMetadata.parse(good.copyOf(90)))
        assertNull(RockstarTitleMetadata.parse(ByteArray(0)))
        assertNull(RockstarTitleMetadata.parse(ByteArray(512) { (it * 31 + 5).toByte() }))
        assertNull(RockstarTitleMetadata.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(RockstarTitleMetadata.parse(good.copyOf().also { it[4] = 2 }))
        assertNull(RockstarTitleMetadata.parse(good.copyOf().also { it[100] = (it[100] + 1).toByte() }))
        assertNull(RockstarTitleMetadata.parse(rglm("""{"titleId":"gta5","rosTitleId":11 /* open""")))
        assertNull(RockstarTitleMetadata.parse(File(temporary.root, "missing.rgl")))
    }

    @Test fun findsTitleInNestedDirectory() {
        val game = temporary.newFolder("game")
        File(game, "readme.rgl").writeBytes(rglm(lanoire))
        File(game, "bin").mkdirs()
        File(game, "bin/title.rgl").writeText("not a container")
        val nested = File(game, "pc/data").apply { mkdirs() }
        val metadata = File(nested, "Title.RGL").apply { writeBytes(rglm(gta5)) }
        assertEquals(metadata, RockstarTitleMetadata.find(game))
        assertEquals("gta5", RockstarTitleMetadata.parse(RockstarTitleMetadata.find(game)!!)!!.titleId)
    }

    @Test fun prefersShallowestTitle() {
        val game = temporary.newFolder("game")
        File(game, "a/b").mkdirs()
        File(game, "a/b/title.rgl").writeBytes(rglm(lanoire))
        File(game, "title.rgl").writeBytes(rglm(gta5))
        assertEquals(File(game, "title.rgl"), RockstarTitleMetadata.find(game))
    }

    @Test fun stopsAtBoundedDepth() {
        val game = temporary.newFolder("game")
        val deep = File(game, "a/b/c/d").apply { mkdirs() }
        File(deep, "title.rgl").writeBytes(rglm(gta5))
        assertNull(RockstarTitleMetadata.find(game))
        assertNotNull(RockstarTitleMetadata.find(File(game, "a")))
        assertNull(RockstarTitleMetadata.find(File(game, "missing")))
    }

    @Test fun skipsUnreadableTitleForValidNestedOne() {
        val game = temporary.newFolder("game")
        File(game, "title.rgl").writeBytes(byteArrayOf(82, 71, 76, 77) + ByteArray(200) { it.toByte() })
        val nested = File(game, "x64").apply { mkdirs() }
        val metadata = File(nested, "title.rgl").apply { writeBytes(rglm(gta5)) }
        assertEquals(metadata, RockstarTitleMetadata.find(game))
    }
}
