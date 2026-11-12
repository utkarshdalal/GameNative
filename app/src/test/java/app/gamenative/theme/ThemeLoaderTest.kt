package app.gamenative.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var themes: File
    private lateinit var assets: ThemeAssets

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    private fun folder(name: String, vararg files: Pair<String, String>): File {
        val dir = File(themes, name)
        for ((path, body) in files) File(dir, path).apply { parentFile?.mkdirs() }.writeText(body)
        return dir
    }

    private fun unit(dir: File, values: ThemeValues = ThemeValues()) = ThemeUnit(dir, ThemeLoader.loadManifest(dir), values)

    private fun translate(units: List<ThemeUnit>) =
        ThemeTranslator.translate(units.flatMap { ThemeLoader.sheets(it, assets) }, SteamClassMap.EMPTY)

    @Before
    fun setUp() {
        themes = tmp.newFolder("themes")
        assets = ThemeAssets(themes)
        ThemeLoader.clearCache()
    }

    @Test
    fun dependencyIsAppliedFirstWithTheParentsValues() {
        val base = folder(
            "Base",
            "theme.json" to """
                { "name": "Base", "patches": { "Mode": { "default": "Light", "type": "dropdown", "values": {
                  "Light": { "light.css": ["SP"] }, "Dark": { "dark.css": ["SP"] } } } } }
            """.trimIndent(),
            "light.css" to ".gamepadui_BasicHome_ab12c { background: #eeeeee; }",
            "dark.css" to ".gamepadui_BasicHome_ab12c { background: #111111; } .mainmenu_ItemLabel_abcde { color: #aaaaaa; }",
        )
        val parent = folder(
            "Parent",
            "theme.json" to """{ "name": "Parent", "inject": { "p.css": ["SP"] }, "dependencies": { "Base": { "Mode": "Dark" }, "Gone": {} } }""",
            "p.css" to ".mainmenu_ItemLabel_abcde { color: #ffffff; }",
        )
        val units = mapOf("Base" to unit(base), "Parent" to unit(parent))
        val (ordered, missing) = ThemeLoader.withDependencies(units.getValue("Parent")) { units[it] }
        assertEquals(listOf(base, parent), ordered.map { it.folder })
        assertEquals("Dark", ordered[0].values.choices["Mode"])
        assertEquals(listOf("Gone"), missing)

        val spec = translate(ordered).spec
        assertEquals(argb("#111111"), spec.background)
        assertEquals("the parent's later rule wins", argb("#ffffff"), spec.text)

        assertEquals(argb("#eeeeee"), translate(listOf(units.getValue("Base"))).spec.background)
    }

    @Test
    fun dependencyCyclesTerminate() {
        val a = folder("A", "theme.json" to """{ "name": "A", "dependencies": { "B": {} } }""")
        val b = folder("B", "theme.json" to """{ "name": "B", "dependencies": { "A": {} } }""")
        val units = mapOf("A" to unit(a), "B" to unit(b))
        val (ordered, _) = ThemeLoader.withDependencies(units.getValue("A")) { units[it] }
        assertEquals(listOf(b, a), ordered.map { it.folder })
    }

    @Test
    fun cssOnlyFolderAndThemesCustomUrls() {
        val dir = folder(
            "Plain",
            "theme.css" to ".gamepadui_BasicHome_ab12c { background: url(/themes_custom/Plain%20Theme/img/bg.jpg); }",
            "img/bg.jpg" to "x",
        )
        val spec = translate(listOf(unit(dir))).spec
        assertEquals(File(dir, "img/bg.jpg").canonicalPath, spec.backgroundImage)
    }

    @Test
    fun injectFilesOutsideTheThemeAreIgnored() {
        File(themes, "outside.css").writeText(".gamepadui_BasicHome_ab12c { background: #123456; }")
        val dir = folder("Evil", "theme.json" to """{ "name": "Evil", "inject": { "../outside.css": ["SP"] } }""")
        assertNull(translate(listOf(unit(dir))).spec.background)
    }

    @Test
    fun starterThemeTranslatesToItsVariables() {
        val dir = folder(
            "Starter",
            "theme.json" to StarterTheme.themeJson,
            "theme.css" to StarterTheme.themeCss,
            StarterTheme.IMAGE to "png",
        )
        val gradient = translate(listOf(unit(dir))).spec
        assertEquals(argb("#4fc3f7"), gradient.primary)
        assertEquals(argb("#4fc3f7"), gradient.focus)
        assertEquals(argb("#182030"), gradient.surface)
        assertEquals(argb("#0b0f17cc"), gradient.bar)
        assertEquals(12f, gradient.cornerRadiusDp)
        assertEquals(160f, gradient.backgroundGradient!!.angleDeg)
        assertNull(gradient.backgroundImage)

        val custom = translate(
            listOf(unit(dir, ThemeValues(choices = mapOf("Background" to "Image"), colors = mapOf("Accent/Accent colour" to "#ff00ff")))),
        ).spec
        assertEquals(argb("#ff00ff"), custom.primary)
        assertEquals(argb("#ff00ff"), custom.focus)
        assertEquals(File(dir, StarterTheme.IMAGE).canonicalPath, custom.backgroundImage)

        val manifest = ThemeLoader.loadManifest(dir)
        assertEquals(listOf("Background", "Accent"), manifest.patches.map { it.name })
        assertEquals(
            "every --gn variable is documented",
            emptyList<String>(),
            ThemeTranslator.GN_VARIABLES.keys.filterNot { StarterTheme.themeCss.contains(it) },
        )
    }
}
