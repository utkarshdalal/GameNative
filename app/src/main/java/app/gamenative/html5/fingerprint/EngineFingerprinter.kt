package app.gamenative.html5.fingerprint

import app.gamenative.html5.asar.AsarArchive
import app.gamenative.html5.asar.AsarDirectoryRef
import java.io.File
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import timber.log.Timber

// signatures registered statically; first-match wins. sideload + DOWNLOAD_COMPLETE both call
// this. Unknown means the caller falls back to the wine/custom-game path -- no errors surfaced.
private val signatures: List<EngineSignature> = listOf(
    // pack:tyrano BEFORE pack:electron AND before pack:nwjs generic -- Tyrano titles ship
    // as NW.js OR Electron wrappers (both would match generic NwjsGenericSignature /
    // ElectronSignature), but the tyrano/libs.js + tyrano/tyrano.js + data/system/Config.tjs
    // triad -- OR a package.json description/dependencies hint -- uniquely identifies the
    // engine. specific pack wins.
    TyranoSignature,
    ElectronSignature,
    RmmvSignature,
    RmmzSignature,
    ConstructThreeSignature,
    // C2 before C3 collisions are impossible (different runtime filenames); ordering preserves
    // existing pack:c3 detection.
    ConstructTwoSignature,
    // GameMaker HTML5 (legacy html5game.js OR modern html5game/<project>.js). no overlap
    // with Godot (different markers) or other engines.
    GameMakerHtml5Signature,
    // Godot Web (*.pck + *.wasm at root). no overlap with NW.js cluster (Godot Web has no
    // package.json) or rmmv/c3 (different runtime markers).
    GodotSignature,
    // Unity WebGL (Build/*.loader.js). promoted out of the Candidate cohort. unique marker,
    // no overlap. brotli/gzip-compressed builds still serve loader.js plain so this matches.
    UnityWebGlSignature,
    // pack:nwjs last -- most exotic. terra/ is unique to the TS Impact rewrite; no overlap
    // with electron/rmmv/c3 markers. Impact-classic (assets/node-webkit.html) follows
    // immediately after -- same pack:nwjs bucket, different webRoot.
    NwjsTerraSignature,
    NwjsImpactSignature,
    // generic NW.js (package.json + .html main, no impact/terra markers) lands LAST in the
    // nwjs cluster so the specific variants still win. catch-net for vanilla NW.js wrappers
    // with no recognized engine.
    NwjsGenericSignature,
)

fun fingerprint(root: DirectoryRef): FingerprintResult {
    val match = matchAgainst(root)
    if (match != null) return match
    // candidate sigs are recognized-but-unsupported (Godot/Unity/GameMaker). caller surfaces
    // a snackbar; container stays wine.
    candidateSignatures.firstOrNull { it.matches(root) }?.let {
        return FingerprintResult.Candidate(engineHint = it.engineHint, reason = it.reason)
    }
    return FingerprintResult.Unknown
}

// single match pipeline: filter signatures, registration-order picks primary, dedupe alternates,
// warn on multi-match, build Matched. webRootOverride lets the nested-dir / zip / nw-exe probes
// supply their prefixed or scheme-qualified webRoot; the default reads the signature's own
// webRootFor(ref). returns null when nothing matches.
private fun matchAgainst(
    ref: DirectoryRef,
    webRootOverride: (EngineSignature) -> String = { it.webRootFor(ref) },
): FingerprintResult.Matched? {
    val matches = signatures.filter { it.matches(ref) }
    if (matches.isEmpty()) return null
    val primary = matches.first()
    val alternates = matches.drop(1)
        .map { it.engineId }
        .filter { it != primary.engineId }
        .distinct()
    if (alternates.isNotEmpty()) {
        Timber.tag("EngineFingerprinter")
            .w("multi-match: primary=${primary.engineId} alternates=$alternates — registration order picks primary")
    }
    return FingerprintResult.Matched(
        engine = primary.engineId,
        webRoot = webRootOverride(primary),
        subEngine = primary.subEngine,
        confidence = primary.confidence,
        alternates = alternates,
    )
}

// disk-first, then unpacked package.nw/ directory probe, then package.nw zip probe.
// webRoot for zip matches uses the "zip:package.nw" scheme; ZipAssetInterceptor branches on it.
// unpacked-dir matches stay on the file-system path with webRoot = "package.nw[/<sig.webRoot>]".
fun fingerprint(root: File): FingerprintResult {
    val disk = JavaFileDirectoryRef(root)

    // asar probe FIRST -- Tyrano-on-Electron titles ship the Tyrano runtime + Config.tjs
    // INSIDE resources/app.asar. ElectronSignature.matches(disk) would otherwise win on
    // resources/app.asar existence and mis-route to pack:electron -- losing pack:tyrano's
    // viewport/scroll/QSA/Audio-pause shims. running the asar probe before matchFirst(disk)
    // lets specific engine signatures claim the asar's contents; if none match (genuine plain
    // Electron), we fall through to ElectronSignature on disk. note: signatures evaluated
    // inside the asar naturally exclude ElectronSignature itself (no resources/app.asar
    // nesting inside the asar) so no self-loop risk.
    val asarMatch = tryAsarProbe(root)
    if (asarMatch != null) return asarMatch

    val diskMatch = matchFirst(disk)
    if (diskMatch != null) return diskMatch

    // unpacked NW.js layout: some titles ship the bundle as a `package.nw/` DIRECTORY next
    // to nw.exe instead of zipping it. probe inside; webRoot becomes "package.nw" or
    // "package.nw/<sig.webRoot>" so installDir resolution in WebViewScreen lands on the actual
    // content folder. covers all engines uniformly -- any signature that would match flat at
    // the install root also matches when nested under package.nw/.
    val packageDir = File(root, "package.nw")
    val packageDirMatch = tryNestedDirProbe(packageDir, "package.nw")
    if (packageDirMatch != null) return packageDirMatch

    // modern NW.js (~0.83+) layout: bundle ships under `resources/app/` next to a renamed
    // launcher .exe, matching the Electron unpacked shape (e.g. Tyrano under
    // resources/app/tyrano/). ElectronSignature root match is gated on the NW.js `window`
    // field so the probe gets a turn here for the genuine NW.js case.
    val resourcesAppDir = File(root, "resources/app")
    val resourcesAppMatch = tryNestedDirProbe(resourcesAppDir, "resources/app")
    if (resourcesAppMatch != null) return resourcesAppMatch

    val zipResult = tryZipProbe(packageDir)
    if (zipResult != null) return zipResult

    // NW.js single-exe bundle: very old NW.js builds (pre-0.13, ffmpegsumo.dll era) ship the
    // game payload as a zip APPENDED to the nw.exe wrapper. no sibling package.nw file; the
    // .exe IS the zip. java.util.zip.ZipFile handles prefix-data zips natively (EOCD specifies
    // absolute file offsets), so once we identify the .exe we can run signatures against it.
    val nwExeResult = tryNwExeProbe(root)
    if (nwExeResult != null) return nwExeResult

    // last-chance candidate check on disk root (Godot/Unity/GameMaker). returns Candidate or
    // Unknown.
    candidateSignatures.firstOrNull { it.matches(disk) }?.let {
        return FingerprintResult.Candidate(engineHint = it.engineHint, reason = it.reason)
    }
    return FingerprintResult.Unknown
}

private fun matchFirst(ref: DirectoryRef): FingerprintResult.Matched? = matchAgainst(ref)

// asar probe: open resources/app.asar (or resources/electron.asar), wrap as DirectoryRef,
// run signatures against the asar's content tree. webRoot stays "" because the asar
// interceptor (AsarAssetInterceptor, selected at runtime via ElectronAsarSetup for
// pack:electron AND pack:tyrano) serves entries from the asar root with no prefix.
// ElectronSignature naturally won't match inside an asar (asar contents don't contain a
// nested resources/app.asar) so no risk of routing pack:electron-in-pack:electron loop.
private fun tryAsarProbe(root: File): FingerprintResult? {
    if (!root.isDirectory) return null
    val asarFile = listOf(
        File(root, "resources/app.asar"),
        File(root, "resources/electron.asar"),
    ).firstOrNull { it.isFile } ?: return null
    return runCatching {
        AsarArchive.open(asarFile).use { archive ->
            val match = matchAgainst(AsarDirectoryRef(archive)) ?: return@use null
            Timber.tag("EngineFingerprinter").i(
                "asar match: asar=${asarFile.name} engine=${match.engine}",
            )
            match as FingerprintResult
        }
    }.onFailure {
        Timber.tag("EngineFingerprinter").w(it, "asar-probe failed for ${asarFile.path}")
    }.getOrNull()
}

// nested-dir probe: run all signatures against `dir` and prefix the resulting webRoot
// with `prefix`. shared by the package.nw/ and resources/app/ probes -- same pattern,
// different prefix. returns null when dir is missing or no signature matches.
private fun tryNestedDirProbe(dir: File, prefix: String): FingerprintResult? {
    if (!dir.isDirectory) return null
    val ref = JavaFileDirectoryRef(dir)
    return matchAgainst(ref) { sig ->
        val sigWebRoot = sig.webRootFor(ref)
        if (sigWebRoot.isEmpty()) prefix else "$prefix/$sigWebRoot"
    }
}

// ZipException / IOException → Timber.warn + null. .use { } guarantees close.
private fun tryZipProbe(pkgNw: File): FingerprintResult? {
    if (!pkgNw.isFile) return null
    return runCatching {
        ZipFile(pkgNw).use { zf ->
            (matchAgainst(ZipDirectoryRef(zf)) { "zip:package.nw" } ?: return@use null) as FingerprintResult
        }
    }.onFailure {
        Timber.tag("EngineFingerprinter").w(it, "zip-probe failed for ${pkgNw.path}")
    }.getOrNull()
}

// NW.js single-exe bundle probe. very old NW.js (pre-0.13, ffmpegsumo.dll era) builds
// concatenate the package.nw zip onto the end of nw.exe -- no sibling package.nw file. shape
// markers: nw.pak + icudtl.dat at root. when matched, scan for any .exe at root, open with
// commons-compress (which handles prefix-data zips -- java.util.zip's ZipFile interprets LFH
// offsets as absolute file offsets and lands on the MZ header at byte 0 instead of the first
// LFH at the zip-portion start). webRoot = "zip:<exe-name>" -- existing scheme.
//
// note: runtime ZipAssetInterceptor still uses java.util.zip; serving assets from a single-exe
// bundle will need the same swap (or pre-extract). this probe only proves the engine match.
private fun tryNwExeProbe(root: File): FingerprintResult? {
    if (!root.isDirectory) return null
    val nwPak = File(root, "nw.pak")
    if (!nwPak.isFile) return null
    // ICU data file -- chromium ships either `icudtl.dat` (newer) or `icudt.dll` (older,
    // pre-2014 era). pre-0.13 single-exe NW.js shape ships icudt.dll alongside nw.pak +
    // ffmpegsumo.dll. accept either so the era-spanning probe stays a single code path.
    val hasIcu = File(root, "icudtl.dat").isFile || File(root, "icudt.dll").isFile
    if (!hasIcu) return null

    val exes = root.listFiles { f -> f.isFile && f.name.endsWith(".exe", ignoreCase = true) }
        ?: return null

    for (exe in exes) {
        val result = runCatching {
            CommonsZipFile.builder().setFile(exe).get().use { zf ->
                // CommonsZipDirectoryRef mirrors ZipDirectoryRef's traversal guard over the
                // commons-compress entry set (signature matchers only need exists/listFiles/readText).
                val match = matchAgainst(CommonsZipDirectoryRef(zf)) { "zip:${exe.name}" }
                    ?: return@use null
                Timber.tag("EngineFingerprinter").i(
                    "NW.js single-exe match: exe=${exe.name} engine=${match.engine}",
                )
                match as FingerprintResult
            }
        }.onFailure {
            // not every .exe is a valid zip -- most won't be. log at debug level.
            Timber.tag("EngineFingerprinter").d("nw-exe probe skipped ${exe.name}: ${it.message}")
        }.getOrNull()
        if (result != null) return result
    }
    return null
}
