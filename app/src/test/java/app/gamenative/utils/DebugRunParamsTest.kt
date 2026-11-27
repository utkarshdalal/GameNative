package app.gamenative.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugRunParamsTest {
    @Test
    fun `explicit trace channel replaces base warning level`() {
        val params = DebugRunParams.fromJson(
            JSONObject().put("winedebug", JSONArray().put("trace+seh")),
        )

        assertEquals(listOf("trace+seh"), params?.winedebug)
        assertEquals(
            "trace+seh,+loaddll,+process,+timestamp,+pid,+tid",
            DebugRunParams.mergeWineDebug(DebugRunParams.BASE_WINEDEBUG, params!!.winedebug),
        )
    }

    @Test
    fun `plain duplicate channel keeps base level`() {
        assertEquals(
            DebugRunParams.BASE_WINEDEBUG,
            DebugRunParams.mergeWineDebug(DebugRunParams.BASE_WINEDEBUG, listOf("+seh")),
        )
    }
}
