package app.gamenative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import app.gamenative.utils.ContainerUtils

@RunWith(AndroidJUnit4::class)
class ContainerDeletionSmokeTest {

    @Test
    fun deleteOrphanedContainer_dirRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val homeDir = File(context.filesDir, "imagefs/home")
        if (!homeDir.exists()) {
            homeDir.mkdirs()
        }
        val prefix = "${com.winlator.xenvironment.ImageFs.USER}-"
        val appId = "GOG_999999"
        val orphanDir = File(homeDir, "${prefix}${appId}")
        if (orphanDir.exists()) orphanDir.deleteRecursively()
        orphanDir.mkdirs()
        File(orphanDir, "marker.txt").writeText("test")
        assertTrue("Orphan directory should exist before deletion", orphanDir.exists())

        // Call the deletion helper in the app
        ContainerUtils.deleteContainer(context, appId)

        // Wait briefly for synchronous deletion branch to complete
        Thread.sleep(1000)

        assertFalse("Orphan directory should be removed after deleteContainer", orphanDir.exists())
    }
}
