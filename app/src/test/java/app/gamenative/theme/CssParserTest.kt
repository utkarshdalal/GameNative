package app.gamenative.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssParserTest {

    @Test
    fun commentsAndImportantAreHandled() {
        val sheet = CssParser.parse(
            """
            /* header { color: red; } */
            .a { color: #fff !important; background: url("x;y.png"); /* inline */ }
            .b { content: "/* not a comment */"; }
            """.trimIndent(),
        )
        assertEquals(2, sheet.rules.size)
        val a = sheet.rules[0]
        assertEquals(listOf(".a"), a.selectors)
        assertEquals(CssDecl("color", "#fff", true), a.decls[0])
        assertEquals("url(\"x;y.png\")", a.decls[1].value)
        assertEquals("\"/* not a comment */\"", sheet.rules[1].decls[0].value)
    }

    @Test
    fun selectorListsAndCustomPropertiesKeepCase() {
        val r = CssParser.parse(":root, html { --MyVar: 1px; COLOR: Red }").rules.single()
        assertEquals(listOf(":root", "html"), r.selectors)
        assertEquals("--MyVar", r.decls[0].property)
        assertEquals("color", r.decls[1].property)
    }

    @Test
    fun nestedRulesAreFlattened() {
        val sheet = CssParser.parse(
            """
            .card {
              color: white;
              &.active { color: red; }
              .label { color: blue; }
              > .icon, &::before { fill: green; }
              @media (min-width: 10px) { border-radius: 4px; }
            }
            """.trimIndent(),
        )
        val bySel = sheet.rules.associate { it.selectors.joinToString(",") to it.decls.first() }
        assertEquals(CssDecl("color", "white", false), sheet.rules.first().decls.single())
        assertEquals(listOf(".card"), sheet.rules.first().selectors)
        assertEquals("red", bySel[".card.active"]?.value)
        assertEquals("blue", bySel[".card .label"]?.value)
        assertEquals("green", bySel[".card > .icon,.card::before"]?.value)
        assertEquals(CssDecl("border-radius", "4px", false), bySel[".card"])
        assertEquals(3, sheet.nestedRules)
    }

    @Test
    fun atRulesAreCollected() {
        val sheet = CssParser.parse(
            """
            @import url("https://fonts.example/x.css");
            @font-face { font-family: "Mine"; src: url(fonts/mine.ttf); }
            @keyframes glow { 0% { border-color: #f00; } 100% { border-color: #0f0; } }
            @media screen { .x { color: #123456; } }
            """.trimIndent(),
        )
        assertEquals(1, sheet.imports.size)
        assertEquals("Mine", sheet.fontFaces.single().first { it.property == "font-family" }.value.trim('"'))
        assertEquals("glow", sheet.keyframes.single().name)
        assertTrue(sheet.keyframes.single().body.contains("#0f0"))
        assertEquals(".x", sheet.rules.single().selectors.single())
    }

    @Test
    fun brokenCssDoesNotThrow() {
        val sheet = CssParser.parse(".a { color: red; .b { color: blue; ")
        assertFalse(sheet.rules.isEmpty())
        assertNotNull(CssParser.parse("}}}{{{"))
        assertNull(CssParser.parse("").rules.firstOrNull())
    }
}
