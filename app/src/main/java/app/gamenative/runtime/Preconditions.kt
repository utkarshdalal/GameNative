package app.gamenative.runtime

import androidx.annotation.VisibleForTesting
import com.winlator.container.Container

// reaching XServerScreen with a non-wine runtime is a wiring bug; fail loud. EXCEPT bootToContainer
// (Open Container), which deliberately shows html5 containers' wine prefix so users can browse saves.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
internal fun requireWineRuntime(container: Container, bootToContainer: Boolean = false) {
    if (bootToContainer) return
    require(container.runtime == WineRuntime.id) {
        "XServerScreen reached with non-wine runtime: ${container.runtime}"
    }
}
