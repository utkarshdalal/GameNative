package app.gamenative.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.gamenative.db.DATABASE_NAME
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.ModDao
import app.gamenative.db.dao.SteamUnlockedBranchDao
import app.gamenative.db.migration.ROOM_MIGRATION_V23_to_V24
import app.gamenative.db.migration.ROOM_MIGRATION_V7_to_V8
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PluviaDatabase {
        // The db will be considered unstable during development.
        // Once stable we should add a (room) db migration
        return Room.databaseBuilder(context, PluviaDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                ROOM_MIGRATION_V7_to_V8,
                ROOM_MIGRATION_V23_to_V24,
            )
            .fallbackToDestructiveMigration(true)
            // Use unbounded thread pools for reads/writes so a long-running query (e.g. the
            // library COUNT/paged load during a large PICS sync) can't starve the default
            // single-threaded executors and deadlock the connection pool. SQLite still
            // serializes writers, so this only removes the scheduling deadlock.
            .setQueryExecutor(Executors.newCachedThreadPool())
            .setTransactionExecutor(Executors.newCachedThreadPool())
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // These indexes are not declared in @Entity (which would require a schema
                    // migration) — they're created here so we can add them without a version bump
                    // while keeping upstream compatibility. IF NOT EXISTS makes them idempotent.
                    //
                    // dlc_for_app_id: the OWNED_APPS_WHERE DLC EXISTS arm joins back into
                    // steam_app on this column. Without an index, SQLite scans all rows per
                    // outer row that fails the direct-license check, turning the COUNT(*) query
                    // O(N²) and causing 100+ second runtimes that exhaust the connection pool.
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_steam_app_dlc_for_app_id " +
                            "ON steam_app(dlc_for_app_id)",
                    )
                    // package_id: used in the WHERE clause to exclude INVALID_PKG_ID rows and
                    // as the lookup key for the direct-license EXISTS arm.
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_steam_app_package_id " +
                            "ON steam_app(package_id)",
                    )
                }
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideSteamLicenseDao(db: PluviaDatabase) = db.steamLicenseDao()

    @Provides
    @Singleton
    fun provideSteamAppDao(db: PluviaDatabase) = db.steamAppDao()

    @Provides
    @Singleton
    fun provideSteamFileHashCacheDao(db: PluviaDatabase) = db.steamFileHashCacheDao()

    @Provides
    @Singleton
    fun provideAppChangeNumbersDao(db: PluviaDatabase) = db.appChangeNumbersDao()

    @Provides
    @Singleton
    fun provideAppFileChangeListsDao(db: PluviaDatabase) = db.appFileChangeListsDao()

    @Provides
    @Singleton
    fun provideLibraryPlayHistoryDao(db: PluviaDatabase): LibraryPlayHistoryDao = db.libraryPlayHistoryDao()

    @Provides
    @Singleton
    fun provideAppInfoDao(db: PluviaDatabase): AppInfoDao = db.appInfoDao()

    @Provides
    @Singleton
    fun provideCachedLicenseDao(db: PluviaDatabase): CachedLicenseDao = db.cachedLicenseDao()

    @Provides
    @Singleton
    fun provideEncryptedAppTicketDao(db: PluviaDatabase): EncryptedAppTicketDao = db.encryptedAppTicketDao()

    @Provides
    @Singleton
    fun provideGOGGameDao(db: PluviaDatabase) = db.gogGameDao()

    @Provides
    @Singleton
    fun provideEpicGameDao(db: PluviaDatabase) = db.epicGameDao()

    @Provides
    @Singleton
    fun provideAmazonGameDao(db: PluviaDatabase) = db.amazonGameDao()

    @Provides
    @Singleton
    fun provideDownloadingAppInfoDao(db: PluviaDatabase): DownloadingAppInfoDao = db.downloadingAppInfoDao()

    @Provides
    @Singleton
    fun provideSteamUnlockedBranchDao(db: PluviaDatabase): SteamUnlockedBranchDao = db.steamUnlockedBranchDao()

    @Provides
    @Singleton
    fun provideModDao(db: PluviaDatabase): ModDao = db.modDao()
}
