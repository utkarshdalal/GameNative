package app.gamenative.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamLicense
import app.gamenative.db.PluviaDatabase
import app.gamenative.enums.AppType
import app.gamenative.service.SteamService.Companion.INVALID_PKG_ID
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import java.util.Date
import java.util.EnumSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamAppDaoTest {

    private lateinit var db: PluviaDatabase
    private lateinit var appDao: SteamAppDao
    private lateinit var licenseDao: SteamLicenseDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PluviaDatabase::class.java,
        ).allowMainThreadQueries().build()
        appDao = db.steamAppDao()
        licenseDao = db.steamLicenseDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // --- helpers ---

    private fun license(packageId: Int, appIds: List<Int> = emptyList(), expired: Boolean = false) = SteamLicense(
        packageId = packageId,
        lastChangeNumber = 0,
        timeCreated = Date(0),
        timeNextProcess = Date(0),
        minuteLimit = 0,
        minutesUsed = 0,
        paymentMethod = EPaymentMethod.None,
        licenseFlags = if (expired) ELicenseFlags.from(8) else EnumSet.noneOf(ELicenseFlags::class.java),
        purchaseCode = "",
        licenseType = ELicenseType.SinglePurchase,
        territoryCode = 0,
        accessToken = 0L,
        ownerAccountId = emptyList(),
        masterPackageID = 0,
        appIds = appIds,
    )

    private fun app(id: Int, packageId: Int, type: AppType = AppType.game) = SteamApp(
        id = id,
        packageId = packageId,
        type = type,
        name = "App $id",
        receivedPICS = type != AppType.invalid,
    )

    private fun ids(apps: List<SteamApp>) = apps.map { it.id }.toSet()

    // --- tests ---

    @Test
    fun `app with valid license listing its appId appears`() = runBlocking {
        licenseDao.insertAll(listOf(license(packageId = 100, appIds = listOf(1000))))
        appDao.insert(app(id = 1000, packageId = 100))

        val result = appDao.getAllOwnedApps().first()
        assertTrue(ids(result).contains(1000))
    }

    @Test
    fun `app whose license was deleted does not appear`() = runBlocking {
        // Insert license, then delete it (simulates expired free weekend)
        licenseDao.insertAll(listOf(license(packageId = 100, appIds = listOf(1000))))
        appDao.insert(app(id = 1000, packageId = 100))
        licenseDao.deleteStaleLicenses(listOf(100))

        val result = appDao.getAllOwnedApps().first()
        assertFalse(ids(result).contains(1000))
    }

    @Test
    fun `app with INVALID_PKG_ID does not appear`() = runBlocking {
        appDao.insert(app(id = 1000, packageId = INVALID_PKG_ID))

        val result = appDao.getAllOwnedApps().first()
        assertFalse(ids(result).contains(1000))
    }

    @Test
    fun `app with valid license but empty app_ids appears provisionally`() = runBlocking {
        // Package PICS not yet fetched — app_ids is still []
        licenseDao.insertAll(listOf(license(packageId = 100, appIds = emptyList())))
        appDao.insert(app(id = 1000, packageId = 100))

        val result = appDao.getAllOwnedApps().first()
        assertTrue(ids(result).contains(1000))
    }

    @Test
    fun `app with license listing only other appIds does not appear`() = runBlocking {
        licenseDao.insertAll(listOf(license(packageId = 100, appIds = listOf(9999))))
        appDao.insert(app(id = 1000, packageId = 100))

        val result = appDao.getAllOwnedApps().first()
        assertFalse(ids(result).contains(1000))
    }

    @Test
    fun `app with expired free weekend license does not appear`() = runBlocking {
        licenseDao.insertAll(listOf(license(packageId = 100, appIds = listOf(1000), expired = true)))
        appDao.insert(app(id = 1000, packageId = 100))

        val result = appDao.getAllOwnedApps().first()
        assertFalse(ids(result).contains(1000))
    }

    @Test
    fun `app appears when purchased after an expired free weekend`() = runBlocking {
        // package_id points to expired free weekend, but a separate purchase license also lists the app
        licenseDao.insertAll(listOf(
            license(packageId = 100, appIds = listOf(1000), expired = true),
            license(packageId = 200, appIds = listOf(1000)),
        ))
        appDao.insert(app(id = 1000, packageId = 100))

        val result = appDao.getAllOwnedApps().first()
        assertTrue(ids(result).contains(1000))
    }

    @Test
    fun `stub app with type invalid does not appear even with valid license`() = runBlocking {
        licenseDao.insertAll(listOf(license(packageId = 100, appIds = listOf(1000))))
        appDao.insert(app(id = 1000, packageId = 100, type = AppType.invalid))

        val result = appDao.getAllOwnedApps().first()
        assertFalse(ids(result).contains(1000))
    }
}
