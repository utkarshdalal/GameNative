package com.winlator.contents

import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AdrenotoolsManagerPackageTypeTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var manager: AdrenotoolsManager
    private lateinit var packageDir: File
    private lateinit var imageFsRoot: File

    @Before
    fun setUp() {
        manager = AdrenotoolsManager(context)
        packageDir = File(context.filesDir, "contents/adrenotools/exynos-layer-test")
        packageDir.deleteRecursively()
        packageDir.mkdirs()
        imageFsRoot = File(context.filesDir, "imagefs-package-type-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        packageDir.deleteRecursively()
        imageFsRoot.deleteRecursively()
    }

    @Test
    fun packageTypeDefaultsToIcdForLegacyMetadata() {
        writeMetadata(
            """
            {
              "name": "Legacy ICD",
              "libraryName": "libvulkan_legacy.so",
              "driverVersion": "1"
            }
            """.trimIndent(),
        )

        assertEquals("icd", manager.getPackageType("exynos-layer-test"))
    }

    @Test
    fun vulkanLayerPackageKeepsSystemIcdAndConfiguresLayerPath() {
        writeMetadata(
            """
            {
              "name": "ExynosTools Test Layer",
              "libraryName": "libVkLayer_exynostools.so",
              "driverVersion": "1",
              "packageType": "vulkanLayer",
              "manifestName": "VkLayer_exynostools.json"
            }
            """.trimIndent(),
        )
        File(packageDir, "libVkLayer_exynostools.so").writeBytes(byteArrayOf(1))
        File(packageDir, "VkLayer_exynostools.json").writeText("{}")

        val env = EnvVars().apply {
            put("ADRENOTOOLS_DRIVER_PATH", "/custom/icd")
            put("ADRENOTOOLS_DRIVER_NAME", "libvulkan_custom.so")
            put("ADRENOTOOLS_HOOKS_PATH", "/hooks")
            put("DISABLE_VORTEK_XCLIPSE_LAYER", "1")
        }
        val imageFs = ImageFs.find(imageFsRoot)

        manager.setDriverById(env, imageFs, "exynos-layer-test")

        assertEquals("vulkanLayer", manager.getPackageType("exynos-layer-test"))
        assertFalse(env.has("ADRENOTOOLS_DRIVER_PATH"))
        assertFalse(env.has("ADRENOTOOLS_DRIVER_NAME"))
        assertFalse(env.has("ADRENOTOOLS_HOOKS_PATH"))
        assertFalse(env.has("DISABLE_VORTEK_XCLIPSE_LAYER"))
        assertEquals(packageDir.absolutePath + "/", env["EXYNOSTOOLS_LAYER_PATH"])
        assertTrue(env["VK_LAYER_PATH"].startsWith(packageDir.absolutePath + "/:"))
        assertTrue(env["VK_LAYER_PATH"].contains(imageFsRoot.absolutePath + "/usr/share/vulkan/implicit_layer.d"))
        assertTrue(env["VK_LAYER_PATH"].contains(imageFsRoot.absolutePath + "/usr/share/vulkan/explicit_layer.d"))
    }

    private fun writeMetadata(text: String) {
        File(packageDir, "meta.json").writeText(text)
    }
}
