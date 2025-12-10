package app.gamenative.theme

import app.gamenative.theme.io.ThemeLoader
import app.gamenative.theme.model.ThemeLoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeLoaderSmokeTest {

    @Test
    fun load_basicTheme_withIncludesAndVariables_succeeds() {
        // Create a temp theme folder structure
        val base = createTempDir(prefix = "ThemeSmoke_")
        try {
            val themeDir = File(base, "MyTheme").apply { mkdirs() }

            // variables.xml
            val variablesXml = File(themeDir, "variables.xml")
            variablesXml.writeText(
                """
                <variables>
                  <var name="colorPrimary" value="#00FF00"/>
                </variables>
                """.trimIndent()
            )

            // split file to be included
            val split = File(themeDir, "sections\\grid.xml")
            split.parentFile.mkdirs()
            split.writeText(
                """
                <layouts>
                  <grid id="mainGrid" columns="3" rows="2"/>
                </layouts>
                """.trimIndent()
            )

            // theme.xml referencing include and variables block
            val themeXml = File(themeDir, "theme.xml")
            themeXml.writeText(
                """
                <theme>
                  <variables ref="/variables.xml">
                    <var name="colorPrimary" value="#FF0000"/>
                    <var name="spacing" value="8"/>
                  </variables>
                  <layouts>
                    <include src="sections/grid.xml"/>
                  </layouts>
                </theme>
                """.trimIndent()
            )

            // manifest.xml declaring entry
            val manifest = File(themeDir, "manifest.xml")
            manifest.writeText(
                """
                <manifest>
                  <entry theme="theme.xml" variables="variables.xml"/>
                </manifest>
                """.trimIndent()
            )

            val loader = ThemeLoader()
            val result = loader.load(themeDir.absolutePath)

            when (result) {
                is ThemeLoadResult.Success -> {
                    // Variables: external provides colorPrimary=#00FF00 but inline overrides to #FF0000
                    val vars = result.tree.variables
                    assertEquals("#FF0000", vars["colorPrimary"]) // last-writer wins
                    assertEquals("8", vars["spacing"]) // inline only

                    // Ensure include produced a grid node under layouts
                    val root = result.tree.themeXml
                    val layouts = root.children.firstOrNull { it.name == "layouts" }
                    assertTrue("layouts block should exist", layouts != null)
                    val grid = layouts!!.children.firstOrNull { it.name == "grid" }
                    assertTrue("grid from included file should be present", grid != null)
                    // Source location: the grid should originate from the split file
                    assertTrue(grid!!.source?.filePath?.endsWith("sections${File.separator}grid.xml") == true)
                }
                is ThemeLoadResult.Failure -> {
                    assertTrue("ThemeLoader failed: ${result.errors}", false)
                }
            }
        } finally {
            // Cleanup best-effort
            base.deleteRecursively()
        }
    }
}
