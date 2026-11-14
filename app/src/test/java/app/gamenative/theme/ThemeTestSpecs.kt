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

/**
 * Fails on the first null field of [o], nested theme value classes included (fills, gradients, frames, reflections and
 * depth steps are leaves); fields named in [skip] are not checked.
 */
internal fun assertEveryFieldSet(o: Any, path: String, skip: Set<String> = emptySet()) {
    for (f in o.javaClass.declaredFields) {
        if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.name in skip) continue
        f.isAccessible = true
        val v = f.get(o)
        assertNotNull("$path.${f.name} has no option", v)
        val leaf = v is ThemeFill || v is ThemeGradient || v is ThemeTileFrame || v is ThemeReflection || v is ThemeDepthStep
        if (v!!.javaClass.name.startsWith("app.gamenative.theme.Theme") && !leaf) assertEveryFieldSet(v, "$path.${f.name}", skip)
    }
}

/** Layout values only a Steam Deck theme's CSS sets: the Custom theme has no option for them. */
internal val CSS_ONLY_LAYOUT_FIELDS = setOf(
    "tileFrames", "tileFrameWide", "shelfRowImage", "coverReflection", "cardRestBrightness", "cardRestSaturation", "cardFocusedScale",
    "cardDepthSteps", "cardSpineWidthDp", "cardSpineColor", "shelfFitAll", "shelfFocusCentered", "shelfHeaderText", "gridColumns",
    "gridTileAspect", "gridTitleBelow", "uninstalledSaturation", "uninstalledOpacity", "uninstalledIcon", "tabColors", "hideTabCounts",
    "heroImage", "heroImageTile", "heroImageScale", "heroHeightFraction", "homeOverlay", "screenOverlay", "footerHiddenHints",
    "hideTopBarSearch",
)

/** A layout with every [CSS_ONLY_LAYOUT_FIELDS] value set, its images as files in [dir]. */
internal fun cssOnlyLayout(dir: File): ThemeLayout {
    dir.mkdirs()
    fun file(name: String) = File(dir, name).apply { writeBytes(byteArrayOf(1)) }.absolutePath
    val lines = ThemeFill(listOf(ThemeGradient(180f, listOf(0x00000000, 0x40000000), listOf(0.5f, 0.5f), repeatDp = 3f)))
    return ThemeLayout(
        tileFrames = listOf(ThemeTileFrame(file("frame.png"), file("mask.png"), 0.07f, 0.07f, 0.86f, 0.86f)),
        tileFrameWide = ThemeTileFrame(file("wide.png")),
        shelfRowImage = file("row.png"),
        coverReflection = ThemeReflection(0.3f, 0.5f, 3f, 0.5f, 0f),
        cardRestBrightness = 0.5f,
        cardRestSaturation = 0.5f,
        cardFocusedScale = 1.2f,
        cardDepthSteps = listOf(ThemeDepthStep(0.86f, 0f, -5f), ThemeDepthStep(0.76f, -0.14f, -10f)),
        cardSpineWidthDp = 23f,
        cardSpineColor = 0xFF222222.toInt(),
        shelfFitAll = true,
        shelfFocusCentered = true,
        shelfHeaderText = "Welcome back",
        gridColumns = 5,
        gridTileAspect = 1f,
        gridTitleBelow = true,
        uninstalledSaturation = 0f,
        uninstalledOpacity = 0.5f,
        uninstalledIcon = file("icon.png"),
        tabColors = listOf(0xFF1A1A1A.toInt(), 0xFF2A2A2A.toInt()),
        hideTabCounts = true,
        heroImage = file("hero.png"),
        heroImageTile = true,
        heroImageScale = 1.5f,
        heroHeightFraction = 0.5f,
        homeOverlay = lines,
        screenOverlay = lines,
        footerHiddenHints = listOf("A", "B"),
        hideTopBarSearch = true,
    )
}
