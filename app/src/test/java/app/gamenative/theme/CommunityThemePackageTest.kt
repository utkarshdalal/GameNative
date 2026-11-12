package app.gamenative.theme

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommunityThemePackageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var themes: File
    private lateinit var assets: ThemeAssets

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4)

    @Before
    fun setUp() {
        themes = tmp.newFolder("themes")
        assets = ThemeAssets(themes)
        ThemeLoader.clearCache()
    }

    private fun native(css: String) = CommunityThemePackage.isNative(CssParser.parse(css))

    private fun translate(dir: File, values: ThemeValues = ThemeValues()): Pair<ThemeSpec, Boolean> {
        val sheets = ThemeLoader.sheets(ThemeUnit(dir, ThemeLoader.loadManifest(dir), values), assets)
        return ThemeTranslator.translate(sheets, SteamClassMap.EMPTY).spec to sheets.all { CommunityThemePackage.isNative(it.sheet) }
    }

    @Test
    fun nativeCssIsRootGnVariablesAndFontFaces() {
        assertTrue(native(":root { --gn-primary: #fff; --gn-font: url(fonts/a.ttf); } @font-face { font-family: A; src: url('fonts/a.ttf'); }"))
        assertTrue(native(":root { --gn-background-image: url(\"file:///data/themes/.state/x/img.png\") }"))
        assertTrue(native(":root { --gn-background-image: url(/themes_custom/Mine/bg.png) }"))
        assertFalse(native(".gamepadui_BasicHome_ab12c { background: #111; }"))
        assertFalse(native(":root { --gn-primary: #fff; color: red; }"))
        assertFalse(native(":root { --accent: #fff; }"))
        assertFalse(native(":root, body { --gn-primary: #fff; }"))
        assertFalse(native(":root { --gn-background-image: url(https://example.com/a.png); }"))
        assertFalse(native(":root { --gn-background-image: url(data:image/png;base64,AAAA); }"))
        assertFalse(native(":root { --gn-background-image: url(/abs/a.png); }"))
        assertFalse(native("@font-face { font-family: A; src: url(//cdn.example.com/a.ttf); }"))
        assertFalse(native("@keyframes spin { from { opacity: 0 } } :root { --gn-primary: #fff; }"))
        assertFalse(native("@import url(other.css); :root { --gn-primary: #fff; }"))
        assertFalse(native(":root { --gn-primary: #fff; .x { color: red; } }"))
    }

    @Test
    fun blockers() {
        val spec = ThemeSpec(primary = argb("#123456"))
        assertNull(CommunityThemePackage.blocker(true, spec))
        assertEquals(ShareBlocker.NOT_NATIVE, CommunityThemePackage.blocker(false, spec))
        assertEquals(ShareBlocker.EMPTY, CommunityThemePackage.blocker(true, ThemeSpec.EMPTY))
        val big = tmp.newFile("big.ttf")
        RandomAccessFile(big, "rw").use { it.setLength(CommunityThemePackage.MAX_ZIP_BYTES + 1) }
        assertEquals(ShareBlocker.TOO_LARGE, CommunityThemePackage.blocker(true, spec.copy(fontFile = big.path)))
        assertNull(CommunityThemePackage.blocker(true, spec.copy(fontFile = File(tmp.root, "missing.ttf").path)))
    }

    @Test
    fun builtInCustomThemeIsShareableOnceItSetsAValue() {
        val dir = File(themes, BuiltInTheme.ID).apply { mkdirs() }
        File(dir, "theme.json").writeText(BuiltInTheme.themeJson)
        val (defaults, nativeDefaults) = translate(dir)
        assertTrue(nativeDefaults)
        assertEquals(ShareBlocker.EMPTY, CommunityThemePackage.blocker(nativeDefaults, defaults))

        val picked = File(themes, ".state/${BuiltInTheme.ID}/img-1.img").apply { parentFile?.mkdirs(); writeBytes(png) }
        val values = ThemeValues(
            choices = mapOf("Accent colour" to "Yes", "Background image" to "Yes", "Corner radius" to "8"),
            colors = mapOf("Accent colour/Colour" to "#ff0000"),
            images = mapOf("Background image/Image" to picked.absolutePath),
        )
        val (spec, native) = translate(dir, values)
        assertTrue(native)
        assertNull(CommunityThemePackage.blocker(native, spec))
        assertEquals(argb("#ff0000"), spec.primary)
        assertEquals(8f, spec.cornerRadiusDp)
        assertEquals(picked.canonicalPath, spec.backgroundImage)
    }

    @Test
    fun translatedSteamThemeIsNotNative() {
        val dir = File(themes, "Steamy").apply { mkdirs() }
        File(dir, "theme.css").writeText(".gamepadui_BasicHome_ab12c { background: #222222; }")
        assertFalse(translate(dir).second)
    }

    @Test
    fun packageHoldsTheCurrentValuesWithFontAndImage() {
        val src = File(themes, "Mine").apply { mkdirs() }
        val font = File(src, "fonts/My Font.TTF").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(0, 1, 0, 0, 9)) }
        val picked = File(themes, ".state/Mine/img-1.img").apply { parentFile?.mkdirs(); writeBytes(png) }
        val spec = ThemeSpec(
            primary = argb("#4fc3f7"),
            fontFile = font.canonicalPath,
            backgroundImage = picked.canonicalPath,
            layout = ThemeLayout(cardScale = 0.8f, hideBadges = true),
        )
        val zip = tmp.newFile("share.zip")
        CommunityThemePackage.write(spec, "Blue Night", "Sam", "v2.0", "Calm blues", zip)

        ZipFile(zip).use { z ->
            assertEquals(setOf("theme.json", "theme.css", "font.ttf", "background.png"), z.entries().toList().map { it.name }.toSet())
            val json = Json.parseToJsonElement(z.getInputStream(z.getEntry("theme.json")).readBytes().decodeToString()) as JsonObject
            assertEquals("Blue Night", json["name"]!!.jsonPrimitive.content)
            assertEquals("Sam", json["author"]!!.jsonPrimitive.content)
            assertEquals("v2.0", json["version"]!!.jsonPrimitive.content)
            assertEquals("Calm blues", json["description"]!!.jsonPrimitive.content)
            assertEquals(DeckyThemeManifest.LOADER_VERSION.toString(), json["manifest_version"]!!.jsonPrimitive.content)
            val css = z.getInputStream(z.getEntry("theme.css")).readBytes().decodeToString()
            assertTrue(css.contains("--gn-font: url(\"font.ttf\");"))
            assertTrue(css.contains("--gn-background-image: url(\"background.png\");"))
            assertTrue(CommunityThemePackage.isNative(CssParser.parse(css)))
            assertTrue(z.getInputStream(z.getEntry("background.png")).readBytes().contentEquals(png))
        }

        val installed = File(themes, "Installed").apply { mkdirs() }
        ThemeZip.extract(zip, installed)
        val manifest = ThemeLoader.loadManifest(installed)
        assertEquals("Blue Night", manifest.name)
        val (back, native) = translate(installed)
        assertTrue(native)
        assertEquals(
            spec.copy(fontFile = File(installed, "font.ttf").canonicalPath, backgroundImage = File(installed, "background.png").canonicalPath),
            back,
        )
    }

    @Test
    fun missingOrUnsupportedFilesAreSkipped() {
        val gif = tmp.newFile("bg.gif").apply { writeBytes("GIF89a".toByteArray()) }
        val spec = ThemeSpec(primary = argb("#123456"), fontFile = File(tmp.root, "gone.ttf").path, backgroundImage = gif.path)
        val zip = tmp.newFile("share.zip")
        CommunityThemePackage.write(spec, "N", "A", "v1.0", "", zip)
        ZipFile(zip).use { z ->
            assertEquals(setOf("theme.json", "theme.css"), z.entries().toList().map { it.name }.toSet())
            val css = z.getInputStream(z.getEntry("theme.css")).readBytes().decodeToString()
            assertFalse(css.contains("url("))
        }
    }

    @Test
    fun imageTypeComesFromTheBytes() {
        assertEquals("png", CommunityThemePackage.imageExt(tmp.newFile("a.img").apply { writeBytes(png) }))
        assertEquals("jpg", CommunityThemePackage.imageExt(tmp.newFile("b.img").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)) }))
        assertEquals("webp", CommunityThemePackage.imageExt(tmp.newFile("c.img").apply { writeBytes("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()) }))
        assertNull(CommunityThemePackage.imageExt(tmp.newFile("d.gif").apply { writeBytes("GIF89a".toByteArray()) }))
    }
}
