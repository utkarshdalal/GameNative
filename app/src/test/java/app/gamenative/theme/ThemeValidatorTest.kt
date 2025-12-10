package app.gamenative.theme

import app.gamenative.theme.model.SourceLoc
import app.gamenative.theme.model.ThemeTree
import app.gamenative.theme.model.XmlNode
import app.gamenative.theme.validate.Severity
import app.gamenative.theme.validate.ThemeValidator
import app.gamenative.theme.validate.ValidationCode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThemeValidatorTest {

    @Test
    fun engineVersionMismatch_blocksTheme() {
        val root = tempThemeDir().toFile()
        // manifest with engineVersion=2, app supports engineMajor=1
        writeManifest(root, engineVersion = 2, minApp = "1.0.0", maxApp = null)
        val themeXml = XmlNode("theme")
        val tree = ThemeTree(root.absolutePath, manifestEntry = null, themeXml = themeXml)

        val result = ThemeValidator.validate(tree, appVersion = "1.5.0", engineMajor = 1)
        assertTrue("Expected blocking error", result.hasBlocking())
        assertTrue(result.issues.any { it.code == ValidationCode.ENGINE_VERSION_MISMATCH && it.severity == Severity.ERROR })
    }

    @Test
    fun appVersionWindowFail_blocksTheme() {
        val root = tempThemeDir().toFile()
        // app 1.5.0 is below min 2.0.0
        writeManifest(root, engineVersion = 1, minApp = "2.0.0", maxApp = null)
        val tree = ThemeTree(root.absolutePath, manifestEntry = null, themeXml = XmlNode("theme"))

        val resMin = ThemeValidator.validate(tree, appVersion = "1.5.0", engineMajor = 1)
        assertTrue(resMin.hasBlocking())
        assertTrue(resMin.issues.any { it.code == ValidationCode.APP_VERSION_OUT_OF_RANGE })

        // app 3.0.0 exceeds max 2.5.0
        writeManifest(root, engineVersion = 1, minApp = "1.0.0", maxApp = "2.5.0")
        val resMax = ThemeValidator.validate(tree, appVersion = "3.0.0", engineMajor = 1)
        assertTrue(resMax.hasBlocking())
        assertTrue(resMax.issues.any { it.code == ValidationCode.APP_VERSION_OUT_OF_RANGE })
    }

    @Test
    fun badTemplateRef_detected() {
        val root = tempThemeDir().toFile()
        writeManifest(root, engineVersion = 1, minApp = "1.0.0", maxApp = null)
        // theme with grid referencing missing template id "card"
        val grid = XmlNode(
            name = "grid",
            attributes = mapOf(
                "columns" to "3",
                "cellWidth" to "100",
                "cellHeight" to "100",
                "itemTemplate" to "card"
            ),
            source = SourceLoc(File(root, "theme.xml").absolutePath, 10, 5)
        )
        val themeXml = XmlNode(name = "theme", children = listOf(grid))
        val tree = ThemeTree(root.absolutePath, manifestEntry = null, themeXml = themeXml)

        val result = ThemeValidator.validate(tree, appVersion = "1.5.0")
        val err = result.issues.find { it.code == ValidationCode.BAD_TEMPLATE_REF }
        assertNotNull("Expected BAD_TEMPLATE_REF", err)
        assertEquals(Severity.ERROR, err!!.severity)
    }

    @Test
    fun missingMediaAsset_warns() {
        val root = tempThemeDir().toFile()
        writeManifest(root, engineVersion = 1, minApp = "1.0.0", maxApp = null)
        // image with a relative src that doesn't exist
        val img = XmlNode(
            name = "image",
            attributes = mapOf("src" to "assets/missing.png"),
            source = SourceLoc(File(root, "theme.xml").absolutePath, 20, 3)
        )
        val tmpl = XmlNode(name = "template", attributes = mapOf("id" to "card"), children = listOf(img))
        val themeXml = XmlNode(name = "theme", children = listOf(tmpl))
        val tree = ThemeTree(root.absolutePath, manifestEntry = null, themeXml = themeXml)

        val result = ThemeValidator.validate(tree, appVersion = "1.0.0")
        val warn = result.issues.find { it.code == ValidationCode.ASSET_NOT_FOUND }
        assertNotNull("Expected ASSET_NOT_FOUND warning", warn)
        assertEquals(Severity.WARNING, warn!!.severity)
    }

    // Helpers
    private fun tempThemeDir() = Files.createTempDirectory("ThemeValidatorTest_").also { it.toFile().deleteOnExit() }

    private fun writeManifest(dir: File, engineVersion: Int, minApp: String, maxApp: String?) {
        val xml = buildString {
            append("""
                |<manifest
                |  id="Test"
                |  version="0.0.1"
                |  engineVersion="$engineVersion"
                |  minAppVersion="$minApp"${if (maxApp != null) "\n  maxAppVersion=\"$maxApp\"" else ""}/>
            """.trimMargin())
        }
        File(dir, "manifest.xml").writeText(xml)
    }
}
