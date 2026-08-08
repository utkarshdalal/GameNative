package app.gamenative.html5.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// EXECUTES node-globals.js + yaml-bridge.js under Rhino. node-globals completes the Node-style
// boot env (__dirname/__filename) any title's bare-name path math reads; yaml-bridge routes the
// bundled js-yaml UMD through require() for OMORI-style plugins. both are thin delegation -- the
// risk is a broken idempotency guard or a regressed require-id pattern, which these lock.
class ShimNodeGlobalsYamlExecTest {

    @Test
    fun node_globals_set_dirname_and_filename() {
        ShimJsRuntime().load("node-globals.js").use { js ->
            assertEquals("/", js.evalString("window.__dirname"))
            assertEquals("/index.html", js.evalString("window.__filename"))
        }
    }

    @Test
    fun node_globals_are_idempotent() {
        // NW.js/Electron may inject real __dirname per <script>; a pre-set value must survive.
        ShimJsRuntime().use { js ->
            js.eval("window.__dirname = '/already/set';")
            js.load("node-globals.js")
            assertEquals("/already/set", js.evalString("window.__dirname"))
        }
    }

    @Test
    fun yaml_bridge_registers_conventional_ids() {
        ShimJsRuntime().load("require-dispatcher.js").use { js ->
            js.eval("window.jsyaml = { version: '3.14.1', load: function () {}, safeLoad: function () {} };")
            js.load("yaml-bridge.js")
            assertTrue(js.evalBoolean("window.require('js-yaml') === window.jsyaml"))
            assertTrue(js.evalBoolean("window.require('yaml') === window.jsyaml"))
        }
    }

    @Test
    fun yaml_bridge_pattern_matches_master_folder_requires() {
        ShimJsRuntime().load("require-dispatcher.js").use { js ->
            js.eval("window.jsyaml = { version: '3.14.1' };")
            js.load("yaml-bridge.js")
            // NW.js relative-folder require forms, with and without ./ prefix and trailing slash.
            assertTrue(js.evalBoolean("window.require('./js/libs/js-yaml-master') === window.jsyaml"))
            assertTrue(js.evalBoolean("window.require('../libs/js-yaml-master/') === window.jsyaml"))
        }
    }

    @Test
    fun yaml_bridge_bails_without_jsyaml() {
        // js-yaml.min.js failed to load -> bridge must NOT register a bogus module.
        ShimJsRuntime().load("require-dispatcher.js").use { js ->
            js.load("yaml-bridge.js")
            assertFalse(js.evalBoolean("typeof window.require('js-yaml') !== 'undefined'"))
        }
    }
}
