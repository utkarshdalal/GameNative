package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RegistryKeyFixTest {
    private lateinit var context: Context
    private lateinit var container: Container
    private lateinit var systemRegFile: File
    private var hadOriginalSystemReg: Boolean = false
    private var originalSystemRegContent: String = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = mockk(relaxed = true)
        val imageFs = ImageFs.find(context)
        val winePrefixDir = File(imageFs.wineprefix)
        winePrefixDir.mkdirs()
        systemRegFile = File(winePrefixDir, "system.reg")
        hadOriginalSystemReg = systemRegFile.exists()
        if (hadOriginalSystemReg) {
            originalSystemRegContent = systemRegFile.readText()
        }
    }

    @After
    fun tearDown() {
        if (hadOriginalSystemReg) {
            systemRegFile.parentFile?.mkdirs()
            systemRegFile.writeText(originalSystemRegContent)
        } else if (systemRegFile.exists()) {
            systemRegFile.delete()
        }
    }

    @Test
    fun apply_returnsFalse_whenSystemRegDoesNotExist() {
        if (systemRegFile.exists()) {
            systemRegFile.delete()
        }

        val fix = RegistryKeyFix(
            registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion",
            defaultValues = mapOf("Installed Path" to INSTALL_PATH_PLACEHOLDER),
        )

        val result = fix.apply(context, "22330", "/tmp/game", "A:\\", container)

        assertFalse(result)
    }

    @Test
    fun apply_writesMissingValues_andResolvesInstallPathPlaceholder() {
        if (!systemRegFile.exists()) {
            systemRegFile.writeText("REGEDIT4\n")
        }

        val key = "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion"
        val fix = RegistryKeyFix(
            registryKey = key,
            defaultValues = mapOf(
                "Installed Path" to INSTALL_PATH_PLACEHOLDER,
                "Language" to "EN",
            ),
        )

        val result = fix.apply(context, "22330", "/tmp/game", "A:\\Games\\Oblivion", container)

        assertTrue(result)
        WineRegistryEditor(systemRegFile).use { editor ->
            assertEquals("A:\\Games\\Oblivion", editor.getStringValue(key, "Installed Path", null))
            assertEquals("EN", editor.getStringValue(key, "Language", null))
        }
    }

    @Test
    fun apply_keepsExistingRegistryValue_andDoesNotOverwrite() {
        if (!systemRegFile.exists()) {
            systemRegFile.writeText("REGEDIT4\n")
        }

        val key = "Software\\Wow6432Node\\Bethesda Softworks\\Fallout3"
        WineRegistryEditor(systemRegFile).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            editor.setStringValue(key, "Installed Path", "A:\\Existing\\Path")
        }

        val fix = RegistryKeyFix(
            registryKey = key,
            defaultValues = mapOf("Installed Path" to INSTALL_PATH_PLACEHOLDER),
        )

        val result = fix.apply(context, "22300", "/tmp/game", "A:\\New\\Path", container)

        assertTrue(result)
        WineRegistryEditor(systemRegFile).use { editor ->
            assertEquals("A:\\Existing\\Path", editor.getStringValue(key, "Installed Path", null))
        }
    }
}
