package app.gamenative.service

import app.gamenative.data.SteamLicense
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.EnumSet

class SteamPackageSelectionTest {

    private fun makeLicense(
        packageId: Int,
        createdAtMs: Long,
        flags: EnumSet<ELicenseFlags> = EnumSet.of(ELicenseFlags.None),
        paymentMethod: EPaymentMethod = EPaymentMethod.CreditCard,
        licenseType: ELicenseType = ELicenseType.SinglePurchase,
    ) = SteamLicense(
        packageId = packageId,
        lastChangeNumber = 0,
        timeCreated = Date(createdAtMs),
        timeNextProcess = Date(createdAtMs),
        minuteLimit = 0,
        minutesUsed = 0,
        paymentMethod = paymentMethod,
        licenseFlags = flags,
        purchaseCode = "",
        licenseType = licenseType,
        territoryCode = 0,
        accessToken = 0L,
        ownerAccountId = listOf(1),
        masterPackageID = 0,
    )

    @Test
    fun `durable purchase beats active guest pass`() {
        val guestPass = makeLicense(
            packageId = 100,
            createdAtMs = 2_000L,
            paymentMethod = EPaymentMethod.GuestPass,
            licenseType = ELicenseType.SinglePurchaseLimitedUse,
        )
        val purchased = makeLicense(
            packageId = 200,
            createdAtMs = 1_000L,
            paymentMethod = EPaymentMethod.CreditCard,
            licenseType = ELicenseType.SinglePurchase,
        )

        val selected = SteamService.selectPreferredPackageId(
            existingPackageId = purchased.packageId,
            incomingPackageId = guestPass.packageId,
            licensesByPackageId = mapOf(
                guestPass.packageId to guestPass,
                purchased.packageId to purchased,
            ),
        )

        assertEquals(purchased.packageId, selected)
    }

    @Test
    fun `active temporary package beats expired purchase`() {
        val expiredPurchase = makeLicense(
            packageId = 100,
            createdAtMs = 1_000L,
            flags = EnumSet.of(ELicenseFlags.Expired),
            paymentMethod = EPaymentMethod.CreditCard,
            licenseType = ELicenseType.SinglePurchase,
        )
        val activeTemporary = makeLicense(
            packageId = 200,
            createdAtMs = 2_000L,
            paymentMethod = EPaymentMethod.GuestPass,
            licenseType = ELicenseType.SinglePurchaseLimitedUse,
        )

        val selected = SteamService.selectPreferredPackageId(
            existingPackageId = expiredPurchase.packageId,
            incomingPackageId = activeTemporary.packageId,
            licensesByPackageId = mapOf(
                expiredPurchase.packageId to expiredPurchase,
                activeTemporary.packageId to activeTemporary,
            ),
        )

        assertEquals(activeTemporary.packageId, selected)
    }

    @Test
    fun `newer durable purchase wins when both packages are durable`() {
        val olderPurchase = makeLicense(
            packageId = 100,
            createdAtMs = 1_000L,
        )
        val newerPurchase = makeLicense(
            packageId = 200,
            createdAtMs = 2_000L,
        )

        val selected = SteamService.selectPreferredPackageId(
            existingPackageId = olderPurchase.packageId,
            incomingPackageId = newerPurchase.packageId,
            licensesByPackageId = mapOf(
                olderPurchase.packageId to olderPurchase,
                newerPurchase.packageId to newerPurchase,
            ),
        )

        assertEquals(newerPurchase.packageId, selected)
    }

    @Test
    fun `package id breaks complete ties deterministically`() {
        val first = makeLicense(
            packageId = 100,
            createdAtMs = 1_000L,
        )
        val second = makeLicense(
            packageId = 200,
            createdAtMs = 1_000L,
        )

        val selected = SteamService.selectPreferredPackageId(
            existingPackageId = first.packageId,
            incomingPackageId = second.packageId,
            licensesByPackageId = mapOf(
                first.packageId to first,
                second.packageId to second,
            ),
        )

        assertEquals(second.packageId, selected)
    }
}
