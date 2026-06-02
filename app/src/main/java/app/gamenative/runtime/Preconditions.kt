package app.gamenative.runtime

import androidx.annotation.VisibleForTesting
import com.winlator.container.Container

// testable precondition -- extracted so unit tests don't need a composable host
// misrouting to XServerScreen with non-wine runtime is a wiring bug; fail loud.
// EXCEPT: bootToContainer=true is the Open Container menu, which deliberately routes
// any container variant (including html5) through the wine file-manager view so users
// can browse A: drive + saves. wine prefix exists for html5 containers via save-sync.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
internal fun requireWineRuntime(container: Container, bootToContainer: Boolean = false) {
    if (bootToContainer) return
    require(container.runtime == WineRuntime.id) {
        "XServerScreen reached with non-wine runtime: ${container.runtime}"
    }
}
