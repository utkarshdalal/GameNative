package app.gamenative.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The manifest request code decides whether a depot downloads at all, so the three outcomes are
 * kept apart: a code, a definitive "Steam has none" (skip), and a failed request (still try).
 */
class ManifestRequestCodeTest {
    @Test
    fun theOwningAppsCodeWins() {
        assertEquals(11L, pickManifestRequestCode(listOf(1523210 to 11L, 409710 to 22L)))
    }

    @Test
    fun theDownloadingAppsCodeIsUsedWhenTheOwnerAnswersZero() {
        // BioShock Remastered's 1523211: the content app has no public branch, the game's does.
        assertEquals(22L, pickManifestRequestCode(listOf(1523210 to 0L, 409710 to 22L)))
    }

    @Test
    fun zeroFromEveryAppIsADefinitiveAbsence() {
        assertEquals(0L, pickManifestRequestCode(listOf(1523210 to 0L, 409710 to 0L)))
    }

    @Test
    fun aFailedRequestIsNotAnAbsence() {
        // null means "no app answered": skipping the depot here would drop it from the install.
        assertNull(pickManifestRequestCode(listOf(1523210 to null, 409710 to null)))
    }

    @Test
    fun aFailedOwnerStillAcceptsTheOtherAppsCode() {
        assertEquals(22L, pickManifestRequestCode(listOf(1523210 to null, 409710 to 22L)))
    }

    @Test
    fun aFailedRequestIsNotOverruledByAZeroElsewhere() {
        // The owner answered 0 but the other app could not be asked at all: not enough evidence to
        // skip the depot, which is exactly the silent drop this path guards against.
        assertNull(pickManifestRequestCode(listOf(1523210 to 0L, 409710 to null)))
        assertNull(pickManifestRequestCode(listOf(1523210 to null, 409710 to 0L)))
    }
}
