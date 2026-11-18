package app.gamenative.utils

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinComponentInstallTest {
    @Test
    fun placedFilesAreReported() = runBlocking {
        assertTrue(WinComponentInstall.attempt("vcrun2022") { true })
    }

    @Test
    fun anExtractionThatPlacedNothingIsNotPlaced() = runBlocking {
        assertFalse(WinComponentInstall.attempt("vcrun2022") { false })
    }

    @Test
    fun aFailedDownloadDoesNotThrow() = runBlocking {
        var calls = 0
        val placed = WinComponentInstall.attempt("vcrun2012") {
            calls++
            throw IOException("Failed to download wincomponents/vcrun2012.tzst")
        }

        assertFalse(placed)
        assertEquals(1, calls)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotSwallowed() {
        runBlocking { WinComponentInstall.attempt("vcrun2022") { throw CancellationException("launch cancelled") } }
    }

    @Test
    fun appliedValueKeepsTheRequestWhenNothingFailed() {
        val requested = "direct3d=1,vcrun2010=1,vcrun2022=1"

        assertEquals(requested, WinComponentInstall.appliedValue(requested, "direct3d=1", emptySet()))
    }

    @Test
    fun appliedValueRestoresThePreviousValueOfAFailedComponent() {
        assertEquals(
            "direct3d=1,vcrun2010=0,vcrun2022=1",
            WinComponentInstall.appliedValue("direct3d=1,vcrun2010=1,vcrun2022=1", "direct3d=1,vcrun2010=0", setOf("vcrun2010")),
        )
    }

    @Test
    fun appliedValueDropsAFailedComponentThePreviousStateDidNotHave() {
        assertEquals(
            "direct3d=1,vcrun2022=1",
            WinComponentInstall.appliedValue("direct3d=1,vcrun2012=1,vcrun2022=1", "direct3d=1,vcrun2022=0", setOf("vcrun2012")),
        )
    }

    @Test
    fun appliedValueDropsAFailedComponentWhosePreviousValueMatchesTheRequest() {
        val fallback = "direct3d=1,directsound=1,directinput8=0,directinput=0,directmusic=1,directshow=1,directplay=1,vcrun2010=1,wmdecoder=1,opengl=0"

        val applied = WinComponentInstall.appliedValue(fallback + ",vcrun2005=1", fallback, setOf("vcrun2010", "vcrun2005"))

        assertEquals(
            "direct3d=1,directsound=1,directinput8=0,directinput=0,directmusic=1,directshow=1,directplay=1,wmdecoder=1,opengl=0",
            applied,
        )
    }
}
