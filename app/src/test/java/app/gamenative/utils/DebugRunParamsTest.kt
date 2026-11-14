package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRunParamsTest {
    @Test
    fun manualWineDebugSelectionIsNormalizedAndDeduplicated() {
        val params = DebugRunParams.fromManualWineDebug(
            enabled = true,
            selection = "winsock, steamclient, WINSOCK",
        )!!

        assertEquals(listOf("+winsock", "+steamclient"), params.winedebug)
        assertEquals(
            "warn+seh,+loaddll,+process,+timestamp,+pid,+tid,+winsock,+steamclient",
            DebugRunParams.mergeWineDebug(DebugRunParams.BASE_WINEDEBUG, params.winedebug),
        )
        assertEquals("+winsock", params.toJson().getJSONArray("channels").getString(0))
    }

    @Test
    fun manualWineDebugSelectionRequiresEnabledPreferenceAndValidChannels() {
        assertNull(DebugRunParams.fromManualWineDebug(false, "winsock"))
        assertNull(DebugRunParams.fromManualWineDebug(true, " , invalid channel"))
        assertTrue(DebugRunParams.fromManualWineDebug(true, "+winsock")!!.winedebug.contains("+winsock"))
    }
}
