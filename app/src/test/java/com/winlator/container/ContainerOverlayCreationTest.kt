package com.winlator.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerOverlayCreationTest {
    private val proton = "proton-9.0-arm64ec"
    private lateinit var context: Context
    private lateinit var imageFsRoot: File
    private lateinit var nativeLibDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        imageFsRoot = ImageFs.find(context).rootDir
        nativeLibDir = File(context.cacheDir, "native-libs").apply { mkdirs() }
        File(nativeLibDir, ContainerOverlay.BIONIC_LIB).writeText("lib")
        context.applicationInfo.nativeLibraryDir = nativeLibDir.absolutePath
    }

    @After
    fun tearDown() {
        File(imageFsRoot, "opt/$proton").deleteRecursively()
        File(imageFsRoot, "home").deleteRecursively()
        nativeLibDir.deleteRecursively()
    }

    private fun write(root: File, rel: String, content: String) {
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private fun completeBase(): File {
        val baseDir = File(imageFsRoot, "opt/$proton/${BasePrefix.DIR_NAME}")
        val baseWine = File(baseDir, ".wine")
        write(baseWine, "system.reg", "base-system")
        write(baseWine, "user.reg", "base-user")
        write(baseWine, ".update-timestamp", "disable")
        write(baseWine, "drive_c/users/xuser/Documents/readme.txt", "doc")
        write(baseWine, "drive_c/windows/system32/drivers/mountmgr.sys", "MZ base driver")
        File(baseWine, "dosdevices").mkdirs()
        write(baseDir, BasePrefix.COMPLETE_MARKER, "3:$proton:${ImageFs.find(context).version}")
        return baseWine
    }

    private fun bionicProtonData() = JSONObject()
        .put("name", "container_t")
        .put("containerVariant", Container.BIONIC)
        .put("wineVersion", proton)

    @Test
    fun createContainer_isThinWhenTheBaseExists() {
        val baseWine = completeBase()

        val container = ContainerManager(context).createContainer("THIN_1", bionicProtonData())

        assertNotNull(container)
        val upper = File(container!!.rootDir, ".wine")
        assertTrue(container.isOverlay)
        assertEquals(ContainerOverlay.canonicalHostPath(baseWine), container.basePrefix)
        assertEquals("base-system", File(upper, "system.reg").readText())
        assertEquals("base-user", File(upper, "user.reg").readText())
        assertEquals("disable", File(upper, ".update-timestamp").readText())
        assertTrue(File(upper, "drive_c/users/xuser/Documents/readme.txt").isFile)
        assertFalse(File(upper, "drive_c/windows/system32").exists())
        assertTrue(File(upper, ".gnoverlay/opaque/dosdevices").isFile)
    }

    @Test
    fun createContainer_disablesThePrefixUpdateWhenTheBaseHasNoStamp() {
        val baseWine = completeBase()
        assertTrue(File(baseWine, ".update-timestamp").delete())

        val container = ContainerManager(context).createContainer("THIN_2", bionicProtonData())

        assertNotNull(container)
        assertTrue(container!!.isOverlay)
        assertEquals("disable", File(container.rootDir, ".wine/.update-timestamp").readText())
    }

    private fun assertPendingPrefix(container: Container?) {
        assertNotNull(container)
        val upper = File(container!!.rootDir, ".wine")
        assertFalse(container.isOverlay)
        assertEquals("", container.basePrefix)
        assertEquals(listOf(".update-timestamp"), upper.list()!!.toList())
        assertEquals("disable", File(upper, ".update-timestamp").readText())
    }

    @Test
    fun createContainer_writesNoWineFilesWhenTheProtonHasNoBase() {
        assertPendingPrefix(ContainerManager(context).createContainer("PENDING_1", bionicProtonData()))
    }

    @Test
    fun createContainer_writesNoWineFilesWithoutTheOverlayLibrary() {
        completeBase()
        File(nativeLibDir, ContainerOverlay.BIONIC_LIB).delete()

        assertPendingPrefix(ContainerManager(context).createContainer("PENDING_2", bionicProtonData()))
    }

    @Test
    fun firstLaunchMigration_makesAPendingPrefixThin_keepingPreLaunchFiles() {
        val baseWine = completeBase()
        write(baseWine, "drive_c/windows/system32/kernel32.dll", "proton kernel32")
        val container = ContainerManager(context).createContainer("PENDING_3", bionicProtonData())!!
        val upper = File(container.rootDir, ".wine")
        // What the first launch extracts from the Proton, plus a file written before it.
        write(upper, "system.reg", "proton-system")
        write(upper, "drive_c/windows/system32/kernel32.dll", "proton kernel32")
        write(upper, "drive_c/Program Files (x86)/Steam/steamclient.dll", "client")

        val result = ContainerOverlayMigrator.migrate(container, baseWine, emptyList())

        assertTrue(result.completed)
        assertTrue(container.isOverlay)
        assertEquals(ContainerOverlay.canonicalHostPath(baseWine), container.basePrefix)
        assertFalse(File(upper, "drive_c/windows/system32/kernel32.dll").exists())
        assertEquals("client", File(upper, "drive_c/Program Files (x86)/Steam/steamclient.dll").readText())
        assertEquals("proton-system", File(upper, "system.reg").readText())
        assertEquals("disable", File(upper, ".update-timestamp").readText())
    }
}
