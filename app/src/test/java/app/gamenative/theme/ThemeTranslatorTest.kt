package app.gamenative.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeTranslatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sheet(css: String, resolve: (String) -> String? = { null }) = SourceSheet(CssParser.parse(css), resolve)

    private fun translate(vararg css: String, map: SteamClassMap = SteamClassMap.EMPTY) =
        ThemeTranslator.translate(css.map { sheet(it) }, map)

    private fun argb(hex: String) = CssValues.parseColor(hex)!!.toArgb()

    @Test
    fun readableClassNamesMapToRoles() {
        val r = translate(
            """
            .gamepadui_BasicHome_ab12c { background-color: #101010; }
            .quickaccessmenu_QuickAccessMenu_xy9zq { background: rgba(32, 32, 48, 0.8); }
            .gamepaddialog_FieldLabel_q1w2e { color: #eeeeee; }
            .gamepaddialog_FieldDescription_r4t5y { color: #999999; }
            .gamepaddialog_WithBottomSeparatorStandard_u7i8o::after { background: #333333; }
            .appportrait_LibraryItemBox_p0o9i { border-radius: 10px; }
            .DialogButton.Primary { background: #e05a00; color: #ffffff; }
            """.trimIndent(),
        )
        val s = r.spec
        assertEquals(argb("#101010"), s.background)
        assertEquals(argb("#202030"), s.surface)
        assertEquals(argb("#eeeeee"), s.text)
        assertEquals(argb("#999999"), s.textMuted)
        assertEquals(argb("#333333"), s.outline)
        assertNull("a tile radius is not the app-wide radius", s.cornerRadiusDp)
        assertEquals(10f, s.layout?.cardCornerRadiusDp)
        assertEquals(argb("#e05a00"), s.primary)
        assertEquals(argb("#ffffff"), s.onPrimary)
        assertEquals(7, r.totalRules)
        assertEquals(7, r.appliedRules)
    }

    @Test
    fun opaqueClassNamesNeedTheClassMap() {
        val css = "._3kDfK9pXq2LmNw8Rz1Ab4 { background: #224466; }"
        assertNull(translate(css).spec.background)
        val map = SteamClassMap(mapOf("_3kDfK9pXq2LmNw8Rz1Ab4" to "gamepadpage_GamepadPage"))
        assertEquals(argb("#224466"), translate(css, map = map).spec.background)
        val attr = "[class*=\"_3kDfK9pXq2LmNw8Rz1Ab4\"] { background: #335577; }"
        assertEquals(argb("#335577"), translate(attr, map = map).spec.background)
    }

    @Test
    fun classMapReducesStableJson() {
        val stable = """
            {
              "Ab12_GamepadPage": ["gamepadpage_GamepadPage_3yThy", "gamepadpage_GamepadPage", "_1aB2c3D4e5F6g7H8i9J0kL"],
              "Ab12_Unrelated": ["gamepadpage_Unrelated_9zZzz", "gamepadpage_Unrelated", "_9zY8x7W6v5U4t3S2r1Q0pO"],
              "Ab12_Header": ["_2qW3e4R5t6Y7u8I9o0PaSd"]
            }
        """.trimIndent()
        val map = SteamClassMap.reduceStableJson(stable)
        assertEquals("gamepadpage_GamepadPage", map.names["_1aB2c3D4e5F6g7H8i9J0kL"])
        assertEquals("gamepadpage_Header", map.names["_2qW3e4R5t6Y7u8I9o0PaSd"])
        assertNull(map.names["_9zY8x7W6v5U4t3S2r1Q0pO"])
        assertEquals(map.names, SteamClassMap.fromJson(map.toJson()).names)
    }

    @Test
    fun toggleOnColourComesFromBeforeOrOnState() {
        assertNull(translate(".gamepaddialog_ToggleRail_aaaaa { background: #ff0000; }").spec.toggle)
        assertEquals(argb("#00aa55"), translate(".gamepaddialog_ToggleRail_aaaaa::before { background: #00aa55; }").spec.toggle)
        assertEquals(
            argb("#00aa55"),
            translate(".gamepaddialog_ToggleRail_aaaaa.gamepaddialog_On_bbbbb { background: #00aa55; }").spec.toggle,
        )
        assertEquals(argb("#00aa55"), translate(".gamepaddialog_ToggleRail_aaaaa { &::before { background: #00aa55; } }").spec.toggle)
    }

    @Test
    fun focusFromStateAndKeyframes() {
        assertEquals(argb("#ffcc00"), translate(".gamepaddialog_Field_aaaaa.gpfocus { background: #ffcc00; }").spec.focus)
        assertEquals(argb("#ffcc00"), translate(".DialogButton:focus { border: 2px solid #ffcc00; }").spec.focus)
        val kf = translate("@keyframes gamepaddialog_FocusRingAnimation_ab12c { 0% { border-color: #00ffee; } 100% { border-color: #00ffee; } }")
        assertEquals(argb("#00ffee"), kf.spec.focus)
        assertEquals("keyframes are not style rules", 0, kf.appliedRules)
        assertNull(translate("@keyframes myOwnPulse { 0% { border-color: #00ffee; } }").spec.focus)
    }

    @Test
    fun barKeepsAlpha() {
        val s = translate(".header_Header_abcde { background-color: rgba(0, 0, 0, 0.5); backdrop-filter: blur(8px); }").spec
        assertEquals(argb("#00000080"), s.bar)
        assertEquals(8f, s.blurDp)
    }

    @Test
    fun varsResolveThroughRoot() {
        val s = translate(
            ":root { --accent: #12ab34; --text: var(--light, #f0f0f0); }",
            ".progressbar_Percent_abcde { background: var(--accent); } .mainmenu_ItemLabel_abcde { color: var(--text); }",
        ).spec
        assertEquals(argb("#12ab34"), s.toggle)
        assertEquals(argb("#f0f0f0"), s.text)
    }

    @Test
    fun steamRootVariableOverride() {
        val s = translate(":root { --gpColor-Blue: #ff6600; }").spec
        assertEquals(argb("#ff6600"), s.primary)
        assertEquals(argb("#ff6600"), s.focus)
    }

    @Test
    fun gnVariablesBeatInferredValues() {
        val dir = tmp.newFolder("t")
        File(dir, "img").mkdirs()
        val bg = File(dir, "img/bg.png").apply { writeBytes(byteArrayOf(1)) }
        val font = File(dir, "f.ttf").apply { writeBytes(byteArrayOf(1)) }
        val assets = ThemeAssets(tmp.root)
        val r = ThemeTranslator.translate(
            listOf(
                sheet(".gamepadui_BasicHome_ab12c { background: #101010; }"),
                sheet(
                    """
                    :root {
                      --gn-background: #202020;
                      --gn-primary: hsl(200, 100%, 50%);
                      --gn-bar: rgba(10, 20, 30, 0.25);
                      --gn-corner-radius: 6px;
                      --gn-panel-opacity: 80%;
                      --gn-blur: 4px;
                      --gn-font: url(f.ttf);
                      --gn-background-image: url("img/bg.png");
                      --gn-background-gradient: linear-gradient(to bottom, #000, #111);
                    }
                    """.trimIndent(),
                ) { assets.resolve(it, dir, "") },
            ),
            SteamClassMap.EMPTY,
        )
        val s = r.spec
        assertEquals(argb("#202020"), s.background)
        assertEquals(argb("#00aaff"), s.primary)
        assertEquals(argb("#0a141e40"), s.bar)
        assertEquals(6f, s.cornerRadiusDp)
        assertEquals(0.8f, s.panelOpacity!!, 0.001f)
        assertEquals(4f, s.blurDp)
        assertEquals(font.canonicalPath, s.fontFile)
        assertEquals(bg.canonicalPath, s.backgroundImage)
        assertEquals(180f, s.backgroundGradient!!.angleDeg)
        assertEquals("the page rule's colour was replaced, so only the :root rule counts", 1, r.appliedRules)
    }

    @Test
    fun fontFromFontFaceNeedsALocalFile() {
        val dir = tmp.newFolder("f")
        File(dir, "fonts").mkdirs()
        val font = File(dir, "fonts/My Font.otf").apply { writeBytes(byteArrayOf(1)) }
        val assets = ThemeAssets(tmp.root)
        val css = """
            @font-face { font-family: "My Font"; src: url(/themes_custom/Some%20Folder/fonts/My%20Font.otf); }
            :root { font-family: "My Font", sans-serif; }
        """.trimIndent()
        val s = ThemeTranslator.translate(listOf(sheet(css) { assets.resolve(it, dir, "") }), SteamClassMap.EMPTY).spec
        assertEquals(font.canonicalPath, s.fontFile)
    }

    @Test
    fun assetsNeverResolveOutsideTheThemesFolder() {
        val dir = tmp.newFolder("themes", "a")
        File(tmp.root, "secret.txt").writeText("x")
        val assets = ThemeAssets(File(tmp.root, "themes"))
        assertNull(assets.resolve("../../secret.txt", dir, ""))
        assertNull(assets.resolve("/themes_custom/a/../../../secret.txt", dir, ""))
        assertNull(assets.resolve("file://${File(tmp.root, "secret.txt").absolutePath}", dir, ""))
        assertNull(assets.resolve("https://example.com/x.png", dir, ""))
    }

    @Test
    fun laterRuleWithSameSelectorReplacesEarlier() {
        val s = translate(".mainmenu_ItemLabel_abcde { color: #111111; } .mainmenu_ItemLabel_abcde { color: #222222; }").spec
        assertEquals(argb("#222222"), s.text)
    }

    @Test
    fun layoutOnlyThemeSetsNothing() {
        val r = translate(".gamepadui_BasicHome_ab12c { width: 50%; transform: rotate(3deg); }")
        assertTrue(r.spec.isEmpty)
        assertEquals(1, r.totalRules)
        assertEquals(0, r.appliedRules)
    }

    @Test
    fun overlayLaterThemeWins() {
        val a = ThemeSpec(primary = 1, text = 2)
        val b = ThemeSpec(primary = 3, bar = 4)
        assertEquals(ThemeSpec(primary = 3, text = 2, bar = 4), ThemeSpec.EMPTY.overlay(a).overlay(b))
    }
}
