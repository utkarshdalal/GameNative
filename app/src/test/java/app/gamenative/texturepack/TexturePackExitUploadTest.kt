package app.gamenative.texturepack

import app.gamenative.texturepack.TexturePackGate.ExitUploadAction
import org.junit.Assert.assertEquals
import org.junit.Test

class TexturePackExitUploadTest {

    @Test
    fun normalExitAlwaysPrompts() {
        for (sync in listOf(true, false)) {
            for (sources in listOf(true, false)) {
                assertEquals(ExitUploadAction.PROMPT, TexturePackGate.exitUploadAction(false, sync, sources))
            }
        }
    }

    @Test
    fun intentExitEnqueuesWhenSyncEnabledWithSources() {
        assertEquals(ExitUploadAction.ENQUEUE, TexturePackGate.exitUploadAction(true, true, true))
    }

    @Test
    fun intentExitDoesNothingWithoutSyncOrSources() {
        assertEquals(ExitUploadAction.NONE, TexturePackGate.exitUploadAction(true, false, true))
        assertEquals(ExitUploadAction.NONE, TexturePackGate.exitUploadAction(true, true, false))
        assertEquals(ExitUploadAction.NONE, TexturePackGate.exitUploadAction(true, false, false))
    }
}
