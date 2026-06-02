package app.gamenative.html5.host

import org.junit.Assert.assertTrue
import org.junit.Test

class AssetInterceptorWorkerStubTest {
    @Test fun synthesizesClassicImportScriptsPair() {
        val body = AssetInterceptor.synthesizeWorkerStubBody(
            orig = "/scripts/c3runtime.js",
            bundleUrl = "/_shims/worker-bundle.js",
            mode = "classic",
        )
        assertTrue("bundle import missing: $body", body.contains("importScripts(\"/_shims/worker-bundle.js\")"))
        assertTrue("orig import missing: $body", body.contains("importScripts(\"/scripts/c3runtime.js\")"))
        val bundleIdx = body.indexOf("/_shims/worker-bundle.js")
        val origIdx = body.indexOf("/scripts/c3runtime.js")
        assertTrue("bundle must load first: bundleIdx=$bundleIdx origIdx=$origIdx", bundleIdx < origIdx)
    }

    @Test fun synthesizesModuleAwaitImportPair() {
        val body = AssetInterceptor.synthesizeWorkerStubBody(
            orig = "/scripts/c3runtime.js",
            bundleUrl = "/_shims/worker-bundle.js",
            mode = "module",
        )
        assertTrue("await import bundle missing: $body", body.contains("await import(\"/_shims/worker-bundle.js\")"))
        assertTrue("await import orig missing: $body", body.contains("await import(\"/scripts/c3runtime.js\")"))
        // bundle MUST load before orig -- handshake order matters
        val bundleIdx = body.indexOf("worker-bundle.js")
        val origIdx = body.indexOf("c3runtime.js")
        assertTrue("bundle must precede orig: bundleIdx=$bundleIdx origIdx=$origIdx", bundleIdx < origIdx)
    }

    @Test fun escapesQuotesInOrig() {
        // a malicious orig must not break out of the string literal
        val body = AssetInterceptor.synthesizeWorkerStubBody(
            orig = "/scripts/foo.js\");evil(\"",
            bundleUrl = "/_shims/worker-bundle.js",
            mode = "classic",
        )
        assertTrue("must escape embedded quote in orig: $body", body.contains("\\\""))
    }
}
