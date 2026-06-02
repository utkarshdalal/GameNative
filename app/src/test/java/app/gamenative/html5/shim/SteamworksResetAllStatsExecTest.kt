package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Test

// EXECUTES steamworks.js under Rhino. c3's greenworks DOM handler posts resetAllStats' return value
// through a MessagePort; the Proxy fallback's function fails structured clone there.
class SteamworksResetAllStatsExecTest {
    @Test
    fun resetAllStats_returns_plain_bool_and_forwards_flag() {
        ShimJsRuntime().installBase64().installProxyShim().use { js ->
            js.eval(
                """
                var __gnResetCalls = [];
                var __gnSteamworksBridge = {
                    getInboundCloudJson: function () { return '{}'; },
                    resetAllStats: function (achievementsToo) { __gnResetCalls.push(achievementsToo); return true; },
                };
                """.trimIndent(),
            )
            js.load("require-dispatcher.js").load("steamworks.js")
            assertEquals("boolean", js.evalString("typeof window.greenworks.resetAllStats(1)"))
            assertEquals("true", js.evalString("String(__gnResetCalls[0])"))
        }
    }
}
