package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManifestIdCorrelationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestIdsMatchInstalledIds() = runBlocking {
        val manifest = loadLocalManifest()
        val items = manifest.items.filterKeys { it != ManifestContentTypes.PROTON }

        for ((typeKey, entries) in items) {
            println("Checking manifest type=$typeKey entries=${entries.size}")
            for (entry in entries) {
                if (typeKey == ManifestContentTypes.DRIVER) {
                    verifyDriverEntry(entry)
                } else {
                    verifyContentEntry(typeKey, entry)
                }
            }
        }
    }

    private fun loadLocalManifest(): ManifestData {
        val manifestFile = locateManifestFile()
        val json = manifestFile.readText()
        val parsed = ManifestRepository.parseManifest(json)
        assertNotNull("Failed to parse manifest.json", parsed)
        return parsed!!
    }

    private fun locateManifestFile(): File {
        val workingDir = File(System.getProperty("user.dir"))
        val direct = File(workingDir, "manifest.json")
        if (direct.exists()) return direct
        val parent = workingDir.parentFile
        if (parent != null) {
            val parentFile = File(parent, "manifest.json")
            if (parentFile.exists()) return parentFile
        }
        throw IllegalStateException("manifest.json not found from ${workingDir.absolutePath}")
    }

    private suspend fun verifyDriverEntry(entry: ManifestEntry) {
        val manager = AdrenotoolsManager(context)
        var installedId: String? = null
        try {
            println("Driver download/install id=${entry.id} name=${entry.name} url=${entry.url}")
            val result = ManifestInstaller.downloadAndInstallDriver(context, entry)
            println("Driver install result id=${entry.id} success=${result.success} message=${result.message}")
            assertTrue(
                "Driver install failed for ${entry.id}: ${result.message}",
                result.success,
            )
            val installed = manager.enumarateInstalledDrivers()
            println("Driver installed IDs: $installed")
            installedId = installed.firstOrNull { it.equals(entry.id, ignoreCase = true) }
            println("Driver match id=${entry.id} matched=${installedId != null}")
            assertTrue(
                "Driver ID mismatch for ${entry.id}. Installed: $installed",
                installedId != null,
            )
        } finally {
            if (installedId != null) {
                println("Driver cleanup id=$installedId")
                manager.removeDriver(installedId)
            }
        }
    }

    private suspend fun verifyContentEntry(typeKey: String, entry: ManifestEntry) {
        val expectedType = contentTypeForKey(typeKey)
        val manager = ContentsManager(context)
        var installedProfile: ContentProfile? = null
        try {
            println("Content download/install type=$typeKey id=${entry.id} name=${entry.name} url=${entry.url}")
            val result = ManifestInstaller.downloadAndInstallContent(context, entry, expectedType)
            println("Content install result type=$typeKey id=${entry.id} success=${result.success} message=${result.message}")
            assertTrue(
                "Content install failed for ${entry.id} (${typeKey}): ${result.message}",
                result.success,
            )
            manager.syncContents()
            val profiles = manager.getProfiles(expectedType).orEmpty()
            println("Content installed profiles type=$typeKey count=${profiles.size}")
            installedProfile = profiles.firstOrNull { profile ->
                val installedId = "${profile.verName}-${profile.verCode}"
                installedId.equals(entry.id, ignoreCase = true)
            }
            val installedIds = profiles.map { "${it.verName}-${it.verCode}" }
            println("Content installed IDs type=$typeKey ids=$installedIds")
            println("Content match type=$typeKey id=${entry.id} matched=${installedProfile != null}")
            assertTrue(
                "Content ID mismatch for ${entry.id} (${typeKey}). Installed: $installedIds",
                installedProfile != null,
            )
        } finally {
            if (installedProfile != null) {
                println("Content cleanup type=$typeKey verName=${installedProfile.verName} verCode=${installedProfile.verCode}")
                manager.removeContent(installedProfile)
            }
        }
    }

    private fun contentTypeForKey(key: String): ContentProfile.ContentType {
        return when (key.lowercase()) {
            ManifestContentTypes.DXVK -> ContentProfile.ContentType.CONTENT_TYPE_DXVK
            ManifestContentTypes.VKD3D -> ContentProfile.ContentType.CONTENT_TYPE_VKD3D
            ManifestContentTypes.BOX64 -> ContentProfile.ContentType.CONTENT_TYPE_BOX64
            ManifestContentTypes.WOWBOX64 -> ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
            ManifestContentTypes.FEXCORE -> ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
            ManifestContentTypes.WINE -> ContentProfile.ContentType.CONTENT_TYPE_WINE
            else -> throw IllegalArgumentException("Unsupported manifest type: $key")
        }
    }
}
