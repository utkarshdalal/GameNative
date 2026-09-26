package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GyroSettings
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfilePersistenceTest {
    @Test
    fun existingVoidSaveStillPersistsUnrelatedContainerSettings() = Fixture().use { fixture ->
        fixture.game.drives = "A:/customD:/games"
        fixture.game.screenSize = "1920x1080"
        fixture.game.setSteamOfflineMode(true)
        fixture.game.saveData()
        val loaded = Container(fixture.game.id).apply { loadData(JSONObject(fixture.game.configFile.readText())) }
        assertEquals(fixture.game.drives, loaded.drives)
        assertEquals(fixture.game.screenSize, loaded.screenSize)
        assertTrue(loaded.isSteamOfflineMode)
        assertEquals(fixture.game.extraData.toString(), loaded.extraData.toString())
    }

    @Test
    fun checkedContainerSaveReportsActualWriteFailureAndCanRetry() {
        Fixture().use { fixture ->
            val blocked = fixture.game.configFile
            assertTrue(blocked.delete())
            assertTrue(blocked.mkdir())
            val sentinel = blocked.resolve("keep.dat").apply { writeText("keep") }
            assertFalse(fixture.game.saveDataChecked())
            assertEquals("keep", sentinel.readText())
            assertTrue(sentinel.delete())
            assertTrue(blocked.delete())
            assertTrue(fixture.game.saveDataChecked())
            assertEquals(fixture.game.id, JSONObject(blocked.readText()).getString("id"))
        }
    }

    @Test
    fun failedApplyRestoresExistingProfileDiskConfigAndExactLiveSettings() {
        for (throws in listOf(false, true)) {
            Fixture().use { fixture ->
                val working = fixture.own(ControlProfileService.ensureWorkingProfile(fixture.context, fixture.game, fixture.manager)!!)
                val before = fixture.snapshot()
                fixture.game.failSave = true
                fixture.game.throwOnSave = throws
                fixture.game.onSave = {
                    assertEquals(1, fixture.backups().size)
                    val entries = InputControlsManager(fixture.context).allProfiles
                    assertEquals(entries.size, entries.map { it.id }.distinct().size)
                }
                assertThrows(IOException::class.java) { fixture.apply() }
                fixture.assertUnchanged(before)
                assertTrue(fixture.profileFile(working).isFile)
                fixture.assertNoBackups()

                fixture.game.failSave = false
                fixture.game.onSave = {}
                val applied = fixture.apply()
                assertEquals(working.id, applied.id)
                assertEquals(GyroSettings.MODE_MOUSE, GyroSettings.fromContainer(fixture.game).mode)
                val reloaded = Container(fixture.game.id).apply { loadData(JSONObject(fixture.game.configFile.readText())) }
                assertEquals(GyroSettings.fromContainer(fixture.game), GyroSettings.fromContainer(reloaded))
                assertTrue(reloaded.isTouchscreenMode)
                assertTrue(reloaded.isShooterMode)
                fixture.assertNoBackups()
            }
        }
    }

    @Test
    fun failedFirstApplyDoesNotChangeAssociationOrLeaveAWorkingCopy() = Fixture().use { fixture ->
        val before = fixture.snapshot()
        fixture.game.failSave = true
        assertThrows(IOException::class.java) { fixture.apply() }
        fixture.assertUnchanged(before)
        fixture.assertNoBackups()
    }

    @Test
    fun failedCreateAndApplyDoesNotLeaveTheNewLibraryProfile() = Fixture().use { fixture ->
        val before = fixture.snapshot()
        fixture.game.failSave = true
        assertThrows(IOException::class.java) {
            ControlProfileService.createAndApply(
                fixture.context,
                fixture.game,
                fixture.manager,
                "Atomic create",
                ControlProfileSection.entries.toSet(),
                fromCurrent = true,
            )
        }
        fixture.assertUnchanged(before)
    }

    @Test
    fun failedApplyPreservesAbsentProfileReferencesAndNullExtraData() {
        for (extra in listOf(null, JSONObject().put("unrelated", "keep"))) {
            Fixture().use { fixture ->
                fixture.game.extraData = extra
                assertTrue(fixture.game.saveDataChecked())
                val before = fixture.snapshot()
                fixture.game.failSave = true
                assertThrows(IOException::class.java) { fixture.apply() }
                fixture.assertUnchanged(before)
                fixture.assertNoBackups()
            }
        }
    }

    @Test
    fun failedWorkingCopyCreationDoesNotChangeAssociationOrLeaveAProfile() = Fixture().use { fixture ->
        val before = fixture.snapshot()
        fixture.game.failSave = true
        assertThrows(IOException::class.java) {
            ControlProfileService.ensureWorkingProfile(fixture.context, fixture.game, fixture.manager)
        }
        fixture.assertUnchanged(before)
        fixture.assertNoBackups()
    }

    @Test
    fun failedMigrationPreservesTheLibraryAndContainerReference() = Fixture().use { fixture ->
        val before = fixture.snapshot()
        fixture.game.failSave = true
        assertThrows(IOException::class.java) {
            ControlProfileService.migrateReferencedLibraryProfiles(fixture.context, fixture.manager, listOf(fixture.game))
        }
        fixture.assertUnchanged(before)
    }

    @Test
    fun invalidCategorySelectionDoesNotCreateOrSaveAWorkingCopy() = Fixture().use { fixture ->
        val before = fixture.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            ControlProfileService.applyProfile(fixture.context, fixture.game, fixture.manager, fixture.source, emptySet())
        }
        fixture.assertUnchanged(before)
    }

    @Test
    fun failedProfileWriteNeverCommitsContainerChanges() = Fixture().use { fixture ->
        val working = fixture.own(ControlProfileService.ensureWorkingProfile(fixture.context, fixture.game, fixture.manager)!!)
        val before = fixture.snapshot()
        val target = fixture.profileFile(working)
        Mockito.mockStatic(FileUtils::class.java) { call ->
            if (call.method.name == "writeString" && call.arguments.firstOrNull() == target) false else call.callRealMethod()
        }.use {
            assertThrows(IOException::class.java) { fixture.apply() }
        }
        fixture.assertUnchanged(before)
        fixture.assertNoBackups()
    }

    @Test
    fun failedUpdateOfWorkingCopyRestoresTheLibraryEntry() = Fixture().use { fixture ->
        val working = fixture.own(ControlProfileService.ensureWorkingProfile(fixture.context, fixture.game, fixture.manager)!!)
        val before = fixture.snapshot()
        val target = fixture.profileFile(working)
        Mockito.mockStatic(FileUtils::class.java) { call ->
            if (call.method.name == "writeString" && call.arguments.firstOrNull() == target) false else call.callRealMethod()
        }.use {
            assertThrows(IOException::class.java) {
                ControlProfileService.updateFromCurrent(
                    fixture.context, fixture.game, fixture.manager, fixture.incoming, ControlProfileSection.entries.toSet(),
                )
            }
        }
        fixture.assertUnchanged(before)
        fixture.assertNoBackups()
    }

    @Test
    fun rollbackFailureReportsTheProblemAndKeepsARecoverableBackupOutsideTheLibrary() = Fixture().use { fixture ->
        val working = fixture.own(ControlProfileService.ensureWorkingProfile(fixture.context, fixture.game, fixture.manager)!!)
        val target = fixture.profileFile(working)
        val original = target.readText()
        fixture.game.failSave = true
        fixture.game.onSave = {
            // Simulate the destination becoming unusable while the container write fails.
            assertTrue(target.delete())
            assertTrue(target.mkdir())
            target.resolve("keep.dat").writeText("keep")
        }
        try {
            val error = assertThrows(IOException::class.java) { fixture.apply() }
            val backup = fixture.backups().single()
            assertTrue(error.message!!.contains("could not be restored"))
            assertTrue(error.message!!.contains(backup.name))
            assertEquals(original, backup.readText())
            assertEquals("keep", target.resolve("keep.dat").readText())
            assertEquals(fixture.context.filesDir, backup.parentFile)
        } finally {
            target.resolve("keep.dat").delete()
            target.delete()
            fixture.backups().forEach { it.delete() }
        }
    }

    private class TestContainer(id: String) : Container(id) {
        var failSave = false
        var throwOnSave = false
        var onSave: () -> Unit = {}
        override fun saveDataChecked(): Boolean {
            onSave()
            if (failSave) {
                if (throwOnSave) throw IOException("simulated write exception")
                return false
            }
            return super.saveDataChecked()
        }
    }

    private data class Snapshot(val config: String, val extra: String?, val touch: Boolean, val gestures: String,
        val shooter: Boolean, val shooterConfig: String, val profiles: Map<String, String>)

    private class Fixture : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("profile-save-").toFile()
        private val ids = mutableSetOf<Int>()
        val source = own(manager.createProfile("Persistence baseline"))
        val game = TestContainer(root.name).apply {
            rootDir = root
            putExtra("profileId", source.id.toString())
            putExtra("gyroSensitivity", "1.700") // Preserve raw values, not re-normalized defaults.
            putExtra("unrelated", JSONObject().put("nested", "keep"))
            setTouchscreenMode(false)
            gestureConfig = ""
            setShooterMode(false)
            shooterConfig = ""
            assertTrue(saveDataChecked())
        }
        val incoming = own(ControlProfileService.saveCurrentAsProfile(
            context, game, manager, "Incoming settings", ControlProfileSection.entries.toSet(),
        )).also { profile ->
            val json = ControlProfileService.readProfileJson(context, profile)
            json.put("gyroSettings", GyroSettings(mode = GyroSettings.MODE_MOUSE, sensitivity = 2f).toJsonObject())
            json.getJSONObject("touchscreenSettings").put("enabled", true)
            json.getJSONObject("shooterSettings").put("enabled", true)
            assertTrue(FileUtils.writeString(profileFile(profile), json.toString()))
        }
        fun own(profile: ControlsProfile) = profile.also { ids += it.id }
        fun profileFile(profile: ControlsProfile): File = ControlsProfile.getProfileFile(context, profile.id)
        fun apply() = own(ControlProfileService.applyProfile(context, game, manager, incoming))
        private fun files() = profileFile(source).parentFile!!.listFiles()!!.filter { it.extension == "icp" }
            .associate { it.name to it.readText() }
        fun snapshot() = Snapshot(game.configFile.readText(), game.extraData?.toString(), game.isTouchscreenMode,
            game.gestureConfig, game.isShooterMode, game.shooterConfig, files())
        fun assertUnchanged(before: Snapshot) { assertEquals(before, snapshot()) }
        fun backups() = context.filesDir.listFiles()!!.filter { it.extension == "rollback" }
        fun assertNoBackups() {
            assertTrue(backups().isEmpty())
            assertTrue(profileFile(source).parentFile!!.listFiles()!!.none { it.extension == "rollback" })
        }
        override fun close() {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }
}
