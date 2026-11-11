package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckyThemeManifestTest {

    private val json = """
        {
          "name": "Sample",
          "display_name": "Sample Theme",
          "author": "me",
          "version": "v2.0",
          "manifest_version": 8,
          "flags": ["optional"],
          "tabs": { "default": ["SP"] },
          "inject": {
            "shared.css": ["SP", "QuickAccess"],
            "desktop.css": ["desktop"],
            "--root-size": ["10px", "All"],
            "fallback.css": []
          },
          "patches": {
            "Accent": {
              "type": "dropdown",
              "default": "Orange",
              "values": {
                "Orange": { "orange.css": ["SP"] },
                "Custom": {}
              },
              "components": [
                { "name": "Colour", "type": "color-picker", "on": "Custom", "default": "#3366ff",
                  "css_variable": "accent", "tabs": ["SP"] },
                { "name": "Bg", "type": "image-picker", "on": "Custom", "default": "Sample/bg one.png",
                  "css_variable": "--bg", "tabs": ["SP"] }
              ]
            },
            "Toggle": { "type": "checkbox", "default": "No", "values": { "Yes": {}, "Maybe": {} } }
          },
          "dependencies": { "Base": { "Mode": "Dark" } }
        }
    """.trimIndent()

    @Test
    fun parsesFieldsAndDefaults() {
        val m = DeckyThemeManifest.parse(json)
        assertEquals("Sample Theme", m.label)
        assertEquals("v2.0", m.version)
        assertEquals(listOf("OPTIONAL"), m.flags)
        assertEquals(mapOf("Base" to mapOf("Mode" to "Dark")), m.dependencies)
        val toggle = m.patches.first { it.name == "Toggle" }
        assertEquals("dropdown", toggle.type)
        assertEquals("Yes", toggle.default)
        assertEquals("--accent", m.patches[0].components[0].cssVariable)
    }

    @Test
    fun injectsFollowLoaderOrderAndTabs() {
        val m = DeckyThemeManifest.parse(json)
        val inj = m.injects(ThemeValues())
        assertEquals("shared.css", inj[0].file)
        assertTrue(inj[0].relevant)
        assertEquals("desktop.css", inj[1].file)
        assertFalse(inj[1].relevant)
        assertEquals(":root { --root-size: 10px; }", inj[2].css)
        assertTrue(inj[2].relevant)
        assertEquals("fallback.css", inj[3].file)
        assertTrue("empty tabs use the theme's default mapping", inj[3].relevant)
        assertEquals("orange.css", inj[4].file)
        assertEquals(5, inj.size)
    }

    @Test
    fun componentsInjectOnlyWhenTheirValueIsSelected() {
        val m = DeckyThemeManifest.parse(json)
        val inj = m.injects(ThemeValues(choices = mapOf("Accent" to "Custom"), colors = mapOf("Accent/Colour" to "#ff0000")))
        val css = inj.mapNotNull { it.css }
        assertTrue(css.contains(":root { --accent: #ff0000; --accent_r: 255; --accent_g: 0; --accent_b: 0; --accent_rgb: 255, 0, 0; }"))
        assertTrue(css.contains(":root { --bg: url(/themes_custom/Sample/bg%20one.png) }"))
        assertFalse(inj.any { it.file == "orange.css" })
    }

    @Test
    fun unknownChoiceFallsBackToDefault() {
        val m = DeckyThemeManifest.parse(json)
        assertEquals("Orange", m.patches[0].valueOf(ThemeValues(choices = mapOf("Accent" to "Nope"))))
    }

    @Test
    fun versionOnePatchFormat() {
        val m = DeckyThemeManifest.parse(
            """{ "name": "Old", "patches": { "Size": { "default": "Big", "Small": { "s.css": ["SP"] }, "Big": { "b.css": ["SP"] } } } }""",
        )
        assertEquals(1, m.manifestVersion)
        assertEquals(listOf("Small", "Big"), m.patches[0].optionNames)
        assertEquals(listOf("b.css"), m.injects(ThemeValues()).map { it.file })
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooNewManifestIsRejected() {
        DeckyThemeManifest.parse("""{ "name": "Future", "manifest_version": 99 }""")
    }

    @Test
    fun cssOnlyThemeInjectsThemeCssEverywhere() {
        val inj = DeckyThemeManifest.cssOnly("Folder").injects(ThemeValues()).single()
        assertEquals("theme.css", inj.file)
        assertTrue(inj.relevant)
    }

    @Test
    fun imagePickerRejectsEscapes() {
        assertFalse(DeckyThemeManifest.isSafeRelative("../x.png"))
        assertFalse(DeckyThemeManifest.isSafeRelative("/abs.png"))
        assertTrue(DeckyThemeManifest.isSafeRelative("Theme/img.png"))
    }
}
