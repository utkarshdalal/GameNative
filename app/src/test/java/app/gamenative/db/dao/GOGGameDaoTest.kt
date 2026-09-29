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

/** Builds a GOG game fixture for the assertions in this test. */
    private fun game(
        id: String,
        gogComHidden: Boolean = false,
        galaxyHidden: Boolean = false,
    ) = GOGGame(
        id = id,
        title = "Game $id",
        gogComHidden = gogComHidden,
        galaxyHidden = galaxyHidden,
    )

/** Verifies the behavior described by this test: reconcile GOG.com Hidden Changes Only Observed IDs And Preserves Omissions. */
    @Test
    fun reconcileGogComHiddenChangesOnlyObservedIdsAndPreservesOmissions() = runBlocking {
        dao.insertAll(
            listOf(
                game("1", gogComHidden = true, galaxyHidden = true),
                game("2", gogComHidden = true),
                game("3", galaxyHidden = true),
            ),
        )

        dao.reconcileGogComHidden(
            trueIds = listOf("3"),
            falseIds = listOf("1"),
        )

        assertFalse(dao.getById("1")!!.gogComHidden)
        assertTrue(dao.getById("1")!!.galaxyHidden)
        assertTrue(dao.getById("2")!!.gogComHidden)
        assertFalse(dao.getById("2")!!.galaxyHidden)
        assertTrue(dao.getById("3")!!.gogComHidden)
        assertTrue(dao.getById("3")!!.galaxyHidden)

        // Unknown remote IDs are ignored and do not create rows.
        dao.reconcileGogComHidden(trueIds = listOf("unknown"), falseIds = emptyList())
        assertEquals(null, dao.getById("unknown"))
    }

/** Verifies the behavior described by this test: reconcile Galaxy Hidden Cannot Overwrite GOG.com Hidden. */
    @Test
    fun reconcileGalaxyHiddenCannotOverwriteGogComHidden() = runBlocking {
        dao.insert(game("1", gogComHidden = true, galaxyHidden = true))

        dao.reconcileGalaxyHidden(trueIds = emptyList(), falseIds = listOf("1"))

        val game = dao.getById("1")!!
        assertTrue(game.gogComHidden)
        assertFalse(game.galaxyHidden)
        assertTrue(game.hidden)
    }

/** Verifies the behavior described by this test: upsert Preserving Install Status Preserves Both Hidden Sources And Install State. */
    @Test
    fun upsertPreservingInstallStatusPreservesBothHiddenSourcesAndInstallState() = runBlocking {
        dao.insert(
            game("1", gogComHidden = true, galaxyHidden = true).copy(
                isInstalled = true,
                installPath = "/games/g1",
                verticalCoverUrl = "https://images.gog.com/cover.webp",
            )
        )

        dao.upsertPreservingInstallStatus(listOf(game("1").copy(title = "Updated")))

        val existing = dao.getById("1")!!
        assertTrue(existing.gogComHidden)
        assertTrue(existing.galaxyHidden)
        assertTrue(existing.isInstalled)
        assertEquals("/games/g1", existing.installPath)
        assertEquals("https://images.gog.com/cover.webp", existing.verticalCoverUrl)
        assertEquals("Updated", existing.title)

        // New rows carry the source defaults supplied by the metadata refresh.
        dao.upsertPreservingInstallStatus(listOf(game("2")))
        assertFalse(dao.getById("2")!!.hidden)
    }

    @Test
    fun upsertPreservingInstallStatusUsesIncomingNonBlankCover() = runBlocking {
        dao.insert(
            game("1", gogComHidden = true).copy(
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
        assertTrue(updated.gogComHidden)
        assertTrue(updated.isInstalled)
        assertEquals("/games/g1", updated.installPath)
    }

/** Verifies the behavior described by this test: get All Emits Updated Effective Hidden Rows. */
    @Test
    fun getAllEmitsUpdatedEffectiveHiddenRows() = runBlocking {
        dao.insertAll(listOf(game("1"), game("2")))

        dao.reconcileGalaxyHidden(trueIds = listOf("2"), falseIds = emptyList())

        val updated = dao.getAll().first { list -> list.any { it.galaxyHidden } }
        assertEquals(listOf("1", "2"), updated.map { it.id })
        assertFalse(updated.first { it.id == "1" }.hidden)
        assertTrue(updated.first { it.id == "2" }.hidden)
    }

/** Verifies the behavior described by this test: source Reconciliation Handles More Than SQLite Bind Limit. */
    @Test
    fun sourceReconciliationHandlesMoreThanSqliteBindLimit() = runBlocking {
        val count = 1001
        dao.insertAll((1..count).map { game(it.toString()) })

        dao.reconcileGogComHidden(
            trueIds = (1..count).map { it.toString() },
            falseIds = emptyList(),
        )

        assertEquals(count, dao.getAllAsList().count { it.gogComHidden })
    }

/** Verifies the behavior described by this test: clear Hidden Source Flags Resets Both Sources Together. */
    @Test
    fun clearHiddenSourceFlagsResetsBothSourcesTogether() = runBlocking {
        dao.insert(game("1", gogComHidden = true, galaxyHidden = true))

        dao.clearHiddenSourceFlags()

        val cleared = dao.getById("1")!!
        assertFalse(cleared.gogComHidden)
        assertFalse(cleared.galaxyHidden)
    }
}
