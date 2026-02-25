package app.gamenative.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.gamenative.data.ChangeNumbers
import app.gamenative.data.AppInfo
import app.gamenative.data.FileChangeLists
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamLicense
import app.gamenative.data.CachedLicense
import app.gamenative.data.DownloadingAppInfo
import app.gamenative.data.EncryptedAppTicket
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.db.converters.AppConverter
import app.gamenative.db.converters.ByteArrayConverter
import app.gamenative.db.converters.FriendConverter
import app.gamenative.db.converters.LicenseConverter
import app.gamenative.db.converters.PathTypeConverter
import app.gamenative.db.converters.UserFileInfoListConverter
import app.gamenative.db.converters.GOGConverter
import app.gamenative.db.dao.ChangeNumbersDao
import app.gamenative.db.dao.FileChangeListsDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamLicenseDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao

const val DATABASE_NAME = "pluvia.db"

@Database(
    entities = [
        AppInfo::class,
        CachedLicense::class,
        ChangeNumbers::class,
        EncryptedAppTicket::class,
        FileChangeLists::class,
        SteamApp::class,
        SteamLicense::class,
        GOGGame::class,
        EpicGame::class,
        AmazonGame::class,
        DownloadingAppInfo::class
    ],
    version = 13,
    // For db migration, visit https://developer.android.com/training/data-storage/room/migrating-db-versions for more information
    exportSchema = true, // It is better to handle db changes carefully, as GN is getting much more users.
    autoMigrations = [
        // For every version change, if it is automatic, please add a new migration here.
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13), // Added amazon_games table
    ]
)
@TypeConverters(
    AppConverter::class,
    ByteArrayConverter::class,
    FriendConverter::class,
    LicenseConverter::class,
    PathTypeConverter::class,
    UserFileInfoListConverter::class,
    GOGConverter::class,
)
abstract class PluviaDatabase : RoomDatabase() {

    abstract fun steamLicenseDao(): SteamLicenseDao

    abstract fun steamAppDao(): SteamAppDao

    abstract fun appChangeNumbersDao(): ChangeNumbersDao

    abstract fun appFileChangeListsDao(): FileChangeListsDao

    abstract fun appInfoDao(): AppInfoDao

    abstract fun cachedLicenseDao(): CachedLicenseDao

    abstract fun encryptedAppTicketDao(): EncryptedAppTicketDao

    abstract fun gogGameDao(): GOGGameDao

    abstract fun epicGameDao(): EpicGameDao

    abstract fun amazonGameDao(): AmazonGameDao

    abstract fun downloadingAppInfoDao(): DownloadingAppInfoDao
}
