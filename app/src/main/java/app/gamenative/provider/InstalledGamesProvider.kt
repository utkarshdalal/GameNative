package app.gamenative.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.gamenative.BuildConfig
import app.gamenative.db.PluviaDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * Read-only ContentProvider that exposes the list of installed games to external apps.
 *
 * Authority: ${applicationId}.games (e.g., app.gamenative.games or app.gamenative.gold.games)
 * URI: content://${applicationId}.games/installed
 *
 * External apps can discover the provider via PackageManager.resolveContentProvider().
 */
class InstalledGamesProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DatabaseEntryPoint {
        fun database(): PluviaDatabase
    }

    companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.games"
        private const val PATH_INSTALLED = "installed"
        private const val CODE_INSTALLED = 1

        const val COLUMN_APP_ID = "app_id"
        const val COLUMN_NAME = "name"
        const val COLUMN_GAME_SOURCE = "game_source"
        const val COLUMN_ICON_REF = "icon_ref"
        const val COLUMN_IS_INSTALLED = "is_installed"

        private val COLUMNS = arrayOf(
            COLUMN_APP_ID,
            COLUMN_NAME,
            COLUMN_GAME_SOURCE,
            COLUMN_ICON_REF,
            COLUMN_IS_INSTALLED,
        )

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_INSTALLED, CODE_INSTALLED)
        }
    }

    private val database: PluviaDatabase by lazy {
        val appContext = context?.applicationContext
            ?: throw IllegalStateException("Context not available")
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            DatabaseEntryPoint::class.java,
        )
        entryPoint.database()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uriMatcher.match(uri) != CODE_INSTALLED) {
            Timber.w("[InstalledGamesProvider] Unknown URI: $uri")
            return null
        }

        val cols = if (projection.isNullOrEmpty()) COLUMNS else projection
        val cursor = MatrixCursor(cols as Array<String>)

        try {
            addSteamGames(cursor, cols)
            addGOGGames(cursor, cols)
            addEpicGames(cursor, cols)
            addAmazonGames(cursor, cols)
        } catch (e: Exception) {
            Timber.e(e, "[InstalledGamesProvider] Error querying installed games")
            return cursor
        }

        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    private fun addRow(
        cursor: MatrixCursor,
        cols: Array<String>,
        appId: Int,
        name: String,
        gameSource: String,
        iconRef: String,
        isInstalled: Int,
    ) {
        cursor.addRow(cols.map { col ->
            when (col) {
                COLUMN_APP_ID -> appId
                COLUMN_NAME -> name
                COLUMN_GAME_SOURCE -> gameSource
                COLUMN_ICON_REF -> iconRef
                COLUMN_IS_INSTALLED -> isInstalled
                else -> null
            }
        }.toTypedArray())
    }

    private fun addSteamGames(cursor: MatrixCursor, cols: Array<String>) {
        val db = database.openHelper.readableDatabase
        val sql = """
            SELECT s.id, s.name, s.client_icon_hash, a.is_downloaded
            FROM steam_app s
            INNER JOIN app_info a ON s.id = a.id
            WHERE s.type != 0 AND s.id != 480
              AND a.is_downloaded = 1
            ORDER BY s.name COLLATE NOCASE
        """.trimIndent()

        db.query(sql).use { c ->
            while (c.moveToNext()) {
                addRow(cursor, cols, c.getInt(0), c.getString(1), "STEAM", c.getString(2) ?: "", c.getInt(3))
            }
        }
    }

    private fun addGOGGames(cursor: MatrixCursor, cols: Array<String>) {
        val db = database.openHelper.readableDatabase
        val sql = """
            SELECT id, title, icon_url, is_installed
            FROM gog_games
            WHERE is_installed = 1 AND exclude = 0
            ORDER BY title COLLATE NOCASE
        """.trimIndent()

        db.query(sql).use { c ->
            while (c.moveToNext()) {
                val numericId = c.getString(0).toIntOrNull() ?: continue
                addRow(cursor, cols, numericId, c.getString(1), "GOG", c.getString(2) ?: "", c.getInt(3))
            }
        }
    }

    private fun addEpicGames(cursor: MatrixCursor, cols: Array<String>) {
        val db = database.openHelper.readableDatabase
        val sql = """
            SELECT id, title, art_square, is_installed
            FROM epic_games
            WHERE is_installed = 1 AND is_dlc = 0
            ORDER BY title COLLATE NOCASE
        """.trimIndent()

        db.query(sql).use { c ->
            while (c.moveToNext()) {
                addRow(cursor, cols, c.getInt(0), c.getString(1), "EPIC", c.getString(2) ?: "", c.getInt(3))
            }
        }
    }

    private fun addAmazonGames(cursor: MatrixCursor, cols: Array<String>) {
        val db = database.openHelper.readableDatabase
        val sql = """
            SELECT app_id, title, art_url, is_installed
            FROM amazon_games
            WHERE is_installed = 1
            ORDER BY title COLLATE NOCASE
        """.trimIndent()

        db.query(sql).use { c ->
            while (c.moveToNext()) {
                addRow(cursor, cols, c.getInt(0), c.getString(1), "AMAZON", c.getString(2) ?: "", c.getInt(3))
            }
        }
    }

    // Read-only provider — all write operations are unsupported.

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_INSTALLED -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_INSTALLED"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("This provider is read-only")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("This provider is read-only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("This provider is read-only")
    }
}
