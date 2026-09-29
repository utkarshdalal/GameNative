package app.gamenative.db.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.PluviaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GOGHiddenGamesMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
    )

    /** Verifies that the migration preserves legacy hidden state in the GOG.com source flag. */
    @Test
    fun migratesV26HiddenIntoGogComSourceAndRemovesPhysicalHiddenColumn() {
        helper.createDatabase(TEST_DB, 26).apply {
            execSQL(
                """
                INSERT INTO gog_games (
                    id, title, slug, download_size, install_size, is_installed, install_path,
                    image_url, icon_url, background_url, vertical_cover_url, description,
                    release_date, developer, publisher, genres, languages, last_played, play_time,
                    type, exclude, hidden
                ) VALUES (
                    '100', 'Migrated', 'migrated', 10, 20, 1, '/games/migrated',
                    'image', 'icon', 'background', 'cover', 'description',
                    '2026-01-01', 'developer', 'publisher', '["rpg"]', '["en"]',
                    30, 40, 1, 0, 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO gog_games (
                    id, title, slug, download_size, install_size, is_installed, install_path,
                    image_url, icon_url, background_url, vertical_cover_url, description,
                    release_date, developer, publisher, genres, languages, last_played, play_time,
                    type, exclude, hidden
                ) VALUES (
                    '200', 'Installed', 'installed', 101, 202, 0, '/games/installed',
                    'image-2', 'icon-2', 'background-2', 'cover-2', 'description-2',
                    '2025-12-31', 'developer-2', 'publisher-2', '["adventure"]', '["fr"]',
                    303, 404, 2, 1, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            28,
            true,
            ROOM_MIGRATION_V26_to_V27,
            ROOM_MIGRATION_V27_to_V28,
        ).use { db ->
            val columns = db.query("PRAGMA table_info(gog_games)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }

            assertFalse(columns.contains("hidden"))
            assertTrue(columns.contains("gog_com_hidden"))
            assertTrue(columns.contains("galaxy_hidden"))

            db.query("SELECT * FROM gog_games ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertMigratedRow(
                    cursor = cursor,
                    id = "100",
                    title = "Migrated",
                    slug = "migrated",
                    downloadSize = 10,
                    installSize = 20,
                    isInstalled = 1,
                    installPath = "/games/migrated",
                    imageUrl = "image",
                    iconUrl = "icon",
                    backgroundUrl = "background",
                    verticalCoverUrl = "cover",
                    description = "description",
                    releaseDate = "2026-01-01",
                    developer = "developer",
                    publisher = "publisher",
                    genres = "[\"rpg\"]",
                    languages = "[\"en\"]",
                    lastPlayed = 30,
                    playTime = 40,
                    type = 1,
                    exclude = 0,
                    gogComHidden = 1,
                    galaxyHidden = 0,
                )

                assertTrue(cursor.moveToNext())
                assertMigratedRow(
                    cursor = cursor,
                    id = "200",
                    title = "Installed",
                    slug = "installed",
                    downloadSize = 101,
                    installSize = 202,
                    isInstalled = 0,
                    installPath = "/games/installed",
                    imageUrl = "image-2",
                    iconUrl = "icon-2",
                    backgroundUrl = "background-2",
                    verticalCoverUrl = "cover-2",
                    description = "description-2",
                    releaseDate = "2025-12-31",
                    developer = "developer-2",
                    publisher = "publisher-2",
                    genres = "[\"adventure\"]",
                    languages = "[\"fr\"]",
                    lastPlayed = 303,
                    playTime = 404,
                    type = 2,
                    exclude = 1,
                    gogComHidden = 0,
                    galaxyHidden = 0,
                )

                assertFalse(cursor.moveToNext())
            }
        }
    }

    /** Asserts that the migrated row retains the expected source-specific hidden flags. */
    private fun assertMigratedRow(
        cursor: android.database.Cursor,
        id: String,
        title: String,
        slug: String,
        downloadSize: Long,
        installSize: Long,
        isInstalled: Int,
        installPath: String,
        imageUrl: String,
        iconUrl: String,
        backgroundUrl: String,
        verticalCoverUrl: String,
        description: String,
        releaseDate: String,
        developer: String,
        publisher: String,
        genres: String,
        languages: String,
        lastPlayed: Long,
        playTime: Long,
        type: Int,
        exclude: Int,
        gogComHidden: Int,
        galaxyHidden: Int,
    ) {
        assertEquals(id, cursor.getString(0))
        assertEquals(title, cursor.getString(1))
        assertEquals(slug, cursor.getString(2))
        assertEquals(downloadSize, cursor.getLong(3))
        assertEquals(installSize, cursor.getLong(4))
        assertEquals(isInstalled, cursor.getInt(5))
        assertEquals(installPath, cursor.getString(6))
        assertEquals(imageUrl, cursor.getString(7))
        assertEquals(iconUrl, cursor.getString(8))
        assertEquals(backgroundUrl, cursor.getString(9))
        assertEquals(verticalCoverUrl, cursor.getString(10))
        assertEquals(description, cursor.getString(11))
        assertEquals(releaseDate, cursor.getString(12))
        assertEquals(developer, cursor.getString(13))
        assertEquals(publisher, cursor.getString(14))
        assertEquals(genres, cursor.getString(15))
        assertEquals(languages, cursor.getString(16))
        assertEquals(lastPlayed, cursor.getLong(17))
        assertEquals(playTime, cursor.getLong(18))
        assertEquals(type, cursor.getInt(19))
        assertEquals(exclude, cursor.getInt(20))
        assertEquals(gogComHidden, cursor.getInt(21))
        assertEquals(galaxyHidden, cursor.getInt(22))
    }

    companion object {
        private const val TEST_DB = "gog-hidden-games-migration"
    }
}
