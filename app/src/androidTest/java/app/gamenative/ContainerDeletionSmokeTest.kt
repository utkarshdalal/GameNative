package app.gamenative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.container.ContainerManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import app.gamenative.utils.ContainerUtils

@RunWith(AndroidJUnit4::class)
class ContainerDeletionSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val homeDir get() = File(context.filesDir, "imagefs/home")
    private val prefix = "${com.winlator.xenvironment.ImageFs.USER}-"

    @After
    fun cleanup() {
        // Clean up any test artifacts
        val testIds = listOf("GOG_999999", "GOG_999998", "GOG_999997", "GOG_999996")
        for (appId in testIds) {
            val dir = File(homeDir, "${prefix}${appId}")
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    @Test
    fun deleteOrphanedContainer_dirRemoved() {
        if (!homeDir.exists()) homeDir.mkdirs()

        val appId = "GOG_999999"
        val orphanDir = File(homeDir, "${prefix}${appId}")
        if (orphanDir.exists()) orphanDir.deleteRecursively()
        orphanDir.mkdirs()
        File(orphanDir, "marker.txt").writeText("test")
        assertTrue("Orphan directory should exist before deletion", orphanDir.exists())

        ContainerUtils.deleteContainer(context, appId)

        assertFalse("Orphan directory should be removed after deleteContainer", orphanDir.exists())
    }

    @Test
    fun createContainerIntoOrphanedDir_succeedsAfterCleanup() {
        if (!homeDir.exists()) homeDir.mkdirs()

        val appId = "GOG_999998"
        val orphanDir = File(homeDir, "${prefix}${appId}")
        if (orphanDir.exists()) orphanDir.deleteRecursively()

        // Create an orphan directory (no valid config, so ContainerManager won't know about it)
        orphanDir.mkdirs()
        File(orphanDir, "stale_file.txt").writeText("orphan content")
        assertTrue("Orphan directory should exist", orphanDir.exists())

        // Verify ContainerManager doesn't know about it
        val manager = ContainerManager(context)
        assertFalse("ContainerManager should not know about orphan", manager.hasContainer(appId))

        // Now try to create a container — ContainerManager.createContainer should
        // detect the orphan, clean it up, and succeed
        val data = org.json.JSONObject()
        data.put("name", "container_$appId")
        val container = manager.createContainer(appId, data)

        assertNotNull("Container creation should succeed even with pre-existing orphan dir", container)
        assertFalse("Stale orphan file should be gone", File(orphanDir, "stale_file.txt").exists())
        assertTrue("Container directory should exist", orphanDir.exists())
        assertTrue("ContainerManager should now know about the container", manager.hasContainer(appId))

        // Clean up the created container
        manager.removeContainerAsync(manager.getContainerById(appId)) {}
    }

    @Test
    fun cleanOrphanedContainers_removesAllOrphans() {
        if (!homeDir.exists()) homeDir.mkdirs()

        // Create multiple orphan directories
        val orphanIds = listOf("GOG_999997", "GOG_999996")
        for (id in orphanIds) {
            val dir = File(homeDir, "${prefix}${id}")
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            File(dir, "orphan.txt").writeText("orphan")
        }

        val deleted = ContainerUtils.cleanOrphanedContainers(context)

        // Both should be cleaned up (they may not be in the list if other orphans exist,
        // but the directories should be gone)
        for (id in orphanIds) {
            val dir = File(homeDir, "${prefix}${id}")
            assertFalse("Orphan dir for $id should be removed", dir.exists())
        }
        assertTrue("At least the test orphans should be reported as deleted",
            orphanIds.all { it in deleted })

        // Clean up any that might not have been deleted (shouldn't happen but safety)
        for (id in orphanIds) {
            File(homeDir, "${prefix}${id}").deleteRecursively()
        }
    }
}
