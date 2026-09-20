package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileContainerLookupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun existingContainerIsReadWithoutChangingItsDrivesOrConfiguration() = withContainer { container ->
        container.drives = "A:/user-chosen-pathD:/other-path"
        container.putExtra("profileId", "42")
        assertTrue(container.saveDataChecked())
        val before = container.configFile.readBytes()

        val loaded = ContainerUtils.getOrCreateControlProfileContainer(context, container.id) {
            error("Existing containers must never enter creation or repair")
        }

        assertEquals(container.drives, loaded.drives)
        assertEquals("42", loaded.getExtra("profileId"))
        assertArrayEquals(before, container.configFile.readBytes())
    }

    @Test
    fun missingEmptyAndMalformedConfigsPreserveAllContainerFiles() {
        for (contents in listOf(null, "", "{broken", "{\"showFPS\":\"invalid\"}")) {
            withContainer { container ->
                if (contents != null) assertTrue(FileUtils.writeString(container.configFile, contents))
                val sentinel = container.rootDir.resolve("game-save.dat").apply { writeText("important save") }
                assertThrows(IOException::class.java) {
                    ContainerUtils.getOrCreateControlProfileContainer(context, container.id) {
                        error("An unreadable existing container must never be recreated")
                    }
                }
                assertEquals("important save", sentinel.readText())
                if (contents == null) assertFalse(container.configFile.exists())
                else assertEquals(contents, container.configFile.readText())
            }
        }
    }

    @Test
    fun nonDirectoryContainerEntryIsAlsoProtected() = withContainer { container ->
        assertTrue(container.rootDir.delete())
        container.rootDir.writeText("not a directory")
        assertThrows(IOException::class.java) {
            ContainerUtils.getOrCreateControlProfileContainer(context, container.id) {
                error("Existing entries must not be replaced")
            }
        }
        assertEquals("not a directory", container.rootDir.readText())
    }

    @Test
    fun genuinelyMissingContainerUsesInitialSetupExactlyOnce() = withContainer { container ->
        assertTrue(container.rootDir.delete())
        var creations = 0
        val loaded = ContainerUtils.getOrCreateControlProfileContainer(context, container.id) {
            creations++
            container
        }
        assertSame(container, loaded)
        assertEquals(1, creations)
        assertFalse(container.rootDir.exists())
    }

    @Test
    fun setupFailurePropagatesWithoutRetryingOrRemovingFiles() = withContainer { container ->
        assertTrue(container.rootDir.delete())
        var creations = 0
        assertThrows(IOException::class.java) {
            ContainerUtils.getOrCreateControlProfileContainer(context, container.id) {
                creations++
                // Simulate a container appearing after lookup but before setup finishes.
                assertTrue(container.rootDir.mkdir())
                container.rootDir.resolve("keep.dat").writeText("keep")
                throw IOException("setup failed")
            }
        }
        assertEquals(1, creations)
        assertEquals("keep", container.rootDir.resolve("keep.dat").readText())
    }

    private fun withContainer(block: (Container) -> Unit) {
        val homeDir = File(ImageFs.find(context).rootDir, "home").apply { mkdirs() }
        val root = Files.createTempDirectory(homeDir.toPath(), "${ImageFs.USER}-lookup-").toFile()
        val container = Container(root.name.removePrefix("${ImageFs.USER}-")).apply { rootDir = root }
        try {
            block(container)
        } finally {
            root.deleteRecursively()
        }
    }
}
