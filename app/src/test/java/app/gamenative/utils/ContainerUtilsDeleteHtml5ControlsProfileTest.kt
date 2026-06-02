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

// regression for orphan ControlsProfile cleanup on html5 container delete.
// without the cleanup, every deleted html5 container leaves its per-container profile in the
// global InputControlsManager pool, accumulating over time and cluttering the picker UI.

// safety: when a sibling container references the SAME profileId (lazy-fork window from fix-B),
// we MUST skip the profile delete — the surviving sibling still needs it. otherwise its next
// launch hits a missing-profile state and forks a fresh empty profile, losing user remaps.

// uses real disk + real InputControlsManager (Robolectric Application context) — same shape as
// Html5DefaultControlsProfileFactoryTest. ContainerManager mocked out to skip Winlator I/O.
@RunWith(RobolectricTestRunner::class)
class ContainerUtilsDeleteHtml5ControlsProfileTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DownloadService.populateDownloadService(ApplicationProvider.getApplicationContext())
        // skip heavy ContainerManager / ImageFs I/O — orphan-scan branch is sufficient.
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

    // create a ControlsProfile via the manager so it's a real on-disk profile addressable by id.
    // loadProfiles() seeds maxProfileId + initializes the internal list — required before
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

        // sanity — profile exists in the pool before delete
        assertNotNull(
            "profile must exist before delete",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
        )

        // delete html5 container then ALSO simulate the JSON dir being gone (production
        // deletion would do this; we wipe before the cleanup runs to avoid coupling on
        // ContainerStorageManager). but our cleanup reads BEFORE delete via deleteContainer
        // call — so order: deleteContainer hits cleanup which scans the JSON, finds appId,
        // resolves profileId, removes profile.
        ContainerUtils.deleteContainer(context, "STEAM_111")

        assertNull(
            "profile must be removed from the pool after html5 container delete",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
        )
    }

    @Test
    fun deleting_html5_container_keeps_profile_when_sibling_shares_id() {
        // pre-fix-B legacy state: two containers reference the same profileId. deleting A
        // must NOT remove the profile — B still owns it until ITS next launch (lazy fork).
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
        // bootstrap-never-ran case: html5 container exists on disk but never launched, so
        // controlsProfileId == 0L. cleanup must not throw or remove unrelated profiles.
        val unrelatedId = mintProfile("Unrelated profile")
        seed("alpha-aaaa", "STEAM_111", 0L)

        ContainerUtils.deleteContainer(context, "STEAM_111")

        assertNotNull(
            "unrelated profile must not be touched",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == unrelatedId },
        )
    }

    // ContainerStorageManager.removeContainer is the OTHER deletion
    // path (Settings → Storage → Remove + uninstallGameAndContainer). pre-fix it deleted the
    // dir directly via FileUtils.delete, bypassing the orphan cleanup. regression: ensure
    // that path now also removes the orphan profile.
    @Test
    fun container_storage_manager_remove_also_clears_orphan_profile() {
        val profileId = mintProfile("HTML5: storage-mgr")
        seed("alpha-aaaa", "STEAM_111", profileId.toLong())

        // create a real container dir so removeContainer doesn't early-out on missing dir.
        // uses the same imagefs/home/<USER>-<id> layout that production scans.
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

    @Test
    fun deleting_wine_container_does_not_disturb_html5_profiles() {
        // wine container has no html5-containers/<slug>/config.json entry. cleanup scan
        // returns null for foundSelf, so it must early-out and leave the html5 profile alone.
        val profileId = mintProfile("HTML5: wayward")
        seed("wayward-aaaa", "STEAM_111", profileId.toLong())

        // delete a DIFFERENT appId — represents a wine-only container with no html5 JSON.
        ContainerUtils.deleteContainer(context, "STEAM_999")

        assertNotNull(
            "html5 profile for an unrelated container must be untouched",
            InputControlsManager(context).getProfiles(false).firstOrNull { it.id == profileId },
        )
    }
}
