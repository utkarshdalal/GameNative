package app.gamenative.html5.fingerprint

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        // single-anchor -> confidence=80.
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
        // single-anchor -> confidence=80.
        assertEquals(FingerprintResult.Matched(engine = "pack:c3", confidence = 80), result)
    }

    @Test
    fun fingerprint_emptyTree_isUnknown() {
        val ref = InMemoryDirectoryRef(emptySet())
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }

    @Test
    fun fingerprint_rmmvWithRpgCoreOnly_matches() {
        // www/js/rpg_core.js is the unambiguous RMMV marker -- no other engine ships that filename.
        // requiring System.json alongside it broke custom-encrypted titles (OMORI ships
        // www/data/System.KEL, not System.json).
        val ref = InMemoryDirectoryRef(setOf("www/js/rpg_core.js"))
        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            fingerprint(ref),
        )
    }

    @Test
    fun fingerprint_omoriShape_rpgCorePlusEncryptedSystem_matches() {
        // custom-encrypted shape: www/js/rpg_core.js + www/data/System.KEL. RmmvSignature never
        // looks at System.KEL, but the real-world fixture is locked so tightening the rule has to
        // consciously break this case.
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
        // missing rpg_core.js -> no match. System.json alone is not RMMV-distinctive.
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
        // first-match-wins: signatures registered in RMMV, RMMZ, C3 order, so a tree matching both
        // rmmv AND c3 resolves as rmmv.
        val ref = InMemoryDirectoryRef(
            setOf(
                "www/js/rpg_core.js",
                "www/data/System.json",
                "scripts/c3runtime.js",
            ),
        )
        val result = fingerprint(ref)
        // rmmv primary, c3 alternate. confidence=80 (single-anchor RmmvSignature).
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

    // ---------- fingerprint(File) -- disk-first + package.nw zip probe ----------

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
        // disk has rmmv shape -- zip probe must NOT run; webRoot comes from signature ("www"), not zip prefix.
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
        // MZ in-zip shares MV's pack id. RmmzSignature is multi-anchor -> confidence=100.
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
        // non-zip garbage makes ZipFile open throw ZipException -- caller gets Unknown, no rethrow.
        val pkg = tempFolder.newFile("package.nw")
        pkg.writeBytes(ByteArray(32) { it.toByte() })

        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    @Test
    fun fingerprint_file_zipProbe_noPackageNw_returnsUnknown() {
        // no package.nw -- zip probe short-circuits.
        File(tempFolder.root, "other.txt").writeText("unrelated")
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    @Test
    fun fingerprint_file_unpackedPackageNwDir_emptyFallsThroughToUnknown() {
        // package.nw/ as an empty DIRECTORY: unpacked probe finds no match and the zip probe sees
        // isFile()=false and short-circuits.
        tempFolder.newFolder("package.nw")
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    // ---------- fingerprint(File) -- unpacked package.nw/ directory probe ----------

    // some NW.js titles ship C2 inside an unpacked package.nw/ DIRECTORY (not a zip) next to
    // nw.exe. signature anchor is package.nw/c2runtime.js.
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
        // RMMV inside unpacked package.nw/ -- webRoot must be "package.nw/www" so installDir
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
        // multi-anchor -> confidence=100. webRoot="" -> "package.nw".
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
        // multi-anchor -> confidence=100, subEngine="impact".
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
        // install-root signature wins over the unpacked package.nw/ probe, so a misconfigured
        // install (root-level c3 marker AND a stray package.nw/ dir) reports the root engine.
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
        writeZip(
            tempFolder.newFile("package.nw"),
            mapOf("readme.txt" to "hi".toByteArray()),
        )
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    @Test
    fun fingerprint_directoryRef_overload_unchanged() {
        // InMemoryDirectoryRef callers must NOT route through the File overload (it would try to
        // zip-probe a temp dir). the DirectoryRef overload stays pure.
        val ref = InMemoryDirectoryRef(setOf("scripts/c3runtime.js"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "", confidence = 80),
            result,
        )
    }

    // ---------- ElectronSignature integration ----------

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
        // a pathological hybrid (electron wrapping rmmv) must prefer electron. alternates surfaces
        // the rmmv co-match for diagnostic logging.
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
        // Tyrano-on-Electron hybrids would mis-route to pack:electron at root if TyranoSignature
        // came after. order is load-bearing: TyranoSignature MUST be ahead of ElectronSignature.
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

    // ---------- ConstructTwoSignature ----------

    @Test
    fun fingerprint_c2ShapedTree_matchesAsPackC3() {
        // C2 exports ship c2runtime.js at install ROOT, NOT scripts/c2runtime.js -- the matcher
        // follows the observed layout, not the C3 analog.
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
        // first-match-wins: C3 registered before C2 so a hybrid tree resolves as C3. alternates
        // is empty because both report engineId="pack:c3" -- only DIFFERENT engineIds surface.
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
        // C2 must follow C3 so existing C3 titles are unaffected.
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
        // terra layout: package.json + terra/{index.html, dist/bundle.js}. multi-anchor ->
        // confidence=100, subEngine="terra".
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
        // impact layout: package.json + assets/{node-webkit.html, data, media, js}. multi-anchor ->
        // confidence=100, subEngine="impact".
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
        // pack:nwjs is the catch-all NW.js bucket -- must come AFTER c3/rmmv/electron so a
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
        // Impact is registered BEFORE Generic. main "assets/node-webkit.html" + assets/data/ is
        // the impact shape; Generic would also accept it (main is .html) but ordering wins.
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
        // end-to-end through JavaFileDirectoryRef so readText() hits a real file.
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

    // ---------- godot / gms / unity ----------

    @Test
    fun fingerprint_godotHtml5ExportShape_matchesAsPackGodot() {
        // pure Web export: .pck + .wasm + .html + .js, no launcher -- auto-flips to webview runtime.
        val ref = InMemoryDirectoryRef(setOf("game.pck", "game.wasm", "index.html", "game.js"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:godot", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_godotHtml5_wrappedWithLauncherExe_matchesAsPackGodot() {
        // Steam builds wrap the Web export in a launcher .exe (NW.js shell, CEF, custom WebView).
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
        // native Godot Windows build: .pck + .exe + steam_api64.dll, NO .wasm. the .wasm
        // discriminator correctly excludes it -- Wine path is right.
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
        // second native Godot shape: .pck + .exe + steam_api64.dll + PlayFab/libsentry DLLs,
        // no .wasm.
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
        // wrapped HTML5 GMS for Steam has launcher.exe alongside html5game.js. the anchor IS the
        // discriminator; .exe is irrelevant.
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
        // anchor is Build/<name>.loader.js (always plain even in brotli builds where
        // framework/data/wasm ship as .br); index.html + Build/ at root.
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
        val ref = InMemoryDirectoryRef(setOf("html5game.js", "index.html", "sound/bgm.ogg"))
        val result = fingerprint(ref)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:gms", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_gameMakerHtml5_modernLayout_rouletteKnightShape_matchesAsPackGms() {
        // modern GMS HTML5 builds use a per-project script name under html5game/ rather than a
        // fixed html5game.js at root: html5game/<ProjectName>.js + index.html + sound/ + texture/.
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
        // pack:c3 markers AND a unity Build/<name>.loader.js -> c3 wins on registration order
        // (ConstructThreeSignature precedes UnityWebGlSignature).
        val ref = InMemoryDirectoryRef(
            setOf("scripts/c3runtime.js", "Build/MyGame.loader.js"),
        )
        val result = fingerprint(ref)
        assertTrue("expected Matched, got $result", result is FingerprintResult.Matched)
        assertEquals("pack:c3", (result as FingerprintResult.Matched).engine)
    }

    // ---------- NW.js single-exe probe -- ICU file naming variants ----------

    @Test
    fun fingerprint_file_nwExeProbe_acceptsIcuDtlDll_runeousShape() {
        // pre-0.13 NW.js single-exe: nw.pak + ffmpegsumo.dll + icudt.dll (older chromium ICU
        // naming, NOT icudtl.dat). the probe gate must accept either ICU naming so the
        // era-spanning probe stays one path.
        File(tempFolder.root, "nw.pak").writeText("")
        File(tempFolder.root, "ffmpegsumo.dll").writeText("")
        File(tempFolder.root, "icudt.dll").writeText("")
        // single-exe bundle (zip appended to .exe). the c2runtime marker proves the probe ran AND
        // signatures evaluated against the zip contents, not just that the gate passed.
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
        // c2 inside the exe -> pack:c3 (ConstructTwoSignature shares pack id). webRoot = "zip:<exe>".
        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "zip:Runeous.exe", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_modernNwjsUnderResourcesApp_matchesAsPackNwjs() {
        // NW.js ~0.83+ ships like unpacked electron: payload under resources/app/ next to a renamed
        // launcher .exe. ElectronSignature matches at ROOT and runs before the nested resources/app/
        // probe, so without a discriminator it claimed these titles and they loaded with the
        // electron pack instead of pack:nwjs. NW.js `main` is an .html entry; electron's is JS.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/package.json").writeText(
            """{"name":"game","main":"index.html","window":{"title":"Game"}}""",
        )
        File(tempFolder.root, "resources/app/index.html").writeText("<html></html>")
        File(tempFolder.root, "Game.exe").writeText("")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(
                engine = "pack:nwjs",
                webRoot = "resources/app",
                subEngine = "generic",
                confidence = 80,
            ),
            result,
        )
    }

    @Test
    fun fingerprint_file_unpackedElectronUnderResourcesApp_stillMatchesAsPackElectron() {
        // the same shape with a JS main is genuine unpacked electron -- must not regress.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/package.json").writeText(
            """{"name":"game","main":"start.js"}""",
        )
        File(tempFolder.root, "Game.exe").writeText("")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    // ---------- Tyrano-on-Electron + resources/app/ scenarios ----------

    @Test
    fun fingerprint_file_maisonChichigamiShape_matchesAsPackTyrano() {
        // Tyrano-on-Electron with payload under resources/app/. TyranoSignature scans both root
        // and resources/app/ internally and is registered BEFORE ElectronSignature so the
        // specific Tyrano pack wins.
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
        // file-anchor arm matches at the resources/app prefix. alternates surfaces the
        // pack:electron co-match for diagnostic logging.
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
    fun fingerprint_file_cookieClickerShape_matchesAsPackElectron() {
        // unpacked Electron whose package.json depends on adm-zip for mod unzipping (Cookie
        // Clicker). Tyrano is registered first and once read that dependency as an engine hint,
        // so a reinstall re-fingerprinted it as pack:tyrano -- which skips the pack:electron-gated
        // greenworks cloud fallback and left the game with no Steam Cloud restore.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/package.json").writeText(
            """{"name":"cookie-electron","version":"1.0.0","description":"Cookie Clicker standalone",""" +
                """"main":"start.js","dependencies":{"adm-zip":"^0.5.9","steamapi":"^2.1.1"}}""",
        )
        File(tempFolder.root, "chrome_100_percent.pak").writeText("")
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    @Test
    fun fingerprint_file_tyranoPackageJsonHintOnly_matchesViaDescription() {
        // Tyrano-style title whose runtime files are renamed but package.json description
        // survives. exercises the description-hint arm.
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
        // no Tyrano markers, no Tyrano description. Tyrano misses, Electron matches at root via
        // resources/app/package.json existence.
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
        // resources/app/ with no engine markers and no package.json: ElectronSignature misses
        // and the nested probe falls through.
        File(tempFolder.root, "resources/app").mkdirs()
        File(tempFolder.root, "resources/app/random.txt").writeText("nope")
        val result = fingerprint(tempFolder.root)
        assertEquals(FingerprintResult.Unknown, result)
    }

    // ---------- asar probe -- Tyrano-in-asar + plain Electron-in-asar ----------

    @Test
    fun fingerprint_file_asarProbe_fujikiShape_tyranoInAsar_matchesAsPackTyrano() {
        // asar-packed Tyrano-on-Electron: resources/app.asar holds tyrano/libs.js +
        // tyrano/tyrano.js + data/system/Config.tjs at asar root, plus a package.json with the
        // Tyrano-template description. ElectronSignature would otherwise match on
        // resources/app.asar existence and hide the Tyrano runtime, so the asar probe runs FIRST.
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
        // webRoot stays "" because AsarAssetInterceptor serves entries from asar root with no prefix.
        assertEquals(
            FingerprintResult.Matched(engine = "pack:tyrano", webRoot = ""),
            result,
        )
    }

    @Test
    fun fingerprint_file_asarProbe_plainElectronInAsar_fallsThroughToElectron() {
        // negative control: a generic Electron app inside the asar. the asar probe finds no engine
        // signature and falls through to disk, where ElectronSignature matches on
        // resources/app.asar existence.
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
        // corrupt asar: AsarArchive.open throws, the probe returns null, and ElectronSignature
        // still matches on resources/app.asar existence (malformed doesn't matter).
        val resourcesDir = tempFolder.newFolder("resources")
        File(resourcesDir, "app.asar").writeBytes(ByteArray(32) { it.toByte() })
        val result = fingerprint(tempFolder.root)
        assertEquals(
            FingerprintResult.Matched(engine = "pack:electron", webRoot = "", confidence = 80),
            result,
        )
    }

    // ---------- fingerprint(File) -- generic single-subdirectory descent ----------

    @Test
    fun fingerprint_file_singleSubdirDescent_trenchFaceShape_matchesRmmvWithPrefixedWebRoot() {
        // some depots nest the whole NW.js payload one level deeper, beside DepotDownloader's
        // dot-prefixed bookkeeping entries.
        val inner = tempFolder.newFolder("Trench Face")
        File(inner, "package.json").writeText("""{"main":"www/index.html"}""")
        File(inner, "nw.dll").writeText("")
        File(File(inner, "www/js").apply { mkdirs() }, "rpg_core.js").writeText("")
        tempFolder.newFolder(".DepotDownloader")
        tempFolder.newFile(".download_complete")

        assertEquals(
            // NW.js package.json also matches generically; registration order keeps rmmv primary.
            FingerprintResult.Matched(
                engine = "pack:rmmv",
                webRoot = "Trench Face/www",
                confidence = 80,
                alternates = listOf("pack:nwjs"),
            ),
            fingerprint(tempFolder.root),
        )
    }

    @Test
    fun fingerprint_file_singleSubdirDescent_looseRootFilesTolerated() {
        // readme/redist files beside the one payload folder must not defeat the descent --
        // only the DIRECTORY count gates it.
        val inner = tempFolder.newFolder("Game")
        File(inner, "c2runtime.js").writeText("")
        tempFolder.newFile("readme.txt")

        assertEquals(
            FingerprintResult.Matched(engine = "pack:c3", webRoot = "Game", confidence = 80),
            fingerprint(tempFolder.root),
        )
    }

    @Test
    fun fingerprint_file_singleSubdirDescent_twoSubdirs_staysUnknown() {
        // ambiguous root -- no basis for picking a payload dir, so no descent.
        File(tempFolder.newFolder("Game"), "c2runtime.js").writeText("")
        tempFolder.newFolder("Extras")

        assertEquals(FingerprintResult.Unknown, fingerprint(tempFolder.root))
    }

    @Test
    fun fingerprint_file_singleSubdirDescent_doesNotOverrideRootMatch() {
        // root-level markers win; the descent only runs when everything above misses.
        File(File(tempFolder.root, "www/js").apply { mkdirs() }, "rpg_core.js").writeText("")
        File(tempFolder.newFolder("www", "extra"), "c2runtime.js").writeText("")

        assertEquals(
            FingerprintResult.Matched(engine = "pack:rmmv", webRoot = "www", confidence = 80),
            fingerprint(tempFolder.root),
        )
    }

    @Test
    fun fingerprint_faithStyleNativeGameMaker_isUnknown() {
        // data.win + audiogroup*.dat + .exe is NATIVE GameMaker (Wine path), NOT HTML5 GameMaker
        // (which would ship html5game.js). must stay Unknown so the wine path runs.
        val ref = InMemoryDirectoryRef(setOf("FAITH.exe", "data.win", "audiogroup1.dat"))
        assertEquals(FingerprintResult.Unknown, fingerprint(ref))
    }
}
