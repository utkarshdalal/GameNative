package app.gamenative.theme.sound

import app.gamenative.theme.ThemeStoreClient
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SoundPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wav(samples: Int = 64): ByteArray {
        val data = samples * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(22050); putInt(44100); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            repeat(samples) { putShort(((it % 16) * 512).toShort()) }
        }.array()
    }

    private fun pack(json: String, vararg files: String): File {
        val dir = tmp.newFolder()
        File(dir, "pack.json").writeText(json)
        for (f in files) File(dir, f).apply { parentFile?.mkdirs() }.writeBytes(wav())
        return dir
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("p${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun parsesManifestVersion1() {
        val m = SoundPackManifest.parse("""{"name":"Old","author":"a","version":"v1.0","manifest_version":1,"music":false,"ignore":["deck_ui_typing.wav"]}""")
        assertEquals("Old", m.name)
        assertEquals("a", m.author)
        assertEquals("v1.0", m.version)
        assertEquals(1, m.manifestVersion)
        assertFalse(m.music)
        assertEquals(setOf("deck_ui_typing.wav"), m.ignore)
        assertTrue(m.mappings.isEmpty())
    }

    @Test
    fun parsesManifestVersion2WithMappingsAndBom() {
        val m = SoundPackManifest.parse(
            "﻿" + """{"name":" New ","description":"d","manifest_version":2,"music":true,"id":"abc",
                "mappings":{"deck_ui_toast.wav":["a.wav","b.mp3"],"deck_ui_typing.wav":"t.wav","bad":[1,""],"deck_ui_launch_game.wav":[]}}""",
        )
        assertEquals("New", m.name)
        assertEquals("d", m.description)
        assertEquals(2, m.manifestVersion)
        assertTrue(m.music)
        assertEquals("abc", m.storeId)
        assertEquals(mapOf("deck_ui_toast.wav" to listOf("a.wav", "b.mp3"), "deck_ui_typing.wav" to listOf("t.wav")), m.mappings)
    }

    @Test
    fun manifestDefaultsAndMissingName() {
        val m = SoundPackManifest.parse("""{"name":"X"}""")
        assertEquals(1, m.manifestVersion)
        assertFalse(m.music)
        assertNull(m.author)
        try {
            SoundPackManifest.parse("""{"author":"x"}""")
            fail()
        } catch (e: IOException) {
        }
    }

    @Test
    fun tableCoversEveryUiSoundWithSteamNames() {
        for (s in UiSound.entries) {
            val names = SteamSounds.TABLE[s]
            assertTrue("$s has no Steam sound", !names.isNullOrEmpty())
            for (n in names!!) assertTrue("$n is not a Steam sound", n in SteamSounds.ALL)
        }
        assertEquals(33, SteamSounds.ALL.toSet().size)
    }

    @Test
    fun directFilesIgnoreAndFallback() {
        val dir = pack(
            """{"name":"P","ignore":["deck_ui_misc_10.wav","deck_ui_toast.wav"]}""",
            "deck_ui_misc_10.wav", "deck_ui_navigation.wav", "deck_ui_toast.wav", "deck_ui_default_activation.wav",
        )
        val m = SoundPackManifest.load(dir)
        assertEquals(listOf("deck_ui_navigation.wav"), SteamSounds.candidates(dir, m, UiSound.NAVIGATE).map { it.name })
        assertEquals(listOf("deck_ui_default_activation.wav"), SteamSounds.candidates(dir, m, UiSound.SELECT).map { it.name })
        assertTrue(SteamSounds.candidates(dir, m, UiSound.NOTIFY).isEmpty())
        assertTrue(SteamSounds.candidates(dir, m, UiSound.LAUNCH).isEmpty())
        assertEquals(2, SteamSounds.soundCount(dir, m))
    }

    @Test
    fun mappingsPickRandomlyAndDropUnsafeOrMissingFiles() {
        val dir = pack(
            """{"name":"P","manifest_version":2,"mappings":{"deck_ui_typing.wav":["k/1.wav","k/2.wav","../x.wav","missing.wav","k/readme.txt"]}}""",
            "k/1.wav", "k/2.wav",
        )
        File(dir, "k/readme.txt").writeText("x")
        val m = SoundPackManifest.load(dir)
        val files = SteamSounds.candidates(dir, m, UiSound.TYPE)
        assertEquals(listOf("1.wav", "2.wav"), files.map { it.name })
        val rnd = Random(7)
        val picked = (1..50).mapNotNull { SteamSounds.pick(files, rnd)?.name }.toSet()
        assertEquals(setOf("1.wav", "2.wav"), picked)
    }

    @Test
    fun mappedFileNameMatchesIgnoringCase() {
        val dir = pack("""{"name":"P","mappings":{"deck_ui_launch_game.wav":["Launch.WAV"]}}""", "launch.wav")
        assertEquals("launch.wav", SteamSounds.candidates(dir, SoundPackManifest.load(dir), UiSound.LAUNCH).single().name)
    }

    @Test
    fun musicIntroAndMenu() {
        val dir = pack("""{"name":"M","music":true}""", "menu_music.mp3")
        val m = SoundPackManifest.load(dir)
        assertEquals("menu_music.mp3", SteamSounds.menuMusic(dir, m)?.name)
        assertNull(SteamSounds.introMusic(dir, m))
        assertEquals(1, SteamSounds.soundCount(dir, m))
        File(dir, "intro_music.mp3").writeBytes(wav())
        assertEquals("intro_music.mp3", SteamSounds.introMusic(dir, m)?.name)
        val mapped = pack("""{"name":"M2","music":true,"mappings":{"menu_music.mp3":["loop.ogg"]}}""", "loop.ogg")
        assertEquals("loop.ogg", SteamSounds.menuMusic(mapped, SoundPackManifest.load(mapped))?.name)
    }

    @Test
    fun navigateRateLimit() {
        assertTrue(SteamSounds.navigateAllowed(0L, 1000L))
        assertFalse(SteamSounds.navigateAllowed(1000L, 1000L))
        assertFalse(SteamSounds.navigateAllowed(1000L, 1034L))
        assertTrue(SteamSounds.navigateAllowed(1000L, 1035L))
        assertTrue(SteamSounds.navigateAllowed(1000L, 900L))
    }

    @Test
    fun settingsRoundTrip() {
        val state = SoundState(
            SoundSettings(enabled = true, volume = 0.25f, activePackId = "Switch", musicEnabled = true, musicVolume = 0.75f, activeMusicPackId = "Wii"),
            mapOf("Switch" to SoundPackOrigin("id-1", "v1.1"), "Local" to SoundPackOrigin()),
        )
        assertEquals(state, SoundState.parse(state.toJson()))
        assertEquals(SoundState(), SoundState.parse(SoundState().toJson()))
    }

    @Test
    fun settingsDefaultsAndClamp() {
        assertEquals(SoundState(), SoundState.parse(null))
        assertEquals(SoundState(), SoundState.parse("{not json"))
        val s = SoundState.parse("""{"settings":{"volume":4,"musicVolume":-1,"enabled":"yes","activePackId":""}}""").settings
        assertEquals(1f, s.volume)
        assertEquals(0f, s.musicVolume)
        assertFalse(s.enabled)
        assertNull(s.activePackId)
        assertFalse(SoundSettings().enabled)
        assertFalse(SoundSettings().musicEnabled)
    }

    @Test
    fun storeAudioListMapping() {
        val json = """{"total":2,"items":[
            {"id":"a1","name":"pack_a","displayName":"Pack A","type":"Audio","target":"Audio","version":"v1.1",
             "author":{"username":"someone"},"images":[{"id":"img1"}],"download":{"id":"blob1","downloadCount":1234}},
            {"id":"m1","name":"Menu","target":"Music","specifiedAuthor":"other","download":{"id":"blob2","downloadCount":5.0}},
            {"name":"no id"}]}"""
        val items = ThemeStoreClient.parseList(json)
        assertEquals(2, items.size)
        val a = items[0]
        assertEquals("a1", a.id)
        assertEquals("Pack A", a.name)
        assertEquals("Audio", a.target)
        assertEquals("someone", a.author)
        assertEquals("v1.1", a.version)
        assertEquals("blob1", a.downloadBlobId)
        assertEquals(1234, a.downloads)
        assertEquals("https://api.deckthemes.com/blobs/img1", a.previewImageUrl)
        assertEquals("Music", items[1].target)
        assertEquals("other", items[1].author)
        assertEquals(5, items[1].downloads)
        assertTrue(ThemeStoreClient.parseList("""{"total":0}""").isEmpty())
    }

    @Test
    fun unpackFindsNestedPackAndPrunesFiles() {
        val z = zip(
            "My Pack/pack.json" to """{"name":"My Pack"}""".toByteArray(),
            "My Pack/deck_ui_launch_game.wav" to wav(),
            "My Pack/run.sh" to "echo".toByteArray(),
            "My Pack/sub/lib.so" to byteArrayOf(1),
            "My Pack/cover.png" to byteArrayOf(1),
        )
        val root = SoundPackFiles.unpack(z, tmp.newFolder())
        assertEquals("My Pack", root.name)
        assertEquals(setOf("pack.json", "deck_ui_launch_game.wav", "cover.png"), root.walk().filter { it.isFile }.map { it.name }.toSet())
        assertFalse(File(root, "sub").exists())
        assertEquals("My Pack", SoundPackFiles.validate(root).name)
    }

    @Test
    fun unpackRejectsTooManyEntriesAndPacksWithoutSounds() {
        val many = (0..SoundPackFiles.MAX_ENTRIES).map { "f$it.txt" to byteArrayOf() } + ("pack.json" to """{"name":"x"}""".toByteArray())
        try {
            SoundPackFiles.unpack(zip(*many.toTypedArray()), tmp.newFolder())
            fail()
        } catch (e: IOException) {
        }
        val noSounds = SoundPackFiles.unpack(zip("pack.json" to """{"name":"x"}""".toByteArray(), "other.wav" to wav()), tmp.newFolder())
        try {
            SoundPackFiles.validate(noSounds)
            fail()
        } catch (e: IOException) {
        }
        val musicWithout = SoundPackFiles.unpack(zip("pack.json" to """{"name":"m","music":true}""".toByteArray()), tmp.newFolder())
        try {
            SoundPackFiles.validate(musicWithout)
            fail()
        } catch (e: IOException) {
        }
    }

    @Test
    fun packIds() {
        assertEquals("Pip-Boy Sounds (Fallout 3_New Vegas)", SoundPackFiles.packId("Pip-Boy Sounds (Fallout 3/New Vegas)"))
        assertEquals("pack_.hidden", SoundPackFiles.packId(".hidden"))
        assertEquals("Pack", SoundPackFiles.packId("  "))
    }
}
