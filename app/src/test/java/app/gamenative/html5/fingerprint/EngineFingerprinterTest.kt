package app.gamenative.html5.fingerprint

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// pure-jvm unit tests (no Robolectric, no Android imports) — DirectoryRef/JavaFileDirectoryRef
// tests live here alongside fingerprint tests per guidance (single test file).
class EngineFingerprinterTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    // ---------- DirectoryRef / JavaFileDirectoryRef / InMemoryDirectoryRef ----------

    @Test
    fun javaFileDirectoryRef_exists_returnsTrueForExistingNestedFile() {
        val nested = tempFolder.newFolder("a", "b")
        java.io.File(nested, "c.txt").writeText("hi")
        val ref = JavaFileDirectoryRef(tempFolder.root)
        assertTrue(ref.exists("a/b/c.txt"))
    }

    @Test
    fun javaFileDirectoryRef_exists_returnsFalseForMissingPath() {
        val ref = JavaFileDirectoryRef(tempFolder.root)
        assertFalse(ref.exists("does/not/exist.txt"))
    }

    @Test
    fun javaFileDirectoryRef_listFiles_returnsChildNamesNotAbsolutePaths() {
        val sub = tempFolder.newFolder("sub")
        java.io.File(sub, "one.txt").writeText("1")
        java.io.File(sub, "two.txt").writeText("2")
        val ref = JavaFileDirectoryRef(tempFolder.root)
        val names = ref.listFiles("sub").toSet()
        assertEquals(setOf("one.txt", "two.txt"), names)
    }

    @Test
    fun javaFileDirectoryRef_listFiles_onNonexistentPath_returnsEmptyList() {
        val ref = JavaFileDirectoryRef(tempFolder.root)
        assertEquals(emptyList<String>(), ref.listFiles("nonexistent"))
    }

    @Test
    fun inMemoryDirectoryRef_exists_worksForDirectAndDirectoryEntries() {
        val ref = InMemoryDirectoryRef(setOf("www/js/rpg_core.js", "www/data/System.json"))
        assertTrue(ref.exists("www/js/rpg_core.js"))
        assertTrue(ref.exists("www")) // directory inferred from child prefixes
        assertFalse(ref.exists("nope"))
    }

    // ---------- fingerprint() behavior ----------

    @Test
    fun fingerprint_rmmvShapedTree_matchesAsPackRmmv() {
        val ref = InMemoryDirectoryRef(
            setOf(
                "www/js/rpg_core.js",
                "www/data/System.json",
                "www/index.html",
            ),
        )
        val result = fingerprint(ref)
        // RmmvSignature.webRoot="www", single-anchor → confidence=80.
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_rmmzShapedTree_matchesAsPackRmmvTooPerD08() {
        // MZ drops the www/ indirection but shares the SAME pack id 
        val ref = InMemoryDirectoryRef(
            setOf(
                "js/rmmz_core.js",
                "data/System.json",
                "index.html",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(FingerprintResult.Matched(engine = "pack:rmmv"), result)
    }

    @Test
    fun fingerprint_c3ShapedTree_matchesAsPackC3() {
        val ref = InMemoryDirectoryRef(
            setOf(
                "scripts/c3runtime.js",
                "index.html",
            ),
        )
        val result = fingerprint(ref)
        // ConstructThreeSignature single-anchor → confidence=80.
        assertEquals(FingerprintResult.Matched(engine = "pack:c3", confidence = 80), result)
    }

    @Test
    fun fingerprint_emptyTree_isUnknown() {
        val ref = InMemoryDirectoryRef(emptySet())
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_rmmvWithRpgCoreOnly_matches() {
        // www/js/rpg_core.js is the unambiguous RMMV marker — no other engine ships that filename.
        // before we AND'd System.json as a sanity pair, but custom-encrypted titles
        // (OMORI ships www/data/System.KEL not System.json) broke under the stricter rule.
        val ref = InMemoryDirectoryRef(setOf("www/js/rpg_core.js"))
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            fingerprint(ref),
        )
    }

    @Test
    fun fingerprint_omoriShape_rpgCorePlusEncryptedSystem_matches() {
        // OMORI canonical shape: www/js/rpg_core.js + www/data/System.KEL (custom-encrypted).
        // exists() never sees System.KEL — RmmvSignature only checks rpg_core.js — but lock the
        // real-world fixture so a future "tighten" PR has to consciously break this case.
        val ref = InMemoryDirectoryRef(
            setOf(
                "www/js/rpg_core.js",
                "www/data/System.KEL",
                "www/data/Map001.KEL",
                "www/data/Atlas.PLUTO",
            ),
        )
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            fingerprint(ref),
        )
    }

    @Test
    fun fingerprint_partialRmmvOnlySystemJson_isUnknown() {
        // missing rpg_core.js → no match. System.json alone is not RMMV-distinctive.
        val ref = InMemoryDirectoryRef(setOf("www/data/System.json"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_wineCustomGameLikeTree_isUnknown() {
        // .exe + .dll looks like a wine custom game, not html5
        val ref = InMemoryDirectoryRef(setOf("Game.exe", "libcrypto.dll", "data.pak"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_javaFileDirectoryRefEndToEnd_onDiskRmmvDetected() {
        // write a real rmmv-shaped tree to a temp dir, fingerprint through the on-disk adapter.
        val wwwJs = tempFolder.newFolder("www", "js")
        java.io.File(wwwJs, "rpg_core.js").writeText("")
        val wwwData = tempFolder.newFolder("www", "data")
        java.io.File(wwwData, "System.json").writeText("{}")

        val ref = JavaFileDirectoryRef(tempFolder.root)
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_isDeterministic_sameRefSameResult() {
        val ref = InMemoryDirectoryRef(
            setOf("scripts/c3runtime.js"),
        )
        val first = fingerprint(ref)
        val second = fingerprint(ref)
        assertEquals(first, second)
        assertEquals(FingerprintResult.Matched(engine = "pack:c3", confidence = 80), first)
    }

    @Test
    fun fingerprint_rmmvBeatsC3WhenBothShapesPresent() {
        // first-match-wins: signatures registered in RMMV, RMMZ, C3 order (see EngineFingerprinter).
        // if a tree somehow matches both rmmv AND c3, rmmv wins. this locks the ordering.
        val ref = InMemoryDirectoryRef(
            setOf(
                "www/js/rpg_core.js",
                "www/data/System.json",
                "scripts/c3runtime.js",
            ),
        )
        val result = fingerprint(ref)
        // hybrid: rmmv primary, c3 alternate. confidence=80 (single-anchor RmmvSignature).
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:rmmv",
                webRoot = "www",
                confidence = 80,
                alternates = listOf("pack:c3"),
            ),
            result,
        )
    }

    // ---------- fingerprint(File) — disk-first + package.nw zip probe ----------

    // local helper; sharedTest extraction is optional polish.
    private fun writeZip(target: File, entries: Map<String, ByteArray>): File {
        java.util.zip.ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return target
    }

    @Test
    fun fingerprint_file_diskMatch_returnsMatchedWithSignatureWebRoot() {
        // disk has rmmv shape — zip probe must NOT run; webRoot comes from signature ("www"), not zip prefix.
        val wwwJs = tempFolder.newFolder("www", "js")
        File(wwwJs, "rpg_core.js").writeText("")
        val wwwData = tempFolder.newFolder("www", "data")
        File(wwwData, "System.json").writeText("{}")

        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_zipProbe_c3InsideZip_matchesAsPackC3() {
        writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "scripts/c3runtime.js" to ByteArray(0),
                "index.html" to "<html></html>".toByteArray(),
            ),
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "zip:package.nw", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_zipProbe_rmmvInsideZip_matchesAsPackRmmv() {
        writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "www/js/rpg_core.js" to ByteArray(0),
                "www/data/System.json" to "{}".toByteArray(),
                "www/index.html" to "<html></html>".toByteArray(),
            ),
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "zip:package.nw", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_zipProbe_rmmzInsideZip_matchesAsPackRmmz() {
        // MZ in-zip — same pack id as MV per (signature returns engineId="pack:rmmv").
        // RmmzSignature is multi-anchor → confidence=100 (matches Matched default).
        writeZip(
            tempFolder.newFile("package.nw"),
            mapOf(
                "js/rmmz_core.js" to ByteArray(0),
                "data/System.json" to "{}".toByteArray(),
                "index.html" to "<html></html>".toByteArray(),
            ),
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "zip:package.nw"),
            result,
        )
    }

    @Test
    fun fingerprint_file_zipProbe_corruptZip_returnsUnknown() {
        // 32 bytes of non-zip garbage — ZipFile open MUST throw ZipException. caller gets Unknown, no rethrow.
        val pkg = tempFolder.newFile("package.nw")
        pkg.writeBytes(ByteArray(32) { it.toByte() })

        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    @Test
    fun fingerprint_file_zipProbe_noPackageNw_returnsUnknown() {
        // disk has unrelated file but no package.nw — zip probe short-circuits, returns Unknown.
        File(tempFolder.root, "other.txt").writeText("unrelated")
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    @Test
    fun fingerprint_file_unpackedPackageNwDir_emptyFallsThroughToUnknown() {
        // package.nw/ exists as an empty DIRECTORY: unpacked probe runs but finds no signature
        // match; zip probe sees isFile()=false and short-circuits; final result is Unknown.
        tempFolder.newFolder("package.nw")
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    // ---------- fingerprint(File) — unpacked package.nw/ directory probe ----------

    // Tokyo Dark (687260) ships C2 inside an unpacked package.nw/ DIRECTORY (not a zip),
    // sitting next to nw.exe. signature anchor is package.nw/c2runtime.js.
    @Test
    fun fingerprint_file_unpackedPackageNwDir_c2InsideDir_matchesAsPackC3() {
        val pkg = tempFolder.newFolder("package.nw")
        File(pkg, "c2runtime.js").writeText("")
        File(pkg, "index.html").writeText("<html></html>")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "package.nw", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_unpackedPackageNwDir_c3InsideDir_matchesAsPackC3() {
        val pkg = tempFolder.newFolder("package.nw")
        File(pkg, "scripts").mkdirs()
        File(pkg, "scripts/c3runtime.js").writeText("")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "package.nw", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_unpackedPackageNwDir_rmmvNestedUnderWww_matchesWithNestedWebRoot() {
        // RMMV inside unpacked package.nw/ — webRoot should be "package.nw/www" so installDir
        // resolution lands on the actual content folder.
        val pkg = tempFolder.newFolder("package.nw")
        File(pkg, "www/js").mkdirs()
        File(pkg, "www/js/rpg_core.js").writeText("")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "package.nw/www", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_unpackedPackageNwDir_rmmzInsideDir_matchesWithNestedWebRoot() {
        val pkg = tempFolder.newFolder("package.nw")
        File(pkg, "js").mkdirs()
        File(pkg, "js/rmmz_core.js").writeText("")
        File(pkg, "data").mkdirs()
        File(pkg, "data/System.json").writeText("{}")
        val result = fingerprint(tempFolder.root)
        // RmmzSignature multi-anchor → confidence=100 (Matched default). webRoot="" → "package.nw".
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "package.nw"),
            result,
        )
    }

    @Test
    fun fingerprint_file_unpackedPackageNwDir_nwjsImpactInsideDir_matchesWithNestedWebRoot() {
        val pkg = tempFolder.newFolder("package.nw")
        File(pkg, "package.json").writeText("{\"main\":\"assets/node-webkit.html\"}")
        File(pkg, "assets").mkdirs()
        File(pkg, "assets/node-webkit.html").writeText("<html></html>")
        File(pkg, "assets/data").mkdirs()
        val result = fingerprint(tempFolder.root)
        // NwjsImpactSignature multi-anchor → confidence=100, subEngine="impact".
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:nwjs",
                webRoot = "package.nw/assets",
                subEngine = "impact",
            ),
            result,
        )
    }

    @Test
    fun fingerprint_file_diskMatchBeatsUnpackedDirProbe() {
        // precedence: install-root signature wins over unpacked package.nw/ probe.
        // shouldn't happen in practice but lock the order so a misconfigured install
        // (root-level c3 marker AND a stray package.nw/ dir) reports the root engine.
        File(tempFolder.root, "scripts").mkdirs()
        File(tempFolder.root, "scripts/c3runtime.js").writeText("")
        val pkg = tempFolder.newFolder("package.nw")
        File(pkg, "www/js").mkdirs()
        File(pkg, "www/js/rpg_core.js").writeText("")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_zipProbe_zipWithNoEngineFiles_returnsUnknown() {
        // valid zip, but contents don't match any signature.
        writeZip(
            tempFolder.newFile("package.nw"),
            mapOf("readme.txt" to "hi".toByteArray()),
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    @Test
    fun fingerprint_directoryRef_overload_unchanged() {
        // guard against future breakage: InMemoryDirectoryRef callers must NOT accidentally route through
        // the File-overload (it would try to zip-probe a temp dir). DirectoryRef overload stays pure.
        val ref = InMemoryDirectoryRef(setOf("scripts/c3runtime.js"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "", confidence = 80),
            result,
        )
    }

    // ---------- ElectronSignature integration 1 ----------

    @Test
    fun fingerprint_electronShapedTree_matchesAsPackElectron() {
        val ref = InMemoryDirectoryRef(setOf("resources/app.asar", "package.json"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_electronWithElectronAsarVariant_matchesAsPackElectron() {
        val ref = InMemoryDirectoryRef(setOf("resources/electron.asar"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_electronBeatsRmmvWhenBothPresent() {
        // ordering lock: a pathological hybrid (electron wrapping rmmv) must prefer electron.
        // alternates surfaces the rmmv co-match for diagnostic logging.
        val ref = InMemoryDirectoryRef(
            setOf(
                "resources/app.asar",
                "www/js/rpg_core.js",
                "www/data/System.json",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:electron",
                webRoot = "",
                confidence = 80,
                alternates = listOf("pack:rmmv"),
            ),
            result,
        )
    }

    @Test
    fun fingerprint_signatures_listOrderLocked_tyranoBeforeElectron() {
        // reflection lock — Tyrano-on-Electron hybrids (Welcome to Maison Chichigami 2914480)
        // would mis-route to pack:electron at root if TyranoSignature came after. order is
        // load-bearing: TyranoSignature MUST be ahead of ElectronSignature.
        val clazz = Class.forName("app.gamenative.html5.fingerprint.EngineFingerprinterKt")
        val field = clazz.getDeclaredField("signatures")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sigs = field.get(null) as List<EngineSignature>
        val tyranoIndex = sigs.indexOf(TyranoSignature)
        val electronIndex = sigs.indexOf(ElectronSignature)
        assertTrue("TyranoSignature missing", tyranoIndex >= 0)
        assertTrue("ElectronSignature missing", electronIndex >= 0)
        assertTrue(
            "TyranoSignature must register BEFORE ElectronSignature (tyrano=$tyranoIndex electron=$electronIndex)",
            tyranoIndex < electronIndex,
        )
    }

    // ---------- ConstructTwoSignature / plan ----------

    @Test
    fun fingerprint_c2ShapedTree_matchesAsPackC3() {
        // TNP (332250) ships c2runtime.js at install ROOT, NOT scripts/c2runtime.js.
        // matcher reflects empirical layout, not the C3 mirror analog.
        val ref = InMemoryDirectoryRef(
            setOf(
                "c2runtime.js",
                "index.html",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_c3BeforeC2WhenBothPresent() {
        // first-match-wins: C3 registered before C2 so a hybrid tree resolves as C3.
        // alternates is empty because both signatures report engineId="pack:c3" — same-engine
        // alternates are filtered out (only DIFFERENT engineIds surface as alternates).
        val ref = InMemoryDirectoryRef(
            setOf(
                "scripts/c3runtime.js",
                "c2runtime.js",
                "index.html",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_signatures_listOrderLocked_c2AfterC3() {
        // reflection lock — ordering: C2 must follow C3 so existing C3 titles are
        // unaffected. mirrors electronFirst idiom above.
        val clazz = Class.forName("app.gamenative.html5.fingerprint.EngineFingerprinterKt")
        val field = clazz.getDeclaredField("signatures")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sigs = field.get(null) as List<EngineSignature>
        val c3Index = sigs.indexOf(ConstructThreeSignature)
        val c2Index = sigs.indexOf(ConstructTwoSignature)
        assertTrue("ConstructThreeSignature missing from registry", c3Index >= 0)
        assertTrue("ConstructTwoSignature missing from registry", c2Index >= 0)
        assertTrue(
            "C2 must register AFTER C3 (got c3=$c3Index c2=$c2Index)",
            c2Index > c3Index,
        )
    }

    // ---------- NwjsTerraSignature ----------

    @Test
    fun fingerprint_terraShapedTree_matchesAsPackNwjs() {
        // Alabaster Dawn (3110760) layout: package.json + terra/{index.html, dist/bundle.js}.
        // NwjsTerraSignature multi-anchor → confidence=100 (default), subEngine="terra".
        val ref = InMemoryDirectoryRef(
            setOf(
                "package.json",
                "terra/index.html",
                "terra/dist/bundle.js",
                "alabaster_dawn.exe",
                "nw.dll",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:nwjs", webRoot = "terra", subEngine = "terra"),
            result,
        )
    }

    @Test
    fun fingerprint_terraMissingBundle_doesNotMatch() {
        val ref = InMemoryDirectoryRef(setOf("package.json", "terra/index.html"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_terraMissingPackageJson_doesNotMatch() {
        val ref = InMemoryDirectoryRef(setOf("terra/index.html", "terra/dist/bundle.js"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_impactShapedTree_matchesAsPackNwjs() {
        // CrossCode (368340) layout: package.json + assets/{node-webkit.html, data, media, js}.
        // NwjsImpactSignature multi-anchor → confidence=100 (default), subEngine="impact".
        val ref = InMemoryDirectoryRef(
            setOf(
                "package.json",
                "assets/node-webkit.html",
                "assets/data/players/lea.json",
                "assets/media/entity/player/move.png",
                "assets/js/game.compiled.js",
                "CrossCode.exe",
                "nw.dll",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:nwjs", webRoot = "assets", subEngine = "impact"),
            result,
        )
    }

    @Test
    fun fingerprint_impactMissingDataDir_doesNotMatch() {
        val ref = InMemoryDirectoryRef(setOf("package.json", "assets/node-webkit.html"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_signatures_listOrderLocked_nwjsLast() {
        // pack:nwjs is the catch-all NW.js bucket — must come AFTER c3/rmmv/electron so a
        // c3-in-nwjs export still matches pack:c3 first. Terra/Impact/Generic variants live
        // at the tail; Generic must come LAST in the cluster so the specific sub-engines win.
        val clazz = Class.forName("app.gamenative.html5.fingerprint.EngineFingerprinterKt")
        val field = clazz.getDeclaredField("signatures")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sigs = field.get(null) as List<EngineSignature>
        val terraIndex = sigs.indexOf(NwjsTerraSignature)
        val impactIndex = sigs.indexOf(NwjsImpactSignature)
        val genericIndex = sigs.indexOf(NwjsGenericSignature)
        assertTrue("NwjsTerraSignature missing", terraIndex >= 0)
        assertTrue("NwjsImpactSignature missing", impactIndex >= 0)
        assertTrue("NwjsGenericSignature missing", genericIndex >= 0)
        val lastThree = sigs.size - 3
        assertTrue(
            "pack:nwjs signatures must be last (terra=$terraIndex impact=$impactIndex generic=$genericIndex size=${sigs.size})",
            terraIndex >= lastThree && impactIndex >= lastThree && genericIndex >= lastThree,
        )
        assertTrue(
            "NwjsGenericSignature must be the LAST nwjs sig (generic=$genericIndex impact=$impactIndex terra=$terraIndex)",
            genericIndex > impactIndex && genericIndex > terraIndex,
        )
    }

    // ---------- NwjsGenericSignature (package.json + .html main, no impact/terra markers) ----------

    @Test
    fun fingerprint_nwjsGeneric_packageJsonWithHtmlMain_matchesAsGenericNwjs() {
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json", "game/index.html"),
            contents = mapOf("package.json" to """{"main":"game/index.html","name":"foo"}"""),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:nwjs",
                webRoot = "game",
                subEngine = "generic",
                confidence = 80,
            ),
            result,
        )
    }

    @Test
    fun fingerprint_nwjsGeneric_mainAtRoot_webRootEmpty() {
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json", "index.html"),
            contents = mapOf("package.json" to """{"main":"index.html"}"""),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:nwjs",
                webRoot = "",
                subEngine = "generic",
                confidence = 80,
            ),
            result,
        )
    }

    @Test
    fun fingerprint_nwjsGeneric_jsMain_doesNotMatch() {
        // NW.js can technically point to .js as a node module, but the WebView path requires
        // an HTML entrypoint. reject .js main so misdetections don't auto-flip to webview.
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json", "main.js"),
            contents = mapOf("package.json" to """{"main":"main.js"}"""),
        )
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_nwjsGeneric_malformedJson_doesNotMatch() {
        val ref = InMemoryDirectoryRef(
            entries = setOf("package.json"),
            contents = mapOf("package.json" to "not json {"),
        )
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_nwjsGeneric_loses_to_impact_specific() {
        // Impact signature is registered BEFORE Generic — when both could match, Impact wins.
        // package.json with main "assets/node-webkit.html" + assets/data/ is the impact shape;
        // Generic would also accept (main is .html) but ordering guarantees impact wins.
        val ref = InMemoryDirectoryRef(
            entries = setOf(
                "package.json",
                "assets/node-webkit.html",
                "assets/data/players/lea.json",
            ),
            contents = mapOf("package.json" to """{"main":"assets/node-webkit.html"}"""),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:nwjs",
                webRoot = "assets",
                subEngine = "impact",
            ),
            result,
        )
    }

    @Test
    fun fingerprint_nwjsGeneric_onDisk_readsPackageJsonContents() {
        // end-to-end JavaFileDirectoryRef test — verify readText() actually reads the file.
        File(tempFolder.root, "package.json").writeText("""{"main":"web/start.html"}""")
        File(tempFolder.root, "web").mkdirs()
        File(tempFolder.root, "web/start.html").writeText("<html></html>")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:nwjs",
                webRoot = "web",
                subEngine = "generic",
                confidence = 80,
            ),
            result,
        )
    }

    // ---------- formerly-Candidate engines (godot/gms/unity), now first-class packs ----------

    @Test
    fun fingerprint_godotHtml5ExportShape_matchesAsPackGodot() {
        // pure Web export: .pck + .wasm + .html + .js, no launcher. promoted from Candidate
        // to first-class EngineSignature — auto-flips to webview runtime.
        val ref = InMemoryDirectoryRef(setOf("game.pck", "game.wasm", "index.html", "game.js"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:godot", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_godotHtml5_wrappedWithLauncherExe_matchesAsPackGodot() {
        // Steam ships wrap Web export in a launcher .exe (NW.js shell, CEF, custom WebView).
        // discriminator is .wasm presence (engine runtime), not .exe absence.
        val ref = InMemoryDirectoryRef(
            setOf(
                "launcher.exe",
                "game.pck",
                "game.wasm",
                "index.html",
                "game.js",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:godot", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_godotNativeWindowsExport_alchemistsAlcove_isUnknown() {
        // Alchemist's Alcove (3568090, observed on device 2026-05-20) ships native Godot
        // Windows: .pck + .exe + steam_api64.dll, NO .wasm. discriminator on .wasm presence
        // correctly excludes — Wine path is right.
        val ref = InMemoryDirectoryRef(
            setOf(
                "AlchemistsAlcoveWindows.exe",
                "AlchemistsAlcoveWindows.pck",
                "libgodotsteam.windows.template_release.x86_64.dll",
                "steam_api64.dll",
            ),
        )
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_godotNativeWindowsExport_domeKeeper_isUnknown() {
        // Dome Keeper (1637320, observed on device 2026-05-20) — second native Godot
        // fixture. .pck + .exe + steam_api64.dll + PlayFab/libsentry DLLs, no .wasm.
        val ref = InMemoryDirectoryRef(
            setOf(
                "domekeeper.exe",
                "domekeeper.pck",
                "crashpad_handler.exe",
                "steam_api64.dll",
                "libsentry.windows.release.x86_64.dll",
                "PlayFabCore.Win32.dll",
            ),
        )
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_gameMakerHtml5_wrappedWithLauncherExe_matchesAsPackGms() {
        // wrapped HTML5 GMS for Steam has launcher.exe alongside html5game.js. anchor IS
        // the discriminator; .exe is irrelevant. (post-promotion: returns Matched not Candidate.)
        val ref = InMemoryDirectoryRef(
            setOf(
                "launcher.exe",
                "html5game.js",
                "index.html",
                "sound/bgm.ogg",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:gms", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_unityWebGl_matchesAsPackUnity() {
        // promoted out of the Candidate cohort. anchor is Build/<name>.loader.js (always plain
        // even in brotli builds where framework/data/wasm ship as .br); index.html + Build/ at root.
        val ref = InMemoryDirectoryRef(
            setOf(
                "Build/MyGame.framework.js.br",
                "Build/MyGame.loader.js",
                "Build/MyGame.data.br",
                "index.html",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:unity", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_gameMakerHtml5_legacyLayout_matchesAsPackGms() {
        // legacy: html5game.js single file at install root.
        val ref = InMemoryDirectoryRef(setOf("html5game.js", "index.html", "sound/bgm.ogg"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:gms", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_gameMakerHtml5_modernLayout_rouletteKnightShape_matchesAsPackGms() {
        // Roulette Knight LD41 shape (confirmed via iframe source 2026-05-20):
        // html5game/<ProjectName>.js (here SuicideKnight.js — internal project name) +
        // index.html + sound/ + texture/. modern GMS HTML5 builds use a per-project script
        // name under html5game/ rather than a fixed html5game.js at root.
        val ref = InMemoryDirectoryRef(
            setOf(
                "index.html",
                "html5game/SuicideKnight.js",
                "sound/bgm.ogg",
                "texture/atlas.png",
            ),
        )
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:gms", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_enginePriority_c3BeatsUnity() {
        // a tree with both pack:c3 markers AND a unity Build/<name>.loader.js → c3 wins on
        // registration order (ConstructThreeSignature precedes UnityWebGlSignature). no candidates
        // remain post-promotion; this now guards first-class signature precedence, not match-vs-candidate.
        val ref = InMemoryDirectoryRef(
            setOf("scripts/c3runtime.js", "Build/MyGame.loader.js"),
        )
        val result = fingerprint(ref)
        assertTrue("expected Matched, got $result", result is FingerprintResult.Matched)
        assertEquals("pack:c3", (result as FingerprintResult.Matched).engine)
    }

    // ---------- NW.js single-exe probe — ICU file naming variants ----------

    @Test
    fun fingerprint_file_nwExeProbe_acceptsIcuDtlDll_runeousShape() {
        // Runeous (CUSTOM_GAME_1208444830) sideload shape: pre-0.13 NW.js single-exe with
        // nw.pak + ffmpegsumo.dll + icudt.dll (older chromium ICU naming, NOT icudtl.dat).
        // probe gate must accept either ICU file naming so the era-spanning probe stays one path.
        File(tempFolder.root, "nw.pak").writeText("")
        File(tempFolder.root, "ffmpegsumo.dll").writeText("")
        File(tempFolder.root, "icudt.dll").writeText("")
        // synthesize a single-exe bundle (zip appended to .exe). c2runtime marker proves probe
        // ran AND signatures evaluated against the zip contents (vs. just gate-passing).
        val payload = ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("c2runtime.js"))
                zos.write(ByteArray(0))
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("index.html"))
                zos.write("<html></html>".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        // prepend an MZ-like prefix so commons-compress treats it as a prefix-data zip.
        val exe = File(tempFolder.root, "Runeous.exe")
        exe.outputStream().use { out ->
            out.write("MZ".toByteArray())
            out.write(ByteArray(126))
            out.write(payload)
        }
        val result = fingerprint(tempFolder.root)
        // c2 inside the exe → pack:c3 (ConstructTwoSignature shares pack id). webRoot = "zip:<exe>".
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "zip:Runeous.exe", confidence = 80),
            result,
        )
    }

    // ---------- Tyrano-on-Electron + resources/app/ scenarios ----------

    @Test
    fun fingerprint_file_maisonChichigamiShape_matchesAsPackTyrano() {
        // Welcome to Maison Chichigami (2914480): Tyrano-on-Electron with payload under
        // resources/app/. TyranoSignature scans both root and resources/app/ internally and
        // is registered BEFORE ElectronSignature so the specific Tyrano pack wins.
        File(tempFolder.root, "resources/app/tyrano").mkdirs()
        File(tempFolder.root, "resources/app/data/system").mkdirs()
        File(tempFolder.root, "resources/app/tyrano/libs.js").writeText("")
        File(tempFolder.root, "resources/app/tyrano/tyrano.js").writeText("")
        File(tempFolder.root, "resources/app/data/system/Config.tjs").writeText("")
        File(tempFolder.root, "resources/app/package.json").writeText(
            """{"name":"Chichigamike","main":"main.js","description":"TyranoScript｜ティラノスクリプト Ver5",""" +
                """"dependencies":{"adm-zip":"^0.4.13","fs-extra":"^8.1.0"},"window":{"title":"x"}}""",
        )
        File(tempFolder.root, "chrome_100_percent.pak").writeText("")
        val result = fingerprint(tempFolder.root)
        // Tyrano file-anchor arm matches at resources/app prefix; webRootFor() returns
        // "resources/app". alternates surfaces pack:electron co-match for diagnostic logging.
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:tyrano",
                webRoot = "resources/app",
                alternates = listOf("pack:electron"),
            ),
            result,
        )
    }

    @Test
    fun fingerprint_file_tyranoPackageJsonHintOnly_matchesViaDescription() {
        // hypothetical: Tyrano-style title whose runtime files are renamed but package.json
        // description survives. exercises the description-hint arm.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/package.json").writeText(
            """{"name":"foo","main":"main.js","description":"Built with TyranoScript"}""",
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:tyrano",
                webRoot = "resources/app",
                alternates = listOf("pack:electron"),
            ),
            result,
        )
    }

    @Test
    fun fingerprint_file_plainElectronUnpacked_routesToElectron() {
        // Cookie Clicker-shape: no Tyrano markers, no Tyrano description. Tyrano misses,
        // Electron matches at root via resources/app/package.json existence.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/package.json").writeText("""{"name":"foo","main":"main.js"}""")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_resourcesAppEmpty_isUnknown() {
        // resources/app/ exists but contains no recognized engine markers, no package.json.
        // ElectronSignature misses (no package.json/asar). nested probe falls through. Unknown.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/random.txt").writeText("nope")
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    // ---------- asar probe — Tyrano-in-asar (Fujiki shape) + plain Electron-in-asar ----------

    @Test
    fun fingerprint_file_asarProbe_fujikiShape_tyranoInAsar_matchesAsPackTyrano() {
        // Fujiki (sideloaded Tyrano-on-Electron, asar-packed): resources/app.asar contains
        // tyrano/libs.js + tyrano/tyrano.js + data/system/Config.tjs at asar root, plus a
        // package.json with Tyrano-template description. ElectronSignature would otherwise
        // match resources/app.asar existence and route to pack:electron, hiding the Tyrano
        // runtime. asar probe runs FIRST, lets TyranoSignature claim the asar contents.
        val resourcesDir = tempFolder.newFolder("resources")
        val asarFile = File(resourcesDir, "app.asar")
        app.gamenative.html5.asar.AsarTestFixtures.writeFixture(
            asarFile,
            linkedMapOf(
                "tyrano/libs.js" to ByteArray(0),
                "tyrano/tyrano.js" to ByteArray(0),
                "data/system/Config.tjs" to "scWidth=1280;scHeight=720;".toByteArray(),
                "package.json" to """{"name":"x","main":"main.js","description":"TyranoScript Ver5"}""".toByteArray(),
                "main.js" to "require('electron');".toByteArray(),
                "index.html" to "<html></html>".toByteArray(),
            ),
        )
        val result = fingerprint(tempFolder.root)
        // pack:tyrano wins via file-anchors-at-asar-root arm. webRoot stays "" because
        // AsarAssetInterceptor serves entries from asar root with no prefix.
        assertEquals(
            FingerprintResult.Matched(engine = "pack:tyrano", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_file_asarProbe_plainElectronInAsar_fallsThroughToElectron() {
        // negative control: asar contains a generic Electron app (no Tyrano markers, no
        // RMMV/c3/etc). asar probe finds no engine signature inside, returns null. Falls
        // through to matchFirst(disk) where ElectronSignature matches on resources/app.asar
        // existence and routes to pack:electron as expected.
        val resourcesDir = tempFolder.newFolder("resources")
        val asarFile = File(resourcesDir, "app.asar")
        app.gamenative.html5.asar.AsarTestFixtures.writeFixture(
            asarFile,
            linkedMapOf(
                "package.json" to """{"name":"foo","main":"main.js","productName":"Foo"}""".toByteArray(),
                "main.js" to "require('electron');".toByteArray(),
                "index.html" to "<html></html>".toByteArray(),
            ),
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_asarProbe_corruptAsar_doesNotCrash() {
        // asar file exists but is corrupt → AsarArchive.open throws → probe logs + returns
        // null → fall through to disk matchFirst → ElectronSignature still matches on
        // resources/app.asar existence (it's a file, doesn't matter that it's malformed).
        val resourcesDir = tempFolder.newFolder("resources")
        File(resourcesDir, "app.asar").writeBytes(ByteArray(32) { it.toByte() })
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_faithStyleNativeGameMaker_isUnknown() {
        // FAITH shape: data.win + audiogroup*.dat + .exe — that's NATIVE GameMaker (Wine path),
        // NOT HTML5 GameMaker (which would ship html5game.js). must stay Unknown so the wine
        // path runs.
        val ref = InMemoryDirectoryRef(setOf("FAITH.exe", "data.win", "audiogroup1.dat"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }
}
