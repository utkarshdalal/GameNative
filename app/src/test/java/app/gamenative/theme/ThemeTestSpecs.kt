package app.gamenative.theme

import java.io.File
import org.junit.Assert.assertNotNull

/**
 * The built-in theme in [themes] (its folder already written) translated with every option at a non-default value,
 * a background image and a font file.
 */
internal fun builtInWithEveryOption(themes: File): ThemeSpec {
    val dir = File(themes, BuiltInTheme.ID)
    val m = ThemeLoader.loadManifest(dir)
    val state = File(themes, ".state/${BuiltInTheme.ID}").apply { mkdirs() }
    val image = File(state, "bg.png").apply { writeBytes(byteArrayOf(1)) }
    val font = File(state, "font.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
    val choices = m.patches.associate { p -> p.name to if (p.type == "checkbox") "Yes" else p.optionNames.last() }
    val colors = m.patches.flatMap { it.components }.filter { it.type == DeckyThemeManifest.COLOR_PICKER }.associate { it.key to "#12345680" }
    val values = ThemeValues(
        choices = choices,
        colors = colors,
        images = mapOf("Background image/Image" to image.absolutePath),
        fonts = mapOf("Font/Font file" to font.absolutePath),
    )
    val unit = ThemeUnit(dir, m, values)
    return ThemeTranslator.translate(ThemeLoader.sheets(unit, ThemeAssets(themes)), SteamClassMap.EMPTY).spec
}

/** Fails on the first null field of [o], nested theme value classes included (fills and gradients are leaves). */
internal fun assertEveryFieldSet(o: Any, path: String) {
    for (f in o.javaClass.declaredFields) {
        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
        f.isAccessible = true
        val v = f.get(o)
        assertNotNull("$path.${f.name} has no option", v)
        if (v!!.javaClass.name.startsWith("app.gamenative.theme.Theme") && v !is ThemeFill && v !is ThemeGradient) assertEveryFieldSet(v, "$path.${f.name}")
    }
}
