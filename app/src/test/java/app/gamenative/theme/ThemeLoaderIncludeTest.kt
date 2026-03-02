package app.gamenative.theme

import app.gamenative.theme.io.ThemeLoader
import app.gamenative.theme.model.ThemeLoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThemeLoaderIncludeTest {

    @Test
    fun nestedIncludes_andVariablesPrecedence_workAndPreserveSource() {
        val root = Files.createTempDirectory("ThemeLoaderIncludeTest_").toFile().apply { deleteOnExit() }
        val themeDir = File(root, "Sample").apply { mkdirs() }
        // external variables via manifest entry
        File(themeDir, "variables.xml").writeText(
            """
            <variables>
              <var name="color" value="#0000FF"/>
              <var name="spacing" value="4"/>
            </variables>
            """.trimIndent()
        )
        // nested include chain: layouts -> include A -> include B (grid)
        val incB = File(themeDir, "sections${File.separator}b.xml").apply { parentFile.mkdirs() }
        incB.writeText(
            """
            <layouts>
              <grid id="g" columns="3" cellWidth="100" cellHeight="50" hSpacingPx="@{vars.spacing}" vSpacingPx="@{vars.spacing}" itemTemplate="card" selectionMode="moving"/>
            </layouts>
            """.trimIndent()
        )
        val incA = File(themeDir, "sections${File.separator}a.xml")
        incA.writeText(
            """
            <layouts>
              <include src="/sections/b.xml"/>
            </layouts>
            """.trimIndent()
        )
        // theme.xml with inline variables overriding external
        File(themeDir, "theme.xml").writeText(
            """
            <theme>
              <variables ref="/variables.xml">
                <var name="spacing" value="8"/>
              </variables>
              <templates>
                <template id="card" width="100" height="50">
                  <image id="img" x="0" y="0" width="100" height="50" src="@{game.capsule}"/>
                </template>
              </templates>
              <layouts>
                <include src="sections/a.xml"/>
              </layouts>
            </theme>
            """.trimIndent()
        )
        // manifest entry pointing to theme + variables
        File(themeDir, "manifest.xml").writeText(
            """
            <manifest>
              <entry theme="theme.xml" variables="variables.xml"/>
            </manifest>
            """.trimIndent()
        )

        val result = ThemeLoader().load(themeDir.absolutePath)
        when (result) {
            is ThemeLoadResult.Success -> {
                // precedence check: inline spacing=8 overrides external spacing=4
                assertEquals("8", result.tree.variables["spacing"])
                assertEquals("#0000FF", result.tree.variables["color"]) // inherited

                // ensure grid came from included B file (source path preserved)
                val layouts = result.tree.themeXml.children.first { it.name == "layouts" }
                val grid = layouts.children.first { it.name == "grid" }
                assertTrue(grid.source?.filePath?.endsWith("sections${File.separator}b.xml") == true)
            }
            is ThemeLoadResult.Failure -> assertTrue("Errors: ${result.errors}", false)
        }
    }

    @Test
    fun missingInclude_reportsFailure() {
        val root = Files.createTempDirectory("ThemeLoaderIncludeTest_").toFile().apply { deleteOnExit() }
        val themeDir = File(root, "Missing").apply { mkdirs() }
        File(themeDir, "theme.xml").writeText(
            """
            <theme>
              <layout>
                <include src="sections/absent.xml"/>
              </layout>
            </theme>
            """.trimIndent()
        )
        val res = ThemeLoader().load(themeDir.absolutePath)
        assertTrue(res is ThemeLoadResult.Failure)
        res as ThemeLoadResult.Failure
        assertTrue(res.errors.any { it.code.contains("INCLUDE") || it.message.contains("include", ignoreCase = true) })
    }
}
