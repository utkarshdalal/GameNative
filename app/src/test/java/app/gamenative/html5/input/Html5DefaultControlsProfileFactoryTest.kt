package app.gamenative.html5.input

import androidx.test.core.app.ApplicationProvider
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.DownloadService
import com.winlator.inputcontrols.InputControlsManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// robolectric required -- InputControlsManager(ctx) reads context.getFilesDir() in
// getProfilesDir; ControlsProfile.save() writes JSON to that dir.
//
// each html5 container gets its OWN profile in the global pool (Wine parity).
@RunWith(RobolectricTestRunner::class)
class Html5DefaultControlsProfileFactoryTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // forkOnCollision scans sibling containers under DownloadService.baseExternalAppDirPath.
        DownloadService.populateDownloadService(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        File(DownloadService.baseExternalAppDirPath, "html5-containers").deleteRecursively()
    }

    // controlsProfileId=0L exercises the bootstrap path. installPath/engineProfile are required
    // ctor args (no schema-default in WebViewContainer).
    private fun container(id: String, controlsProfileId: Long = 0L): WebViewContainer =
        WebViewContainer(
            id = id,
            installPath = "/tmp/$id",
            engineProfile = "test",
            controlsProfileId = controlsProfileId,
        )

    // seeds two WebViewContainer.json files with the SAME controlsProfileId -- the state left
    // behind when profiles were looked up globally by name.
    private fun seedSharedProfileIdContainers(
        slugA: String, idA: String,
        slugB: String, idB: String,
        sharedProfileId: Long,
    ) {
        val root = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        root.mkdirs()
        val cfgA = File(File(root, slugA).apply { mkdirs() }, "config.json")
        val cfgB = File(File(root, slugB).apply { mkdirs() }, "config.json")
        WebViewContainer.save(
            slugA,
            WebViewContainer(
                id = idA,
                installPath = "/tmp/$idA",
                engineProfile = "test",
                controlsProfileId = sharedProfileId,
            ),
            cfgA,
        )
        WebViewContainer.save(
            slugB,
            WebViewContainer(
                id = idB,
                installPath = "/tmp/$idB",
                engineProfile = "test",
                controlsProfileId = sharedProfileId,
            ),
            cfgB,
        )
    }

    @Test fun first_call_creates_real_id_profile() {
        val profile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        assertNotNull(profile)
        assertNotEquals(-1, profile.id)
        assertTrue("profile.id must be ≥ 0, got ${profile.id}", profile.id >= 0)
    }

    @Test fun bootstrap_then_resolve_by_id_returns_same_profile() {
        val first = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // later launch: container has the persisted id, factory resolves it
        val second = Html5DefaultControlsProfileFactory.getOrCreate(
            ctx,
            container("alpha", controlsProfileId = first.id.toLong()),
        )
        assertEquals(first.id, second.id)
        assertEquals(first.name, second.name)
    }

    @Test fun created_profile_has_gamepad_keycode_bindings_populated() {
        val profile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // re-read from disk to verify save() persisted bindings
        profile.loadControllers()
        val controllers = profile.controllers
        assertTrue("expected at least 1 controller, got ${controllers.size}", controllers.size >= 1)
        val bindings = controllers[0].controllerBindings
        // createHtml5DefaultProfile has 16 keycode + 8 axis = 24; accept >=12 keycode bindings to
        // allow ABI variance
        assertTrue("expected ≥12 bindings, got ${bindings.size}", bindings.size >= 12)
    }

    @Test fun new_inputControlsManager_instance_sees_persisted_profile() {
        val first = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // fresh app launch -- new manager scans disk
        val freshManager = InputControlsManager(ctx)
        val rediscovered = freshManager.getProfiles(false).firstOrNull { it.id == first.id }
        assertNotNull("profile must persist across InputControlsManager instances", rediscovered)
        assertEquals(first.id, rediscovered!!.id)
    }

    // container A's remap MUST NOT leak into container B (per-container profiles were once shared
    // by name, so remapping one game changed another).
    @Test fun container_a_remap_does_not_leak_into_container_b() {
        val containerA = container("wayward")
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerA)
        val idA = profileA.id

        // clear+re-add bindings on the wildcard "*" controller, then save -- mirrors
        // PhysicalControllerConfigSection.onSave.
        profileA.loadControllers()
        val wildcardA = profileA.getController("*")
        assertNotNull("wildcard controller must exist after factory bootstrap", wildcardA)
        val existing = wildcardA!!.controllerBindings.toList()
        for (b in existing) wildcardA.removeControllerBinding(b)
        val remap = com.winlator.inputcontrols.ExternalControllerBinding()
        remap.setKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP)
        remap.setBinding(com.winlator.inputcontrols.Binding.KEY_W)
        wildcardA.addControllerBinding(remap)
        profileA.save()

        // container B MUST mint a separate profile, NOT reuse container A's.
        val containerB = container("look-outside")
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerB)
        assertNotEquals(
            "container B must get a DIFFERENT profile id than container A (per-container, Wine parity)",
            idA,
            profileB.id,
        )

        // B's wildcard controller should have DEFAULT bindings -- A's KEY_W remap must NOT be
        // visible.
        profileB.loadControllers()
        val wildcardB = profileB.getController("*")
        assertNotNull("container B has its own wildcard controller", wildcardB)
        val dpadUpInB = wildcardB!!.controllerBindings.firstOrNull {
            it.keyCodeForAxis == android.view.KeyEvent.KEYCODE_DPAD_UP
        }?.binding
        assertEquals(
            "container B's DPAD_UP must be the DEFAULT GAMEPAD_DPAD_UP, NOT container A's KEY_W remap",
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_UP,
            dpadUpInB,
        )
        assertNotEquals(
            "container B must NOT see container A's KEY_W remap",
            com.winlator.inputcontrols.Binding.KEY_W,
            dpadUpInB,
        )
    }

    // positive companion to the leak test: A's remap MUST survive a relaunch of A with B opened
    // in between.
    @Test fun container_a_remap_survives_relaunch_of_container_a() {
        val containerAv1 = container("wayward")
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerAv1)
        val idA = profileA.id
        profileA.loadControllers()
        val wildcardA = profileA.getController("*")!!
        val existing = wildcardA.controllerBindings.toList()
        for (b in existing) wildcardA.removeControllerBinding(b)
        val remap = com.winlator.inputcontrols.ExternalControllerBinding()
        remap.setKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP)
        remap.setBinding(com.winlator.inputcontrols.Binding.KEY_W)
        wildcardA.addControllerBinding(remap)
        profileA.save()

        Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("look-outside"))

        // relaunch A -- container has the persisted id; factory resolves the SAME profile.
        val containerAv2 = container("wayward", controlsProfileId = idA.toLong())
        val profileAv2 = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerAv2)
        assertEquals("relaunch must resolve to same profile id", idA, profileAv2.id)
        profileAv2.loadControllers()
        val wildcardAv2 = profileAv2.getController("*")!!
        val dpadUpAv2 = wildcardAv2.controllerBindings.firstOrNull {
            it.keyCodeForAxis == android.view.KeyEvent.KEYCODE_DPAD_UP
        }?.binding
        assertEquals(
            "container A's KEY_W remap must survive its own relaunch",
            com.winlator.inputcontrols.Binding.KEY_W,
            dpadUpAv2,
        )
    }

    // the first per-container profile keeps the canonical "HTML5 Default" name (older data uses
    // it); later containers get distinct names so the picker can tell them apart.
    @Test fun second_container_gets_distinct_profile_name() {
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("beta"))
        assertNotEquals("profile names must differ across containers", profileA.name, profileB.name)
        val names = setOf(profileA.name, profileB.name)
        assertTrue(
            "first per-container profile should keep canonical name",
            Html5DefaultControlsProfileFactory.HTML5_DEFAULT_PROFILE_NAME in names,
        )
    }

    // bootstrap with controlsProfileId=0L and a stale same-named profile in the pool MUST NOT
    // reuse it -- guards against re-introducing name-based dedupe.
    @Test fun stale_named_profile_in_pool_is_not_reused_for_new_container() {
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        assertEquals(
            Html5DefaultControlsProfileFactory.HTML5_DEFAULT_PROFILE_NAME,
            profileA.name,
        )

        // no persisted id -- must NOT find profileA by name.
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("beta"))
        assertNotEquals(profileA.id, profileB.id)
    }

    // two containers referencing the SAME profileId (left over from name-based sharing): on A's
    // next launch the factory forks the profile and persists a new id to A's
    // WebViewContainer.json. B keeps the original until ITS next launch (lazy migration).
    @Test fun shared_profileId_migrates_lazily_on_first_launch() {
        // the colliding id must exist in the manager pool.
        val seedProfile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("seed"))
        val sharedId = seedProfile.id.toLong()

        seedSharedProfileIdContainers(
            slugA = "wayward-aaaa", idA = "STEAM_111",
            slugB = "look-outside-bbbb", idB = "STEAM_222",
            sharedProfileId = sharedId,
        )

        val containerA = container("STEAM_111", controlsProfileId = sharedId)
        val migratedA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerA)
        assertNotEquals(
            "container A must end up on a NEW profile id, not the shared one",
            sharedId.toInt(),
            migratedA.id,
        )

        val reloadedA = WebViewContainer.load("wayward-aaaa")
        assertNotNull(reloadedA)
        assertEquals(migratedA.id.toLong(), reloadedA!!.controlsProfileId)

        // B's WebViewContainer.json is untouched -- still points at the original sharedId.
        val reloadedB = WebViewContainer.load("look-outside-bbbb")
        assertNotNull(reloadedB)
        assertEquals(sharedId, reloadedB!!.controlsProfileId)
    }

    // after A migrates, B's next launch sees no remaining collision, so B keeps the original
    // sharedId.
    @Test fun second_container_keeps_original_id_after_first_migrated() {
        val seedProfile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("seed"))
        val sharedId = seedProfile.id.toLong()

        seedSharedProfileIdContainers(
            slugA = "wayward-aaaa", idA = "STEAM_111",
            slugB = "look-outside-bbbb", idB = "STEAM_222",
            sharedProfileId = sharedId,
        )

        val containerA = container("STEAM_111", controlsProfileId = sharedId)
        Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerA)

        // A is on a new id now, so no other container shares sharedId and B keeps it.
        val containerBv2 = container("STEAM_222", controlsProfileId = sharedId)
        val profileBv2 = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerBv2)
        assertEquals(
            "container B keeps original sharedId after A migrates",
            sharedId.toInt(),
            profileBv2.id,
        )
        val reloadedB = WebViewContainer.load("look-outside-bbbb")
        assertEquals(sharedId, reloadedB!!.controlsProfileId)
    }

    // A's remap MUST NOT leak into B once migration completes.
    @Test fun remap_after_migration_does_not_leak_to_sibling() {
        val seedProfile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("seed"))
        val sharedId = seedProfile.id.toLong()

        seedSharedProfileIdContainers(
            slugA = "wayward-aaaa", idA = "STEAM_111",
            slugB = "look-outside-bbbb", idB = "STEAM_222",
            sharedProfileId = sharedId,
        )

        val migratedA = Html5DefaultControlsProfileFactory.getOrCreate(
            ctx,
            container("STEAM_111", controlsProfileId = sharedId),
        )

        migratedA.loadControllers()
        val wildcardA = migratedA.getController("*")!!
        val pre = wildcardA.controllerBindings.toList()
        for (b in pre) wildcardA.removeControllerBinding(b)
        val remap = com.winlator.inputcontrols.ExternalControllerBinding()
        remap.setKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP)
        remap.setBinding(com.winlator.inputcontrols.Binding.KEY_W)
        wildcardA.addControllerBinding(remap)
        migratedA.save()

        // B still on the original sharedId, unaffected by A's remap.
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(
            ctx,
            container("STEAM_222", controlsProfileId = sharedId),
        )
        profileB.loadControllers()
        val wildcardB = profileB.getController("*")!!
        val dpadUpInB = wildcardB.controllerBindings.firstOrNull {
            it.keyCodeForAxis == android.view.KeyEvent.KEYCODE_DPAD_UP
        }?.binding
        assertNotEquals(
            "B must NOT see A's KEY_W remap after A migrated to fresh profile",
            com.winlator.inputcontrols.Binding.KEY_W,
            dpadUpInB,
        )
    }

    // an already-unique profileId must NOT migrate on launch -- no churning profiles every launch.
    @Test fun unique_profileId_does_not_migrate() {
        val containerAv1 = container("alpha")
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerAv1)
        val idA = profileA.id

        val rootDir = File(DownloadService.baseExternalAppDirPath, "html5-containers")
        rootDir.mkdirs()
        val cfgA = File(File(rootDir, "alpha-slug").apply { mkdirs() }, "config.json")
        WebViewContainer.save(
            "alpha-slug",
            WebViewContainer(
                id = "alpha",
                installPath = "/tmp/alpha",
                engineProfile = "test",
                controlsProfileId = idA.toLong(),
            ),
            cfgA,
        )

        // relaunch A -- no other container shares idA, so migration is skipped.
        val containerAv2 = container("alpha", controlsProfileId = idA.toLong())
        val profileAv2 = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerAv2)
        assertEquals(
            "unique profileId must survive relaunch without migration churn",
            idA,
            profileAv2.id,
        )
    }
}
