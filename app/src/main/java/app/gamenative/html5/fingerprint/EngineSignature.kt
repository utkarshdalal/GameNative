package app.gamenative.html5.fingerprint
import app.gamenative.html5.profile.EnginePackId

// one per engine variant; variants of one runtime share a pack id and differ only in layout.
sealed interface EngineSignature {
    val engineId: String

    // lives on the signature, not the pack JSON, because variants sharing a pack differ here.
    val webRoot: String

    // diagnostic only (e.g. pack:nwjs "impact"/"terra"/"generic").
    val subEngine: String? get() = null

    // multi-anchor signatures report 100, single-anchor 80.
    val confidence: Int get() = 80

    fun matches(root: DirectoryRef): Boolean

    fun webRootFor(root: DirectoryRef): String = webRoot
}

// single anchor on purpose: custom-encrypted RMMV titles rename data files (.KEL/.PLUTO), so
// www/data/System.json can't be required. rpg_core.js alone is unambiguous.
data object RmmvSignature : EngineSignature {
    override val engineId: String = EnginePackId.RMMV
    override val webRoot: String = "www"
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("www/js/rpg_core.js")
}

// RPG Maker MZ: same pack as MV, no www/ indirection.
data object RmmzSignature : EngineSignature {
    override val engineId: String = EnginePackId.RMMV
    override val webRoot: String = ""
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("js/rmmz_core.js") && root.exists("data/System.json")
}

data object ConstructThreeSignature : EngineSignature {
    override val engineId: String = EnginePackId.C3
    override val webRoot: String = ""
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("scripts/c3runtime.js")
}

// Construct 2 shares pack:c3: the runtimes diverge, but the c3 pack's patches + shims cover both.
data object ConstructTwoSignature : EngineSignature {
    override val engineId: String = EnginePackId.C3
    override val webRoot: String = ""
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("c2runtime.js")
}

// TS rewrite of the Impact engine, under terra/.
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

// classic Impact-engine NW.js. assets/data is the anchor because other engines also nest
// assets/media and assets/js.
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

// generic NW.js: package.json main is an .html file. MUST be registered after impact/terra.
data object NwjsGenericSignature : EngineSignature {
    override val engineId: String = EnginePackId.NWJS
    override val webRoot: String = "" // see webRootFor()
    override val subEngine: String = "generic"
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean {
        if (!root.exists("package.json")) return false
        val probe = PackageJsonProbe.parse(root.readText("package.json")) ?: return false
        val main = probe.main?.trim().orEmpty()
        // NW.js main is always .html; a .js main is an electron/node module. ElectronSignature
        // uses the inverse rule -- keep them in sync so both never claim a title.
        return main.endsWith(".html", ignoreCase = true)
    }

    override fun webRootFor(root: DirectoryRef): String {
        val probe = PackageJsonProbe.parse(root.readText("package.json")) ?: return ""
        return PackageJsonProbe.mainDir(probe.main)
    }
}

// GameMaker HTML5: html5game.js at root (GMS 1.x) or html5game/<Project>.js (GMS 2+).
// native GMS builds ship neither.
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

// Godot Web export: .pck + .wasm at root. .wasm keeps native Godot (.pck + .exe) on wine.
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

// Unity WebGL: Build/*.loader.js is ALWAYS plain JS, even in brotli/gzip builds where
// framework/data/wasm ship as .br/.gz.
data object UnityWebGlSignature : EngineSignature {
    override val engineId: String = EnginePackId.UNITY
    override val webRoot: String = ""
    override val confidence: Int = 100
    override fun matches(root: DirectoryRef): Boolean {
        if (!root.exists("Build")) return false
        return root.listFiles("Build").any { it.endsWith(".loader.js", ignoreCase = true) }
    }
}

// TyranoScript VNs, at root (NW.js) or under resources/app/ (electron). resources/app/ is checked
// here too because at root pack:electron would otherwise claim the title before the nested probe runs.
// MUST be registered before NwjsGenericSignature and ElectronSignature, which also match.
// package.json `description` is the ONLY metadata hint: matching on an `adm-zip` dependency
// claimed a plain electron title that uses it for mods, and the wrong pack broke its cloud saves.
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
        val anchorsMatch = root.exists("${p}tyrano/libs.js") &&
            root.exists("${p}tyrano/tyrano.js") &&
            root.exists("${p}data/system/Config.tjs")
        if (anchorsMatch) return true
        if (!root.exists("${p}package.json")) return false
        val probe = PackageJsonProbe.parse(root.readText("${p}package.json")) ?: return false
        return probe.description?.contains("TyranoScript", ignoreCase = true) == true
    }
}

// packed (asar) or unpacked (resources/app/package.json). MUST be registered after TyranoSignature.
data object ElectronSignature : EngineSignature {
    override val engineId: String = EnginePackId.ELECTRON
    override val webRoot: String = ""
    override val confidence: Int = 80
    override fun matches(root: DirectoryRef): Boolean =
        root.exists("resources/app.asar") ||
            root.exists("resources/electron.asar") ||
            matchesUnpacked(root)

    // modern NW.js (~0.83+) ships the same resources/app/ shape, so tell them apart by `main`:
    // .html = NW.js (NwjsGenericSignature's rule), anything else or absent = electron.
    private fun matchesUnpacked(root: DirectoryRef): Boolean {
        if (!root.exists("resources/app/package.json")) return false
        // unparseable package.json: still electron rather than dropping to Unknown.
        val probe = PackageJsonProbe.parse(root.readText("resources/app/package.json")) ?: return true
        return !probe.main?.trim().orEmpty().endsWith(".html", ignoreCase = true)
    }
}
