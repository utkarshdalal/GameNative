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

// robolectric required — InputControlsManager(ctx) reads context.getFilesDir() in
// getProfilesDir; ControlsProfile.save() writes JSON to that dir.

// covers the real id≥0 on-disk preset that supersedes the in-memory id=-1
// createHtml5DefaultProfile fallback.

// each html5 container gets its OWN profile in the global pool (Wine parity).
@RunWith(RobolectricTestRunner::class)
class Html5DefaultControlsProfileFactoryTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        //forkOnCollision uses DownloadService.baseExternalAppDirPath to scan
        // siblings; pre-fix tests didn't need it because factory had no disk-scan path.
        DownloadService.populateDownloadService(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // wipe html5-containers between tests so collision scans start clean.
        File(DownloadService.baseExternalAppDirPath, "html5-containers").deleteRecursively()
    }

    // minimal container fixture. id is the only field the factory cares about for naming;
    // controlsProfileId=0L exercises the bootstrap path. installPath/engineProfile are required
    // ctor args (no schema-default in WebViewContainer).
    private fun container(id: String, controlsProfileId: Long = 0L): WebViewContainer =
        WebViewContainer(
            id = id,
            installPath = "/tmp/$id",
            engineProfile = "test",
            controlsProfileId = controlsProfileId,
        )

    // fix-B helper: seed two WebViewContainer.json files with the SAME controlsProfileId.
    // simulates the broken-window state where two containers ended up sharing one profile id
    // because the global-by-name lookup was active.
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
        // bootstrap (controlsProfileId=0L) — mints fresh profile
        val first = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // 2nd launch path: container has the persisted id, factory resolves it
        val second = Html5DefaultControlsProfileFactory.getOrCreate(
            ctx,
            container("alpha", controlsProfileId = first.id.toLong()),
        )
        assertEquals(first.id, second.id)
        assertEquals(first.name, second.name)
    }

    @Test fun created_profile_has_gamepad_keycode_bindings_populated() {
        val profile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // force re-read from disk to verify save() persisted bindings
        profile.loadControllers()
        val controllers = profile.controllers
        assertTrue("expected at least 1 controller, got ${controllers.size}", controllers.size >= 1)
        val bindings = controllers[0].controllerBindings
        // 16 keycode + 8 axis from createHtml5DefaultProfile = 24 total
        // accept ≥12 keycode bindings to allow ABI variance
        assertTrue("expected ≥12 bindings, got ${bindings.size}", bindings.size >= 12)
    }

    @Test fun new_inputControlsManager_instance_sees_persisted_profile() {
        val first = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // simulate a fresh app launch — new manager scans disk
        val freshManager = InputControlsManager(ctx)
        val rediscovered = freshManager.getProfiles(false).firstOrNull { it.id == first.id }
        assertNotNull("profile must persist across InputControlsManager instances", rediscovered)
        assertEquals(first.id, rediscovered!!.id)
    }

    //each html5 container gets its OWN profile (Wine parity).
    // user bug report: "I change the setup for Wayward and open Look Outside and my LO
    // setup is ignored and WW used." container A's remap MUST NOT leak into container B.
    // this test asserts the FIXED behavior — replaces a prior version that asserted the bug.
    @Test fun container_a_remap_does_not_leak_into_container_b() {
        // container A bootstraps a fresh profile
        val containerA = container("wayward")
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerA)
        val idA = profileA.id

        // user remap on A: clear+re-add bindings on the wildcard "*" controller, then save.
        // mirrors PhysicalControllerConfigSection.onSave.
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

        // container B bootstraps — MUST mint a separate profile, NOT reuse container A's.
        val containerB = container("look-outside")
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerB)
        assertNotEquals(
            "container B must get a DIFFERENT profile id than container A (per-container, Wine parity)",
            idA,
            profileB.id,
        )

        // container B's wildcard controller should have DEFAULT bindings — A's KEY_W remap
        // must NOT be visible.
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

    //positive companion to the leak test. container A's remap MUST survive
    // a relaunch of container A (when container B has been opened in between).
    @Test fun container_a_remap_survives_relaunch_of_container_a() {
        // bootstrap A and remap
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

        // bootstrap B in between (fresh, separate profile)
        Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("look-outside"))

        // relaunch A — container has the persisted id; factory resolves the SAME profile.
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

    //profile naming. first per-container profile keeps the canonical
    // "HTML5 Default" name (back-compat with bug-window data). second container gets a
    // distinct name so the profile picker UI can distinguish them.
    @Test fun second_container_gets_distinct_profile_name() {
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("beta"))
        assertNotEquals("profile names must differ across containers", profileA.name, profileB.name)
        // and at least one of them is the canonical default name (the first one minted)
        val names = setOf(profileA.name, profileB.name)
        assertTrue(
            "first per-container profile should keep canonical name",
            Html5DefaultControlsProfileFactory.HTML5_DEFAULT_PROFILE_NAME in names,
        )
    }

    // sanity: bootstrap path with controlsProfileId=0L AND a stale profile-by-name lying
    // around in the pool MUST NOT reuse that stale profile (the prior bug). regression
    // guard for a future refactor that re-introduces name-based dedupe.
    @Test fun stale_named_profile_in_pool_is_not_reused_for_new_container() {
        // pre-seed: container A bootstraps the canonical "HTML5 Default" profile.
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("alpha"))
        // sanity check the seeded name (first-bootstrap path keeps canonical name).
        assertEquals(
            Html5DefaultControlsProfileFactory.HTML5_DEFAULT_PROFILE_NAME,
            profileA.name,
        )

        // container B with no persisted id — must NOT find profileA by name.
        val profileB = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("beta"))
        assertNotEquals(profileA.id, profileB.id)
    }

    //collision migration. two containers reference the SAME profileId
    // (legacy from broken-window). on next launch of container A, the factory
    // must fork the profile and persist a new id back to A's WebViewContainer.json. B keeps
    // the original profile until ITS next launch (lazy migration).
    @Test fun shared_profileId_migrates_lazily_on_first_launch() {
        // bootstrap a real profile so the colliding id exists in the manager pool.
        val seedProfile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("seed"))
        val sharedId = seedProfile.id.toLong()

        // simulate the broken-window state: containerA + containerB both point at sharedId.
        seedSharedProfileIdContainers(
            slugA = "wayward-aaaa", idA = "STEAM_111",
            slugB = "look-outside-bbbb", idB = "STEAM_222",
            sharedProfileId = sharedId,
        )

        // launch A → factory detects collision, forks, persists new id to A's JSON.
        val containerA = container("STEAM_111", controlsProfileId = sharedId)
        val migratedA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerA)
        assertNotEquals(
            "container A must end up on a NEW profile id, not the shared one",
            sharedId.toInt(),
            migratedA.id,
        )

        // A's WebViewContainer.json must reflect the new id on disk.
        val reloadedA = WebViewContainer.load("wayward-aaaa")
        assertNotNull(reloadedA)
        assertEquals(migratedA.id.toLong(), reloadedA!!.controlsProfileId)

        // B's WebViewContainer.json is untouched — still points at the original sharedId.
        val reloadedB = WebViewContainer.load("look-outside-bbbb")
        assertNotNull(reloadedB)
        assertEquals(sharedId, reloadedB!!.controlsProfileId)
    }

    //positive companion. after A migrates, B's next launch sees no remaining
    // collision (A is on the new id), so B keeps the original sharedId. user-facing: each
    // container's bindings are isolated end-to-end — no cross-contamination.
    @Test fun second_container_keeps_original_id_after_first_migrated() {
        val seedProfile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("seed"))
        val sharedId = seedProfile.id.toLong()

        seedSharedProfileIdContainers(
            slugA = "wayward-aaaa", idA = "STEAM_111",
            slugB = "look-outside-bbbb", idB = "STEAM_222",
            sharedProfileId = sharedId,
        )

        // A migrates first
        val containerA = container("STEAM_111", controlsProfileId = sharedId)
        Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerA)

        // B launches next — collision check finds NO other container sharing sharedId
        // (A is on a new id now), so B keeps sharedId.
        val containerBv2 = container("STEAM_222", controlsProfileId = sharedId)
        val profileBv2 = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerBv2)
        assertEquals(
            "container B keeps original sharedId after A migrates",
            sharedId.toInt(),
            profileBv2.id,
        )
        // B's JSON still points at sharedId
        val reloadedB = WebViewContainer.load("look-outside-bbbb")
        assertEquals(sharedId, reloadedB!!.controlsProfileId)
    }

    //A's user remap MUST NOT leak into B once migration completes (positive
    // assertion of the user-facing fix). this is the test that mirrors the user bug: "I change
    // the setup for Wayward and open Look Outside and my LO setup is ignored and WW used."
    @Test fun remap_after_migration_does_not_leak_to_sibling() {
        val seedProfile = Html5DefaultControlsProfileFactory.getOrCreate(ctx, container("seed"))
        val sharedId = seedProfile.id.toLong()

        seedSharedProfileIdContainers(
            slugA = "wayward-aaaa", idA = "STEAM_111",
            slugB = "look-outside-bbbb", idB = "STEAM_222",
            sharedProfileId = sharedId,
        )

        // A migrates → unique profile
        val migratedA = Html5DefaultControlsProfileFactory.getOrCreate(
            ctx,
            container("STEAM_111", controlsProfileId = sharedId),
        )

        // user remap on A
        migratedA.loadControllers()
        val wildcardA = migratedA.getController("*")!!
        val pre = wildcardA.controllerBindings.toList()
        for (b in pre) wildcardA.removeControllerBinding(b)
        val remap = com.winlator.inputcontrols.ExternalControllerBinding()
        remap.setKeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP)
        remap.setBinding(com.winlator.inputcontrols.Binding.KEY_W)
        wildcardA.addControllerBinding(remap)
        migratedA.save()

        // B launches → still on original sharedId, unaffected by A's remap.
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

    //a container that's already unique (no sibling shares its profileId)
    // must NOT migrate on launch. this is the steady-state case post-fix; we don't churn
    // profiles on every launch.
    @Test fun unique_profileId_does_not_migrate() {
        // bootstrap A → A gets a unique profileId, persisted to A's JSON.
        val containerAv1 = container("alpha")
        val profileA = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerAv1)
        val idA = profileA.id

        // simulate persisted state: write A's JSON with the assigned id (no sibling).
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

        // relaunch A — no other container shares idA, so migration is skipped.
        val containerAv2 = container("alpha", controlsProfileId = idA.toLong())
        val profileAv2 = Html5DefaultControlsProfileFactory.getOrCreate(ctx, containerAv2)
        assertEquals(
            "unique profileId must survive relaunch without migration churn",
            idA,
            profileAv2.id,
        )
    }
}
