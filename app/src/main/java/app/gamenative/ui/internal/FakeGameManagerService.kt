package app.gamenative.ui.internal

import androidx.compose.runtime.staticCompositionLocalOf
import app.gamenative.data.GameSource
import app.gamenative.service.FakeGameManager
import app.gamenative.service.GameManagerService

class MockGameManagerServiceProvider {
    fun ensureInitialized() {
        // Initialize preview mode with the fake game manager
        GameManagerService.initializeForPreview(
            mapOf(GameSource.STEAM to FakeGameManager),
        )
    }
}

val LocalGameManagerService = staticCompositionLocalOf {
    MockGameManagerServiceProvider()
}
