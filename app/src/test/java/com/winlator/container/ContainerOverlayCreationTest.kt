package com.winlator.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.WineInfo
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
    fun creationPatternVersion_fallsBackToTheMainPatternOnlyForOverlayContainers() {
        val bionicProton = Container("a").apply {
            containerVariant = Container.BIONIC
            wineVersion = proton
        }
        val glibcProton = Container("b").apply {
            containerVariant = Container.GLIBC
            wineVersion = "proton-9.0-x86_64"
        }
        val bionicMain = Container("c").apply { containerVariant = Container.BIONIC }

        assertEquals(WineInfo.MAIN_WINE_VERSION.identifier(), ContainerManager.creationPatternVersion(bionicProton))
        assertEquals("proton-9.0-x86_64", ContainerManager.creationPatternVersion(glibcProton))
        assertEquals(WineInfo.MAIN_WINE_VERSION.identifier(), ContainerManager.creationPatternVersion(bionicMain))
    }
}
