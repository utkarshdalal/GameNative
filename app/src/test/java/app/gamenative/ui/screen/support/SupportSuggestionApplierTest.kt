package app.gamenative.ui.screen.support

import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportSuggestionApplierTest {

    @Test
    fun rejectsArm64ecWineForGlibcContainer() {
        assertEquals(
            "ARM64EC Wine requires a bionic container",
            SupportSuggestionApplier.runtimeCompatibilityError(
                Container.GLIBC,
                "proton-10.0-arm64ec-2",
            ),
        )
    }

    @Test
    fun acceptsArm64ecWineForBionicContainer() {
        assertNull(
            SupportSuggestionApplier.runtimeCompatibilityError(
                Container.BIONIC,
                "proton-10.0-arm64ec-2",
            ),
        )
    }

    @Test
    fun acceptsX8664WineForGlibcContainer() {
        assertNull(
            SupportSuggestionApplier.runtimeCompatibilityError(
                Container.GLIBC,
                "wine-9.2-x86_64",
            ),
        )
    }
}
