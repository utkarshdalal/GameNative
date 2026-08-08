package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Test

// EXECUTES os.js under Rhino. the win32 identity is load-bearing: HTML5 titles run the WINDOWS
// NW.js distribution (project_html5_windows_nwjs_posture), so os.platform()/homedir()/EOL pin
// platform-conditional save-path composition onto the Windows branch where cloud sync watches.
class ShimOsExecTest {
    @Test
    fun reports_win32_identity_by_default() {
        ShimJsRuntime().load("require-dispatcher.js").load("os.js").use { js ->
            js.eval("var os = window.require('os');")
            assertEquals("win32", js.evalString("os.platform()"))
            assertEquals("Windows_NT", js.evalString("os.type()"))
            assertEquals("x64", js.evalString("os.arch()"))
            assertEquals("C:/users/xuser", js.evalString("os.homedir()"))
            assertEquals("C:/users/xuser/AppData/Local/Temp", js.evalString("os.tmpdir()"))
            // EOL must be CRLF on the windows branch -- some title save serializers split on it.
            assertEquals("\r\n", js.evalString("os.EOL"))
        }
    }

    @Test
    fun platform_override_flips_to_linux_branch() {
        // self.__gnPlatform is the escape hatch; set BEFORE load so EOL (computed at load) follows.
        ShimJsRuntime().load("require-dispatcher.js").use { js ->
            js.eval("self.__gnPlatform = 'linux';")
            js.load("os.js")
            js.eval("var os = window.require('os');")
            assertEquals("linux", js.evalString("os.platform()"))
            assertEquals("Linux", js.evalString("os.type()"))
            assertEquals("/tmp", js.evalString("os.tmpdir()"))
            assertEquals("\n", js.evalString("os.EOL"))
        }
    }
}
