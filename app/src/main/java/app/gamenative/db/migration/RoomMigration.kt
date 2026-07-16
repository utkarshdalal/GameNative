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

internal val ROOM_MIGRATION_V23_to_V24 = object : Migration(23, 24) {
    override fun migrate(connection: SQLiteConnection) {
        migrateNexusModSupportToV24(connection)
    }
}

private fun migrateNexusModSupportToV24(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `mod_profile` (
            `profile_id` TEXT NOT NULL,
            `app_id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `active` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`profile_id`)
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_profile_app_id` ON `mod_profile` (`app_id`)")
    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mod_profile_app_id_name` ON `mod_profile` (`app_id`, `name`)")

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `mod_install` (
            `install_id` TEXT NOT NULL,
            `app_id` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `nexus_game_domain` TEXT NOT NULL,
            `nexus_mod_id` INTEGER NOT NULL,
            `nexus_file_id` INTEGER NOT NULL,
            `mod_name` TEXT NOT NULL,
            `file_name` TEXT NOT NULL,
            `version` TEXT NOT NULL,
            `size_bytes` INTEGER NOT NULL,
            `archive_path` TEXT NOT NULL,
            `extracted_path` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `downloaded_at` INTEGER NOT NULL,
            `metadata_json` TEXT NOT NULL,
            PRIMARY KEY(`install_id`)
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_install_app_id` ON `mod_install` (`app_id`)")
    connection.execSQL("DROP INDEX IF EXISTS `index_mod_install_source_nexus_game_domain_nexus_mod_id_nexus_file_id`")
    connection.execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_mod_install_app_id_source_nexus_game_domain_nexus_mod_id_nexus_file_id`
        ON `mod_install` (`app_id`, `source`, `nexus_game_domain`, `nexus_mod_id`, `nexus_file_id`)
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `mod_profile_install_state` (
            `profile_id` TEXT NOT NULL,
            `install_id` TEXT NOT NULL,
            `app_id` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `priority` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`profile_id`, `install_id`),
            FOREIGN KEY(`profile_id`) REFERENCES `mod_profile`(`profile_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`install_id`) REFERENCES `mod_install`(`install_id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_profile_install_state_app_id` ON `mod_profile_install_state` (`app_id`)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_profile_install_state_install_id` ON `mod_profile_install_state` (`install_id`)")
    connection.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_mod_profile_install_state_app_id_profile_id_priority`
        ON `mod_profile_install_state` (`app_id`, `profile_id`, `priority`)
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `mod_placement_recipe` (
            `recipe_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `install_id` TEXT NOT NULL,
            `source_subpath` TEXT NOT NULL,
            `target_root` TEXT NOT NULL,
            `target_relative_path` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `strip_prefix_segments` INTEGER NOT NULL,
            `include_source_directory` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            FOREIGN KEY(`install_id`) REFERENCES `mod_install`(`install_id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_placement_recipe_install_id` ON `mod_placement_recipe` (`install_id`)")

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `mod_overwrite_manifest` (
            `manifest_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `install_id` TEXT NOT NULL,
            `target_path` TEXT NOT NULL,
            `backup_path` TEXT NOT NULL,
            `original_hash` TEXT NOT NULL,
            `original_size` INTEGER NOT NULL,
            `original_mtime` INTEGER NOT NULL,
            `installed_hash` TEXT NOT NULL,
            `installed_size` INTEGER NOT NULL,
            `installed_mtime` INTEGER NOT NULL,
            `timestamp` INTEGER NOT NULL,
            FOREIGN KEY(`install_id`) REFERENCES `mod_install`(`install_id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_overwrite_manifest_install_id` ON `mod_overwrite_manifest` (`install_id`)")
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_mod_overwrite_manifest_target_path` ON `mod_overwrite_manifest` (`target_path`)")
}
