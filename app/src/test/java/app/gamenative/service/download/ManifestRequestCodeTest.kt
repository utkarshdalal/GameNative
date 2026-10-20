package app.gamenative.service.download

import kotlinx.coroutines.runBlocking
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
    fun theFallbackAppIsNotAskedOnceTheOwnerIssuesACode() = runBlocking {
        val asked = mutableListOf<Int>()
        val resolved = firstManifestRequestCode(listOf(1523210, 409710)) { app ->
            asked += app
            if (app == 1523210) 11L else 22L
        }
        assertEquals(ManifestRequestCode(11L, 1523210), resolved)
        assertEquals(listOf(1523210), asked)
    }

    @Test
    fun theFallbackAppIsAskedWhenTheOwnerOnlyAnswersZeros() = runBlocking {
        val asked = mutableListOf<Int>()
        val resolved = firstManifestRequestCode(listOf(1523210, 409710)) { app ->
            asked += app
            if (app == 1523210) 0L else 22L
        }
        assertEquals(ManifestRequestCode(22L, 409710), resolved)
        assertEquals(listOf(1523210, 409710), asked)
    }

    @Test
    fun aFailedOwnerStillFallsBackToTheNextApp() = runBlocking {
        val asked = mutableListOf<Int>()
        val resolved = firstManifestRequestCode(listOf(1523210, 409710)) { app ->
            asked += app
            if (app == 1523210) null else 0L
        }
        // The owner's request failed, so the next app's 0 is not a definitive absence: the depot is
        // tried without a code rather than skipped.
        assertEquals(ManifestRequestCode(null, null), resolved)
        assertEquals(listOf(1523210, 409710), asked)
    }

    @Test
    fun everyRequestFailingEndsWithNoCodeAndNoApp() = runBlocking {
        val resolved = firstManifestRequestCode(listOf(1523210, 409710)) { null }
        assertNull(resolved.code)
        assertNull(resolved.from)
    }

    @Test
    fun aFailedRequestIsNotOverruledByAZeroElsewhere() {
        // The owner answered 0 but the other app could not be asked at all: not enough evidence to
        // skip the depot, which is exactly the silent drop this path guards against.
        assertNull(pickManifestRequestCode(listOf(1523210 to 0L, 409710 to null)))
        assertNull(pickManifestRequestCode(listOf(1523210 to null, 409710 to 0L)))
    }
}
