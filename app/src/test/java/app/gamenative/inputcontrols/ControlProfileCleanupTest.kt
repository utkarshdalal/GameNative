package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileCleanupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun explicitCleanupRequiresDirectoryRemovalAndPreservesOtherProfiles() {
        withWorkingProfile { container, profile, manager ->
            withWorkingProfile { _, otherProfile, _ ->
                val libraryProfile = manager.createProfile("Cleanup library fixture").apply {
                    gameOwnerId = container.id
                    assertTrue(save())
                }
                val libraryFile = ControlsProfile.getProfileFile(context, libraryProfile.id)
                val profileFile = ControlsProfile.getProfileFile(context, profile.id)
                try {
                    // A failed deletion callback must preserve even a container with no config.
                    org.junit.Assert.assertEquals(0, ControlProfileService.deleteWorkingProfilesForContainer(context, container.id))
                    assertTrue(profileFile.isFile)

                    assertTrue(container.rootDir.delete())
                    org.junit.Assert.assertEquals(1, ControlProfileService.deleteWorkingProfilesForContainer(context, container.id))
                    assertFalse(profileFile.exists())
                    assertTrue(libraryFile.isFile)
                    assertTrue(ControlsProfile.getProfileFile(context, otherProfile.id).isFile)
                    org.junit.Assert.assertEquals(0, ControlProfileService.deleteWorkingProfilesForContainer(context, container.id))
                } finally {
                    libraryFile.delete()
                }
            }
        }
    }

    private fun withWorkingProfile(block: (Container, ControlsProfile, InputControlsManager) -> Unit) {
        val homeDir = File(ImageFs.find(context).rootDir, "home").apply { mkdirs() }
        val root = Files.createTempDirectory(homeDir.toPath(), "${ImageFs.USER}-cleanup-").toFile()
        val container = Container(root.name.removePrefix("${ImageFs.USER}-")).apply { rootDir = root }
        val manager = InputControlsManager(context)
        val profile = manager.createProfile(container.id).apply {
            isListed = false
            gameOwnerId = container.id
            assertTrue(save())
        }
        try {
            block(container, profile, manager)
        } finally {
            ControlsProfile.getProfileFile(context, profile.id).delete()
            root.deleteRecursively()
        }
    }
}
