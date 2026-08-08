package app.gamenative.html5.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TyranoSignature has three independent match arms — exercised separately here. integration
// (precedence vs ElectronSignature, fingerprint-result webRoot resolution) lives in
// EngineFingerprinterTest.
class TyranoSignatureTest {

    @Test fun matches_fileAnchorsAtRoot_glare1moreShape() {
        // Glare1more (CUSTOM_GAME_1265286292): Tyrano-on-NW.js single-exe. anchors at root.
        val ref = InMemoryDirectoryRef(
            setOf("tyrano/libs.js", "tyrano/tyrano.js", "data/system/Config.tjs"),
        )
        assertTrue(TyranoSignature.matches(ref))
        assertEquals("", TyranoSignature.webRootFor(ref))
    }

    @Test fun matches_fileAnchorsUnderResourcesApp_maisonChichigamiShape() {
        // Welcome to Maison Chichigami (2914480): Tyrano-on-Electron. anchors under
        // resources/app/. webRoot reflects the nested payload location.
        val ref = InMemoryDirectoryRef(
            setOf(
                "resources/app/tyrano/libs.js",
                "resources/app/tyrano/tyrano.js",
                "resources/app/data/system/Config.tjs",
            ),
        )
        assertTrue(TyranoSignature.matches(ref))
        assertEquals("resources/app", TyranoSignature.webRootFor(ref))
    }

    @Test fun matches_packageJsonDescriptionHintAtRoot() {
        // description metadata alone — for future titles whose file layout drifts but whose
        // Tyrano template description survives. NW.js-side hypothetical.
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json"),
            contents = mapOf(
                "package.json" to """{"name":"x","description":"TyranoScript｜ティラノスクリプト Ver6"}""",
            ),
        )
        assertTrue(TyranoSignature.matches(ref))
    }

    @Test fun matches_packageJsonDescriptionHintUnderResourcesApp() {
        // Maison Chichigami also matches this arm (its package.json contains "TyranoScript")
        // — defense in depth so a future title with renamed runtime files still routes
        // correctly as long as the description sticks.
        val ref = InMemoryDirectoryRef(
            entries = setOf("resources/app/package.json"),
            contents = mapOf(
                "resources/app/package.json" to """{"description":"TyranoScript Ver5"}""",
            ),
        )
        assertTrue(TyranoSignature.matches(ref))
        assertEquals("resources/app", TyranoSignature.webRootFor(ref))
    }

    @Test fun matches_packageJsonAdmZipDependency() {
        // adm-zip is Tyrano's `.tpatch` apply dependency — strong engine signal even without
        // a description string. matched at root.
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json"),
            contents = mapOf(
                "package.json" to """{"main":"main.js","dependencies":{"adm-zip":"^0.4.13","fs-extra":"^8"}}""",
            ),
        )
        assertTrue(TyranoSignature.matches(ref))
    }

    @Test fun matches_falseForRmmvShape() {
        val ref = InMemoryDirectoryRef(setOf("www/js/rpg_core.js", "www/data/System.json"))
        assertFalse(TyranoSignature.matches(ref))
    }

    @Test fun matches_falseForPlainElectronApp() {
        // electron app without Tyrano markers — no description hint, no adm-zip dep.
        val ref = InMemoryDirectoryRef(
            entries = setOf("resources/app/package.json"),
            contents = mapOf(
                "resources/app/package.json" to """{"name":"foo","main":"main.js"}""",
            ),
        )
        assertFalse(TyranoSignature.matches(ref))
    }

    @Test fun matches_falseForPlainNwjsApp() {
        // generic NW.js without Tyrano markers — package.json with main.html and no hints.
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json", "index.html"),
            contents = mapOf("package.json" to """{"main":"index.html","name":"foo"}"""),
        )
        assertFalse(TyranoSignature.matches(ref))
    }

    @Test fun webRootFor_prefersRootWhenAnchorsAtBothLevels() {
        // pathological: anchors at BOTH root AND resources/app/. root wins (matches the way
        // the file-anchor arm is checked first within matchesAtPrefix("")).
        val ref = InMemoryDirectoryRef(
            setOf(
                "tyrano/libs.js",
                "tyrano/tyrano.js",
                "data/system/Config.tjs",
                "resources/app/tyrano/libs.js",
                "resources/app/tyrano/tyrano.js",
                "resources/app/data/system/Config.tjs",
            ),
        )
        assertEquals("", TyranoSignature.webRootFor(ref))
    }
}
