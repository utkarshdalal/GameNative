package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V16_to_V17
import app.gamenative.db.migration.ROOM_MIGRATION_V23_to_V24
import app.gamenative.db.migration.ROOM_MIGRATION_V24_to_V25
import app.gamenative.db.migration.ROOM_MIGRATION_V26_to_V27
import app.gamenative.db.migration.ROOM_MIGRATION_V28_to_V29
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V16UpgradeMigrationAndroidTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
    )

    @Test
    fun migrate16To17_isNoOp() {
        helper.createDatabase(DATABASE_NAME, 16).close()

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            17,
            true,
            ROOM_MIGRATION_V16_to_V17,
        ).close()
    }

    @Test
    fun migrate16ToLatest() {
        helper.createDatabase(DATABASE_NAME, 16).close()

        // Auto-migrations declared on PluviaDatabase are picked up by the helper.
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            LATEST_VERSION,
            true,
            ROOM_MIGRATION_V16_to_V17,
            ROOM_MIGRATION_V23_to_V24,
            ROOM_MIGRATION_V24_to_V25,
            ROOM_MIGRATION_V26_to_V27,
            ROOM_MIGRATION_V28_to_V29,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "v16-upgrade"
        const val LATEST_VERSION = 29
    }
}
