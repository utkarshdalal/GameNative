package app.gamenative.db.dao

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.EnumSet

@RunWith(RobolectricTestRunner::class)
class SteamAppDaoTest {

    private lateinit var db: PluviaDatabase
    private lateinit var appDao: SteamAppDao
    private lateinit var licenseDao: SteamLicenseDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        appDao = db.steamAppDao()
        licenseDao = db.steamLicenseDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeLicense(
        packageId: Int,
        flags: EnumSet<ELicenseFlags> = EnumSet.of(ELicenseFlags.None),
        appIds: List<Int> = emptyList(),
    ) = SteamLicense(
        packageId = packageId,
        lastChangeNumber = 0,
        timeCreated = Date(),
        timeNextProcess = Date(),
        minuteLimit = 0,
        minutesUsed = 0,
        paymentMethod = EPaymentMethod.None,
        licenseFlags = flags,
        purchaseCode = "",
        licenseType = ELicenseType.SinglePurchase,
        territoryCode = 0,
        accessToken = 0L,
        ownerAccountId = listOf(1),
        masterPackageID = 0,
        appIds = appIds,
    )

    private fun makeApp(id: Int, packageId: Int, type: AppType = AppType.game) =
        SteamApp(id = id, packageId = packageId, type = type, name = "App $id")

    @Test
    fun `valid license - app appears in library`() = runBlocking {
        licenseDao.insertAll(listOf(makeLicense(packageId = 100, appIds = listOf(1))))
        appDao.insert(makeApp(id = 1, packageId = 100))

        val apps = appDao.getAllOwnedApps().first()
        assertEquals(1, apps.size)
        assertEquals(1, apps[0].id)
    }

    @Test
    fun `expired license - app excluded from library`() = runBlocking {
        licenseDao.insertAll(listOf(makeLicense(
            packageId = 100,
            flags = EnumSet.of(ELicenseFlags.Expired),
            appIds = listOf(1),
        )))
        appDao.insert(makeApp(id = 1, packageId = 100))

        val apps = appDao.getAllOwnedApps().first()
        assertTrue("expired license app should not appear", apps.isEmpty())
    }

    @Test
    fun `no matching license - app excluded`() = runBlocking {
        // app with valid package_id but no license row
        appDao.insert(makeApp(id = 1, packageId = 999))

        val apps = appDao.getAllOwnedApps().first()
        assertTrue("app without license should not appear", apps.isEmpty())
    }

    @Test
    fun `invalid package_id - app excluded`() = runBlocking {
        appDao.insert(makeApp(id = 1, packageId = INVALID_PKG_ID))

        val apps = appDao.getAllOwnedApps().first()
        assertTrue("app with INVALID_PKG_ID should not appear", apps.isEmpty())
    }

    @Test
    fun `type 0 (invalid) - app excluded`() = runBlocking {
        licenseDao.insertAll(listOf(makeLicense(packageId = 100, appIds = listOf(1))))
        appDao.insert(makeApp(id = 1, packageId = 100, type = AppType.invalid))

        val apps = appDao.getAllOwnedApps().first()
        assertTrue("invalid type app should not appear", apps.isEmpty())
    }

    @Test
    fun `spacewar (480) - excluded`() = runBlocking {
        licenseDao.insertAll(listOf(makeLicense(packageId = 100, appIds = listOf(480))))
        appDao.insert(makeApp(id = 480, packageId = 100))

        val apps = appDao.getAllOwnedApps().first()
        assertTrue("Spacewar should not appear", apps.isEmpty())
    }

    @Test
    fun `multiple apps same license - all appear`() = runBlocking {
        licenseDao.insertAll(listOf(makeLicense(packageId = 100, appIds = listOf(1, 2, 3))))
        appDao.insert(makeApp(id = 1, packageId = 100))
        appDao.insert(makeApp(id = 2, packageId = 100))
        appDao.insert(makeApp(id = 3, packageId = 100))

        val apps = appDao.getAllOwnedApps().first()
        assertEquals(3, apps.size)
    }

    @Test
    fun `mixed valid and expired licenses - only valid appear`() = runBlocking {
        licenseDao.insertAll(listOf(
            makeLicense(packageId = 100, appIds = listOf(1)),
            makeLicense(packageId = 200, flags = EnumSet.of(ELicenseFlags.Expired), appIds = listOf(2)),
        ))
        appDao.insert(makeApp(id = 1, packageId = 100))
        appDao.insert(makeApp(id = 2, packageId = 200))

        val apps = appDao.getAllOwnedApps().first()
        assertEquals(1, apps.size)
        assertEquals(1, apps[0].id)
    }

    @Test
    fun `expired combined with other flags - still excluded`() = runBlocking {
        // expired + renew flags together
        licenseDao.insertAll(listOf(makeLicense(
            packageId = 100,
            flags = EnumSet.of(ELicenseFlags.Expired, ELicenseFlags.Renew),
            appIds = listOf(1),
        )))
        appDao.insert(makeApp(id = 1, packageId = 100))

        val apps = appDao.getAllOwnedApps().first()
        assertTrue("expired+renew license should not appear", apps.isEmpty())
    }

    @Test
    fun `non-expired flags - app appears`() = runBlocking {
        // renew flag without expired
        licenseDao.insertAll(listOf(makeLicense(
            packageId = 100,
            flags = EnumSet.of(ELicenseFlags.Renew),
            appIds = listOf(1),
        )))
        appDao.insert(makeApp(id = 1, packageId = 100))

        val apps = appDao.getAllOwnedApps().first()
        assertEquals(1, apps.size)
    }

    @Test
    fun `results ordered by name case-insensitive`() = runBlocking {
        licenseDao.insertAll(listOf(makeLicense(packageId = 100, appIds = listOf(1, 2, 3))))
        appDao.insert(SteamApp(id = 1, packageId = 100, type = AppType.game, name = "Zelda"))
        appDao.insert(SteamApp(id = 2, packageId = 100, type = AppType.game, name = "alpha"))
        appDao.insert(SteamApp(id = 3, packageId = 100, type = AppType.game, name = "Beta"))

        val apps = appDao.getAllOwnedApps().first()
        assertEquals(listOf("alpha", "Beta", "Zelda"), apps.map { it.name })
    }
}
