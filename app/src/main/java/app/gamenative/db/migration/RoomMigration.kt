package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val DROP_TABLE = "DROP TABLE IF EXISTS " // Trailing Space

internal val ROOM_MIGRATION_V7_to_V8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Dec 5, 2025: Friends and Chat features removed
        connection.execSQL(DROP_TABLE + "chat_message")
        connection.execSQL(DROP_TABLE + "emoticon")
        connection.execSQL(DROP_TABLE + "steam_friend")
    }
}

internal val ROOM_MIGRATION_V16_to_V17 = object : Migration(16, 17) {
    override fun migrate(connection: SQLiteConnection) {
        // No-op: v16 and v17 schemas are identical.
        // AutoMigration was disabled because Room incorrectly tried to re-add
        // the ufs_parse_version column that already existed (upstream PR #1048).
    }
}
