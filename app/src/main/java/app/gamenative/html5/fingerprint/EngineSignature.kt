package app.gamenative.html5.fingerprint
import app.gamenative.html5.profile.EnginePackId

// signatures as data objects (one per engine variant). MV and MZ share the
// SAME pack id ("pack:rmmv") -- the variants only affect path layout, not pack.
// unknown -> FingerprintResult.Unknown -> container stays wine (no flip).
sealed interface EngineSignature {
    val engineId: String

    // sub-folder (relative to install root) containing index.html + static assets.
    // RMMV nests under www/; RMMZ + C3 flatten to root. shared pack id means this must
    // live on the signature, not the pack JSON.
    val webRoot: String

    // sub-bucket within the pack -- diagnostic metadata, not consumed by ProfileRegistry today.
    // pack:nwjs uses "impact"/"terra"/"generic" to distinguish classic Impact-engine NW.js
    // titles from the TS Impact rewrite from vanilla NW.js wrappers. null when no sub-bucket
    // applies.
    val subEngine: String? get() = null

    // 0..100. multi-anchor signatures (2+ markers) report 100; single-anchor 80. lets the
    // fingerprinter rank ambiguous cases and surfaces "weak match" in logs.
    val confidence: Int get() = 80

    fun matches(root: DirectoryRef): Boolean

    // dynamic webRoot override -- NwjsGenericSignature derives the dir from package.json.main.
    // default returns the static webRoot field.
    fun webRootFor(root: DirectoryRef): String = webRoot
}

// RPG Maker MV uses www/ as the asset root. www/js/rpg_core.js is the unambiguous marker --
// no other engine ships that filename. originally we AND'd www/data/System.json as a sanity
// pair, but custom-encrypted RMMV titles rename data files to .KEL/.PLUTO at build time; their
// plugins.js patches DataManager to read the encrypted variants. dropping the second clause
// recovers those titles without false-positive risk. single anchor → conf=80.
data object RmmvSignature : EngineSignature {
    override val engineId: String = EnginePackId.RMMV
    override val webRoot: String = "www"
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("www/js/rpg_core.js")
}

// RPG Maker MZ drops the www/ indirection -- same pack id as RMMV. multi-anchor → conf=100.
data object RmmzSignature : EngineSignature {
    override val engineId: String = EnginePackId.RMMV
    override val webRoot: String = ""
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("js/rmmz_core.js") && root.exists("data/System.json")
}

// Construct 3 NW.js export ships c3runtime.js at scripts/ root. single anchor.
data object ConstructThreeSignature : EngineSignature {
    override val engineId: String = EnginePackId.C3
    override val webRoot: String = ""
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("scripts/c3runtime.js")
}

// Construct 2 NW.js export ships c2runtime.js at the install/zip ROOT (no scripts/
// indirection). reuses pack:c3 -- C2/C3 runtimes diverge but the existing c3 pack's patch +
// shim surface is a reasonable starting point. webRoot="" matches C3 -- index.html sits
// beside the runtime. single anchor.
data object ConstructTwoSignature : EngineSignature {
    override val engineId: String = EnginePackId.C3
    override val webRoot: String = ""
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("c2runtime.js")
}

// TS rewrite of the Impact engine under terra/. package.json main = "terra/index.html";
// bundle.js holds the entire compiled engine. pack:nwjs is the generic NW.js bucket --
// classic Impact lineage and other NW.js derivatives get sibling signatures with their own
// webRoot. shared engineId means one pack JSON / shim wiring covers all variants. 3
// anchors → conf=100.
data object NwjsTerraSignature : EngineSignature {
    override val engineId: String = EnginePackId.NWJS
    override val webRoot: String = "terra"
    override val subEngine: String = "terra"
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("package.json") &&
            root.exists("terra/index.html") &&
            root.exists("terra/dist/bundle.js")
}

// classic Impact-engine NW.js titles ship the Impact convention: package.json with main
// "assets/node-webkit.html" + assets/data/ + assets/media/ + assets/js/. shared pack:nwjs id
// with NwjsTerraSignature -- one pack JSON / shim wiring covers both. matches package.json +
// assets/node-webkit.html + assets/data (data is the JSON tree marker -- assets/media or
// assets/js alone aren't unique enough since other engines also nest media). 3 anchors →
// conf=100.
data object NwjsImpactSignature : EngineSignature {
    override val engineId: String = EnginePackId.NWJS
    override val webRoot: String = "assets"
    override val subEngine: String = "impact"
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("package.json") &&
            root.exists("assets/node-webkit.html") &&
            root.exists("assets/data")
}

// generic NW.js fallback: package.json exists, main field points to a .html file, and none
// of the impact/terra anchors hit. registered LAST in the nwjs cluster so impact/terra still
// win. webRoot derived dynamically from dirname(main). pack:nwjs id with subEngine="generic"
// flags the caller that the title gets nw/greenworks shims but no engine-specific patches.
data object NwjsGenericSignature : EngineSignature {
    override val engineId: String = EnginePackId.NWJS
    override val webRoot: String = "" // dynamic -- see webRootFor()
    override val subEngine: String = "generic"
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean {
        if (!root.exists("package.json")) return false
        val probe = PackageJsonProbe.parse(root.readText("package.json")) ?: return false
        val main = probe.main?.trim().orEmpty()
        // NW.js main must be an .html file (NW spec). reject .js / missing -- those are
        // node-style modules and won't render in a WebView entry-point.
        return main.endsWith(".html", ignoreCase = true)
    }

    override fun webRootFor(root: DirectoryRef): String {
        val probe = PackageJsonProbe.parse(root.readText("package.json")) ?: return ""
        return PackageJsonProbe.mainDir(probe.main)
    }
}

// GameMaker HTML5 export. two layouts:
//   legacy: html5game.js single file at install root (GMS Studio 1.x convention)
//   modern: html5game/<ProjectName>.js inside an html5game/ dir at install root (GMS 2+).
// native GMS (data.win + .exe at root) ships neither marker. no overlap with other engines.
data object GameMakerHtml5Signature : EngineSignature {
    override val engineId: String = EnginePackId.GMS
    override val webRoot: String = ""
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean {
        if (root.exists("html5game.js")) return true
        if (!root.exists("html5game")) return false
        return root.listFiles("html5game").any { it.endsWith(".js", ignoreCase = true) }
    }
}

// Godot Web export ships *.pck (pack data) + *.wasm (engine runtime) + index.html + *.js
// at install root. discriminator on .wasm presence keeps native Godot Windows export (which
// ships .pck + .exe but NO .wasm) on the wine path. webRoot="" -- D&DG demo and standard
// Godot Web exports drop everything at the install dir; if a future title nests, add a
// per-title patches.json entry.
// no overlap with other engines (none ship .wasm + .pck pair at root); ordering is cosmetic.
// 2-anchor sig → confidence=100.
data object GodotSignature : EngineSignature {
    override val engineId: String = EnginePackId.GODOT
    override val webRoot: String = ""
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean {
        val children = root.listFiles("")
        val hasPck = children.any { it.endsWith(".pck", ignoreCase = true) }
        if (!hasPck) return false
        return children.any { it.endsWith(".wasm", ignoreCase = true) }
    }
}

// Unity WebGL. marker is Build/<name>.loader.js -- the loader is ALWAYS plain JS even in
// brotli/gzip builds (where framework/data/wasm ship as .br/.gz). the old Candidate keyed on
// Build/*.framework.js, which a brotli build (*.framework.js.br) would miss -- loader.js is the
// robust anchor. webRoot="" (index.html + Build/ at install root). no overlap with other engines
// (only Unity ships Build/*.loader.js). single distinctive anchor → conf=100.
data object UnityWebGlSignature : EngineSignature {
    override val engineId: String = EnginePackId.UNITY
    override val webRoot: String = ""
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean {
        if (!root.exists("Build")) return false
        return root.listFiles("Build").any { it.endsWith(".loader.js", ignoreCase = true) }
    }
}

// TyranoScript / TyranoBuilder VN titles. matches via three independent strategies:
//   1. file anchors at root -- Tyrano-on-NW.js single-exe shape
//   2. file anchors under resources/app/ -- Tyrano-on-Electron shape
//      (resources/app/tyrano/libs.js + ...). nested probe ALSO covers this via the file
//      path but matching at root keeps registration-order precedence working (we'd
//      otherwise route to pack:electron at root before the nested probe runs).
//   3. package.json hints at root OR resources/app/ -- `description` containing "TyranoScript"
//      OR `dependencies` including `adm-zip` (Tyrano's `.tpatch` apply uses it). catches
//      future Tyrano titles whose file layout drifts but whose template metadata stays.
// titles are typically wrapped in NW.js (so NwjsGenericSignature would also match via
// package.json + main=index.html); registered BEFORE NwjsGenericSignature AND before
// ElectronSignature so the specific Tyrano pack wins on Tyrano-on-Electron hybrids.
// webRoot reflects where the Tyrano payload lives -- "" at root, "resources/app" when nested.
data object TyranoSignature : EngineSignature {
    override val engineId: String = EnginePackId.TYRANO
    override val webRoot: String = ""
    override val confidence: Int = 100

    override fun matches(root: DirectoryRef): Boolean =
        matchesAtPrefix(root, "") || matchesAtPrefix(root, "resources/app")

    override fun webRootFor(root: DirectoryRef): String =
        if (matchesAtPrefix(root, "")) "" else "resources/app"

    private fun matchesAtPrefix(root: DirectoryRef, prefix: String): Boolean {
        val p = if (prefix.isEmpty()) "" else "$prefix/"
        // file-anchor arm: three Tyrano runtime markers under <prefix>/.
        val anchorsMatch = root.exists("${p}tyrano/libs.js") &&
            root.exists("${p}tyrano/tyrano.js") &&
            root.exists("${p}data/system/Config.tjs")
        if (anchorsMatch) return true
        // package.json hint arm: description / dependencies metadata.
        if (!root.exists("${p}package.json")) return false
        val probe = PackageJsonProbe.parse(root.readText("${p}package.json")) ?: return false
        if (probe.description?.contains("TyranoScript", ignoreCase = true) == true) return true
        if ("adm-zip" in probe.dependencies) return true
        return false
    }
}

// Electron titles detected by resources/app.asar, resources/electron.asar, OR the UNPACKED
// variant resources/app/package.json. existence-only -- productName + main resolved lazily at
// WebViewScreen launch. unpacked path covers indie titles that skip asar packing.
// webRoot="" because electron installs have no www/ indirection (entry layout differs from
// RMMV). registered AFTER TyranoSignature in EngineFingerprinter.signatures so
// Tyrano-on-Electron hybrids route to pack:tyrano. single anchor.
data object ElectronSignature : EngineSignature {
    override val engineId: String = EnginePackId.ELECTRON
    override val webRoot: String = ""
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("resources/app.asar") ||
            root.exists("resources/electron.asar") ||
            root.exists("resources/app/package.json")
}
