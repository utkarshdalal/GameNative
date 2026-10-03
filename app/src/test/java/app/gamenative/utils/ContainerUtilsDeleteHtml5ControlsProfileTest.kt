package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.inputcontrols.InputControlsManager
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// without cleanup, every deleted html5 container leaves its per-container profile in the global
// InputControlsManager pool, cluttering the picker over time.
//
// when a sibling container references the SAME profileId (not yet lazily forked), the profile
// delete MUST be skipped -- the sibling still needs it, and its next launch would otherwise fork
// a fresh empty profile, losing user remaps.
//
// real disk + real InputControlsManager; ContainerManager is mocked out to skip Winlator I/O.
@RunWith(RobolectricTestRunner::class)
class ContainerUtilsDeleteHtml5ControlsProfileTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DownloadService.populateDownloadService(ApplicationProvider.getApplicationContext())
        // skip heavy ContainerManager / ImageFs I/O -- the orphan-scan branch is sufficient.
        mockkConstructor(ContainerManager::class)
        every { anyConstructed<ContainerManager>().hasContainer(any()) } returns false
        every { anyConstructed<ContainerManager>().containers } returns ArrayList<Container>()
    }

    @After
    fun tearDown() {
        File(DownloadService.baseExternalAppDirPath, "html5-containers").deleteRecursively()
        unmockkAll()
    }

    private fun seed(slug: String, appId: String, profileId: Long) {
        val root = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        root.mkdirs()
        val cfg = File(File(root, slug).apply { mkdirs() }, "config.json")
        WebViewContainer.save(
            slug,
            WebViewContainer(
                id = appId,
                installPath = "/tmp/$appId",
                engineProfile = "test",
                controlsProfileId = profileId,
            ),
            cfg,
        )
    }

    // loadProfiles() seeds maxProfileId + initializes the internal list -- required before
    // createProfile (which does ++maxProfileId on a null list otherwise).
    private fun mintProfile(name: String): Int {
        val mgr = InputControlsManager(context)
        mgr.getProfiles(false) // forces loadProfiles
        return mgr.createProfile(name).id
    }

    @Test
    fun deleting_html5_container_removes_its_orphan_profile() {
        val profileId = mintProfile("HTML5: alpha")
        seed("alpha-aaaa", "STEAM_111", profileId.toLong())

        assertNotNull(
            "profile must exist before delete",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
        )

        // cleanup reads the JSON BEFORE the delete: deleteContainer scans it, finds the appId,
        // resolves profileId, and removes the profile.
        ContainerUtils.deleteContainer(context, "STEAM_111")

        assertNull(
            "profile must be removed from the pool after html5 container delete",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
        )
    }

    @Test
    fun deleting_html5_container_keeps_profile_when_sibling_shares_id() {
        // two containers reference the same profileId: deleting A must NOT remove the profile --
        // B still owns it until ITS next launch (lazy fork).
        val sharedId = mintProfile("HTML5 Default")
        seed("alpha-aaaa", "STEAM_111", sharedId.toLong())
        seed("beta-bbbb", "STEAM_222", sharedId.toLong())

        ContainerUtils.deleteContainer(context, "STEAM_111")

        assertNotNull(
            "profile must SURVIVE delete when a sibling html5 container references same id",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == sharedId },
        )
    }

    @Test
    fun deleting_html5_container_with_unset_profileId_is_a_noop() {
        // html5 container on disk but never launched, so controlsProfileId == 0L. cleanup must not
        // throw or remove unrelated profiles.
        val unrelatedId = mintProfile("Unrelated profile")
        seed("alpha-aaaa", "STEAM_111", 0L)

        ContainerUtils.deleteContainer(context, "STEAM_111")

        assertNotNull(
            "unrelated profile must not be touched",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == unrelatedId },
        )
    }

    // ContainerStorageManager.removeContainer is the OTHER deletion path (Settings -> Storage ->
    // Remove + uninstallGameAndContainer); it must run the orphan cleanup too rather than just
    // deleting the dir.
    @Test
    fun container_storage_manager_remove_also_clears_orphan_profile() {
        val profileId = mintProfile("HTML5: storage-mgr")
        seed("alpha-aaaa", "STEAM_111", profileId.toLong())

        // a real container dir so removeContainer doesn't early-out, in the same
        // imagefs/home/<USER>-<id> layout production scans.
        val context = RuntimeEnvironment.getApplication()
        val homeDir = File(com.winlator.xenvironment.ImageFs.find(context).rootDir, "home")
        val containerDir = File(homeDir, "${com.winlator.xenvironment.ImageFs.USER}-STEAM_111").apply { mkdirs() }
        File(containerDir, ".container").writeText("{}")

        try {
            assertNotNull(
                "profile must exist before storage-mgr remove",
                InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
            )

            runBlocking { ContainerStorageManager.removeContainer(context, "STEAM_111") }

            assertNull(
                "profile must be removed from the pool after storage-mgr remove",
                InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
            )
        } finally {
            containerDir.deleteRecursively()
        }
    }

    // both uninstall paths (deleteContainer, ContainerStorageManager.removeContainer) run deleteHtml5OriginStorage.
    // a surviving marker would make a reinstall skip chromium LS/IDB sync forever.
    @Test
    fun html5_origin_storage_cleanup_clears_fs_authoritative_marker() {
        app.gamenative.html5.savesync.Html5FsAuthoritative.markUsed(context, "STEAM_111")
        org.junit.Assert.assertTrue(app.gamenative.html5.savesync.Html5FsAuthoritative.isFsAuthoritative(context, "STEAM_111"))

        ContainerUtils.deleteHtml5OriginStorage(context, "STEAM_111")

        org.junit.Assert.assertFalse(
            "fs-authoritative marker must be cleared on uninstall",
            app.gamenative.html5.savesync.Html5FsAuthoritative.isFsAuthoritative(context, "STEAM_111"),
        )
    }

    // chromium holds the shared LS leveldb open once any WebView ran, so uninstall must not write
    // it; the origin is queued for the next boot's purge instead.
    @Test
    fun html5_origin_storage_cleanup_leaves_shared_local_storage_files_untouched() {
        val lsDir = File(context.dataDir, "app_webview/Default/Local Storage/leveldb")
        lsDir.deleteRecursively()
        val origin = app.gamenative.html5.host.WebViewOrigin.originUrl("STEAM_111")
        app.gamenative.html5.savesync.FixtureBuilder.lsWithOrigins(lsDir, origin to mapOf("k" to "v".toByteArray()))
        fun snapshot() = lsDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(lsDir).path to it.readBytes().toList() }
        val before = snapshot()

        try {
            ContainerUtils.deleteHtml5OriginStorage(context, "STEAM_111")

            org.junit.Assert.assertEquals("uninstall must not write the shared LS leveldb", before, snapshot())
            org.junit.Assert.assertTrue(
                "appId must be queued for the boot purge",
                app.gamenative.html5.savesync.Html5PendingLsPurge.isPending(context, "STEAM_111"),
            )
        } finally {
            app.gamenative.html5.savesync.Html5PendingLsPurge.remove(context, "STEAM_111")
            lsDir.deleteRecursively()
        }
    }

    @Test
    fun deleting_wine_container_does_not_disturb_html5_profiles() {
        // a wine container has no html5-containers/<slug>/config.json entry, so the cleanup scan
        // must early-out and leave the html5 profile alone.
        val profileId = mintProfile("HTML5: wayward")
        seed("wayward-aaaa", "STEAM_111", profileId.toLong())

        // a DIFFERENT appId -- a wine-only container with no html5 JSON.
        ContainerUtils.deleteContainer(context, "STEAM_999")

        assertNotNull(
            "html5 profile for an unrelated container must be untouched",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
        )
    }
}
