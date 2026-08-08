package app.gamenative.html5.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-jvm — no android deps. mirrors EngineFingerprinterTest patterns.
// 1 existence-only match, no asar cracking at fingerprint time.
class ElectronSignatureTest {

    @Test fun matches_trueForAppAsar() {
        val ref = InMemoryDirectoryRef(setOf("resources/app.asar"))
        assertTrue(ElectronSignature.matches(ref))
    }

    @Test fun matches_trueForElectronAsar() {
        val ref = InMemoryDirectoryRef(setOf("resources/electron.asar"))
        assertTrue(ElectronSignature.matches(ref))
    }

    @Test fun matches_trueForBothAsars() {
        val ref = InMemoryDirectoryRef(setOf("resources/app.asar", "resources/electron.asar"))
        assertTrue(ElectronSignature.matches(ref))
    }

    @Test fun matches_falseForWronglyNestedAsar() {
        // must be under resources/ — bare app.asar at root does NOT match (SPEC Req #1).
        val ref = InMemoryDirectoryRef(setOf("app.asar"))
        assertFalse(ElectronSignature.matches(ref))
    }

    @Test fun matches_falseForRmmvShape() {
        val ref = InMemoryDirectoryRef(setOf("www/js/rpg_core.js", "www/data/System.json"))
        assertFalse(ElectronSignature.matches(ref))
    }

    @Test fun engineId_isPackElectron() {
        assertEquals("pack:electron", ElectronSignature.engineId)
    }

    @Test fun webRoot_isEmptyString() {
        // electron asar-rooted; no www/ indirection like RMMV has. webRoot=""
        assertEquals("", ElectronSignature.webRoot)
    }

    @Test fun matches_unpackedAppPackageJson_isElectron() {
        // Cookie Clicker-shape: resources/app/package.json present. existence-only — Tyrano
        // discrimination happens upstream via TyranoSignature precedence, not here.
        val ref = InMemoryDirectoryRef(setOf("resources/app/package.json"))
        assertTrue(ElectronSignature.matches(ref))
    }
}
