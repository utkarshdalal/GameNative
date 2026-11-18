package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GOGGame
import app.gamenative.db.PluviaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GOGGameDaoTest {

    private lateinit var db: PluviaDatabase
    private lateinit var dao: GOGGameDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.gogGameDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun game(id: String, hidden: Boolean = false) = GOGGame(
        id = id,
        title = "Game $id",
        hidden = hidden,
    )

    @Test
    fun applyHiddenFlagsMarksListedIdsAndClearsOthers() = runBlocking {
        dao.insertAll(listOf(game("1"), game("2"), game("3")))

        dao.applyHiddenFlags(listOf("1", "3"))

        assertTrue(dao.getById("1")!!.hidden)
        assertFalse(dao.getById("2")!!.hidden)
        assertTrue(dao.getById("3")!!.hidden)

        // A fresh apply clears flags that are no longer hidden.
        dao.applyHiddenFlags(listOf("2"))

        assertFalse(dao.getById("1")!!.hidden)
        assertTrue(dao.getById("2")!!.hidden)
        assertFalse(dao.getById("3")!!.hidden)
    }

    @Test
    fun updateStoreInstallSizeOnlyChangesGamesThatAreNotInstalled() = runBlocking {
        dao.insertAll(listOf(game("1"), game("2").copy(isInstalled = true, installPath = "/games/g2", installSize = 500L)))

        dao.updateStoreInstallSize("1", 1000L)
        dao.updateStoreInstallSize("2", 1000L)

        assertEquals(1000L, dao.getById("1")!!.installSize)
        assertEquals(500L, dao.getById("2")!!.installSize)
        assertEquals("/games/g2", dao.getById("2")!!.installPath)
    }

    @Test
    fun upsertPreservingInstallStatusPreservesHiddenAndInstallState() = runBlocking {
        dao.insert(
            game("1", hidden = true).copy(
                isInstalled = true,
                installPath = "/games/g1",
                verticalCoverUrl = "https://images.gog.com/cover.webp",
            )
        )

        dao.upsertPreservingInstallStatus(listOf(game("1", hidden = false).copy(title = "Updated")))

        val existing = dao.getById("1")!!
        assertTrue(existing.hidden)
        assertTrue(existing.isInstalled)
        assertEquals("/games/g1", existing.installPath)
        assertEquals("https://images.gog.com/cover.webp", existing.verticalCoverUrl)
        assertEquals("Updated", existing.title)

        // New rows are inserted with the values they carry.
        dao.upsertPreservingInstallStatus(listOf(game("2", hidden = true)))
        assertTrue(dao.getById("2")!!.hidden)
    }

    @Test
    fun upsertPreservingInstallStatusUsesIncomingNonBlankCover() = runBlocking {
        dao.insert(
            game("1", hidden = true).copy(
                isInstalled = true,
                installPath = "/games/g1",
                verticalCoverUrl = "https://images.gog.com/old-cover.webp",
            )
        )

        val newCoverUrl = "https://images.gog.com/new-cover.webp"
        dao.upsertPreservingInstallStatus(
            listOf(game("1").copy(verticalCoverUrl = newCoverUrl))
        )

        val updated = dao.getById("1")!!
        assertEquals(newCoverUrl, updated.verticalCoverUrl)
        assertTrue(updated.hidden)
        assertTrue(updated.isInstalled)
        assertEquals("/games/g1", updated.installPath)
    }

    @Test
    fun upsertPreservingInstallStatusKeepsStoredDeveloperAndReleaseDateWhenIncomingIsBlank() = runBlocking {
        dao.insert(
            game("1").copy(developer = "CD Projekt RED", publisher = "CD Projekt", releaseDate = "2015-05-19T00:00:00+0000")
        )

        dao.upsertPreservingInstallStatus(listOf(game("1").copy(releaseDate = "null")))

        val updated = dao.getById("1")!!
        assertEquals("CD Projekt RED", updated.developer)
        assertEquals("CD Projekt", updated.publisher)
        assertEquals("2015-05-19T00:00:00+0000", updated.releaseDate)
    }

    @Test
    fun fillGamesDbInfoOnlyFillsEmptyFields() = runBlocking {
        dao.insertAll(
            listOf(
                game("1").copy(releaseDate = "null"),
                game("2").copy(developer = "Stored Dev", verticalCoverUrl = "https://images.gog.com/cover.webp", releaseDate = "2012-04-17T00:00:00+0000"),
            )
        )
        assertEquals(listOf("1"), dao.getGameIdsMissingGamesDbInfo())

        dao.fillGamesDbInfo("1", "https://images.gog.com/new.webp", "New Dev", "New Pub", "2020-12-10T00:00:00+0000")
        dao.fillGamesDbInfo("2", "", "Other Dev", "Other Pub", "2020-12-10T00:00:00+0000")

        val first = dao.getById("1")!!
        assertEquals("https://images.gog.com/new.webp", first.verticalCoverUrl)
        assertEquals("New Dev", first.developer)
        assertEquals("New Pub", first.publisher)
        assertEquals("2020-12-10T00:00:00+0000", first.releaseDate)
        val second = dao.getById("2")!!
        assertEquals("https://images.gog.com/cover.webp", second.verticalCoverUrl)
        assertEquals("Stored Dev", second.developer)
        assertEquals("Other Pub", second.publisher)
        assertEquals("2012-04-17T00:00:00+0000", second.releaseDate)
        assertTrue(dao.getGameIdsMissingGamesDbInfo().isEmpty())
    }

    @Test
    fun getAllEmitsUpdatedHiddenRows() = runBlocking {
        dao.insertAll(listOf(game("1"), game("2")))

        dao.applyHiddenFlags(listOf("2"))

        val updated = dao.getAll().first { list -> list.any { it.hidden } }
        assertEquals(listOf("1", "2"), updated.map { it.id })
        assertFalse(updated.first { it.id == "1" }.hidden)
        assertTrue(updated.first { it.id == "2" }.hidden)
    }

    @Test
    fun applyHiddenFlagsHandlesMoreThanSqliteBindLimit() = runBlocking {
        val count = 1001
        dao.insertAll((1..count).map { game(it.toString()) })

        dao.applyHiddenFlags((1..count).map { it.toString() })

        assertEquals(count, dao.getAllAsList().count { it.hidden })
    }
}
