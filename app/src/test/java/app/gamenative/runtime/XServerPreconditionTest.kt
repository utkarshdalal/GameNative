package app.gamenative.runtime

import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// precondition — defense-in-depth. XServerScreen is wine-only by contract.
// robolectric needed — mockk<Container> triggers Container.clinit which reads
// Environment.getExternalStoragePublicDirectory (matches ContainerRuntimeJsonTest pattern).
@RunWith(RobolectricTestRunner::class)
class XServerPreconditionTest {

    @Test
    fun wine_runtime_passes_precondition() {
        val container = mockk<Container>(relaxed = true)
        every { container.runtime } returns Container.RUNTIME_WINE
        // should not throw
        requireWineRuntime(container)
    }

    @Test
    fun webview_runtime_throws_illegal_argument() {
        val container = mockk<Container>(relaxed = true)
        every { container.runtime } returns Container.RUNTIME_WEBVIEW

        val ex = assertThrows(IllegalArgumentException::class.java) {
            requireWineRuntime(container)
        }
        assertTrue(ex.message!!.contains("non-wine runtime"))
        assertTrue(ex.message!!.contains("webview"))
    }

    @Test
    fun unknown_runtime_throws_illegal_argument() {
        val container = mockk<Container>(relaxed = true)
        every { container.runtime } returns "bogus-value"

        assertThrows(IllegalArgumentException::class.java) {
            requireWineRuntime(container)
        }
    }

    // Open Container menu route: any container variant + bootToContainer=true bypasses the runtime guard.
    @Test
    fun bootToContainer_true_allows_webview_runtime() {
        val container = mockk<Container>(relaxed = true)
        every { container.runtime } returns Container.RUNTIME_WEBVIEW
        // should not throw
        requireWineRuntime(container, bootToContainer = true)
    }
}
