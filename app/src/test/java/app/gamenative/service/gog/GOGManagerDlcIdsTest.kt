package app.gamenative.service.gog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.GOGGameDao
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGManagerDlcIdsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: PluviaDatabase
    private lateinit var dao: GOGGameDao
    private lateinit var manager: GOGManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.gogGameDao()
        manager = GOGManager(dao, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun installedGame(installDir: File) = GOGGame(
        id = "100",
        title = "Game",
        isInstalled = true,
        installPath = installDir.absolutePath,
    )

    @Test
    fun updateInstalledDlcIdsKeepsIdsOfEarlierCalls() = runBlocking {
        dao.insert(GOGGame(id = "100", installedDlcIds = listOf("1")))

        dao.updateInstalledDlcIds("100", listOf("2"))
        dao.updateInstalledDlcIds("100", listOf("3", "1"))

        assertEquals(listOf("1", "2", "3"), dao.getById("100")!!.installedDlcIds)
    }

    @Test
    fun updateInstallStateKeepsInstalledDlcIds() = runBlocking {
        dao.insert(GOGGame(id = "100", installedDlcIds = listOf("1")))

        dao.updateInstallState("100", isInstalled = true, installPath = "/games/g", installSize = 5L)

        val game = dao.getById("100")!!
        assertTrue(game.isInstalled)
        assertEquals("/games/g", game.installPath)
        assertEquals(5L, game.installSize)
        assertEquals(listOf("1"), game.installedDlcIds)
    }

    @Test
    fun dlcIdsOnDiskSkipBaseAndPendingInstallersAndReadGameSubdirs() = runBlocking {
        val installDir = tmp.newFolder("game")
        installDir.resolve("goggame-100.info").writeText("{}")
        installDir.resolve("goggame-200.info").writeText("{}")
        installDir.resolve("sub").mkdirs()
        installDir.resolve("sub/goggame-300.info").writeText("{}")
        val bonusDir = installDir.resolve(GOGBonusDlcInstaller.BONUS_DIR)
        bonusDir.resolve("400").mkdirs()
        bonusDir.resolve("400/${GOGBonusDlcInstaller.APPLIED_FILE}").writeText("a.zip")
        bonusDir.resolve("500").mkdirs()
        bonusDir.resolve("500/${GOGBonusDlcInstaller.APPLIED_FILE}").writeText("setup.exe")
        bonusDir.resolve("500/${GOGBonusDlcInstaller.PENDING_INSTALLER_FILE}").writeText("setup.exe")
        dao.insert(installedGame(installDir))

        assertEquals(setOf("200", "300", "400"), manager.getDlcIdsOnDisk("100"))
    }

    @Test
    fun installedDlcIdsCombineRecordedIdsAndSubdirInfoFiles() = runBlocking {
        val installDir = tmp.newFolder("game")
        installDir.resolve("game").mkdirs()
        installDir.resolve("game/goggame-100.info").writeText("{}")
        installDir.resolve("game/goggame-200.info").writeText("{}")
        dao.insert(installedGame(installDir).copy(installedDlcIds = listOf("600")))

        assertEquals(setOf("200", "600"), manager.getInstalledDlcIds("100"))
    }

    @Test
    fun installedDlcIdsIncludeAppliedBonusDlcButRecordedIdsDoNot() = runBlocking {
        val installDir = tmp.newFolder("game")
        installDir.resolve("goggame-200.info").writeText("{}")
        val bonusDir = installDir.resolve(GOGBonusDlcInstaller.BONUS_DIR)
        bonusDir.resolve("400").mkdirs()
        bonusDir.resolve("400/${GOGBonusDlcInstaller.APPLIED_FILE}").writeText("a.zip")
        dao.insert(installedGame(installDir).copy(installedDlcIds = listOf("600")))

        assertEquals(setOf("200", "400", "600"), manager.getInstalledDlcIds("100"))
        assertEquals(setOf("600"), manager.getRecordedDlcIds("100"))
    }

    @Test
    fun recordedDlcIdsAreEmptyWhenInstallFolderIsGone() = runBlocking {
        val installDir = File(tmp.root, "deleted")
        dao.insert(installedGame(installDir).copy(installedDlcIds = listOf("600")))

        assertEquals(emptySet<String>(), manager.getRecordedDlcIds("100"))
    }

    @Test
    fun setInstalledDlcIdsReplacesIdsOfAnEarlierInstall() = runBlocking {
        val installDir = tmp.newFolder("game")
        dao.insert(installedGame(installDir).copy(installedDlcIds = listOf("600", "700")))

        manager.setInstalledDlcIds("100", setOf("800"))

        assertEquals(listOf("800"), dao.getById("100")!!.installedDlcIds)
        assertEquals(setOf("800"), manager.getRecordedDlcIds("100"))
    }

    @Test
    fun setInstalledDlcIdsClearsIdsWhenNoneArePlanned() = runBlocking {
        dao.insert(GOGGame(id = "100", installedDlcIds = listOf("600")))

        manager.setInstalledDlcIds("100", emptySet())

        assertEquals(emptyList<String>(), dao.getById("100")!!.installedDlcIds)
    }
}
