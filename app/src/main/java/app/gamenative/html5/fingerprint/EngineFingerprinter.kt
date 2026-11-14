package app.gamenative.html5.fingerprint

import app.gamenative.html5.asar.AsarArchive
import app.gamenative.html5.asar.AsarDirectoryRef
import java.io.File
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import timber.log.Timber

// ORDER MATTERS: first match wins.
private val signatures: List<EngineSignature> = listOf(
    // Tyrano ships as NW.js or electron wrappers, which the generic signatures would also claim.
    TyranoSignature,
    ElectronSignature,
    RmmvSignature,
    RmmzSignature,
    ConstructThreeSignature,
    ConstructTwoSignature,
    GameMakerHtml5Signature,
    GodotSignature,
    UnityWebGlSignature,
    NwjsTerraSignature,
    NwjsImpactSignature,
    // catch-all, so LAST.
    NwjsGenericSignature,
)

fun fingerprint(root: DirectoryRef): FingerprintResult {
    val match = matchAgainst(root)
    if (match != null) return match
    candidateSignatures.firstOrNull { it.matches(root) }?.let {
        return FingerprintResult.Candidate(engineHint = it.engineHint, reason = it.reason)
    }
    return FingerprintResult.Unknown
}

// webRootOverride lets nested-dir / zip / nw-exe probes supply a prefixed or zip:-scheme webRoot.
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

fun fingerprint(root: File): FingerprintResult {
    val disk = JavaFileDirectoryRef(root)

    // asar FIRST: an engine inside the asar (e.g. Tyrano-on-Electron) must win over
    // ElectronSignature, which matches on the asar's mere existence.
    val asarMatch = tryAsarProbe(root)
    if (asarMatch != null) return asarMatch

    val diskMatch = matchFirst(disk)
    if (diskMatch != null) return diskMatch

    // some NW.js titles ship package.nw/ as a DIRECTORY instead of a zip.
    val packageDir = File(root, "package.nw")
    val packageDirMatch = tryNestedDirProbe(packageDir, "package.nw")
    if (packageDirMatch != null) return packageDirMatch

    // modern NW.js (~0.83+) ships under resources/app/ like unpacked electron; ElectronSignature
    // declines .html mains so these reach this probe.
    val resourcesAppDir = File(root, "resources/app")
    val resourcesAppMatch = tryNestedDirProbe(resourcesAppDir, "resources/app")
    if (resourcesAppMatch != null) return resourcesAppMatch

    val zipResult = tryZipProbe(packageDir)
    if (zipResult != null) return zipResult

    val nwExeResult = tryNwExeProbe(root)
    if (nwExeResult != null) return nwExeResult

    // some depots nest the whole payload one level down under an arbitrary name. LAST so every
    // root-level and fixed-name shape wins first.
    val singleSubdirMatch = trySingleSubdirProbe(root)
    if (singleSubdirMatch != null) return singleSubdirMatch

    candidateSignatures.firstOrNull { it.matches(disk) }?.let {
        return FingerprintResult.Candidate(engineHint = it.engineHint, reason = it.reason)
    }
    return FingerprintResult.Unknown
}

private fun matchFirst(ref: DirectoryRef): FingerprintResult.Matched? = matchAgainst(ref)

// the match keeps its signature's webRoot: asar-served packs have their entries at the asar root.
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

private fun tryNestedDirProbe(dir: File, prefix: String): FingerprintResult? {
    if (!dir.isDirectory) return null
    val ref = JavaFileDirectoryRef(dir)
    return matchAgainst(ref) { sig ->
        val sigWebRoot = sig.webRootFor(ref)
        if (sigWebRoot.isEmpty()) prefix else "$prefix/$sigWebRoot"
    }
}

// exactly ONE non-hidden subdirectory (DepotDownloader leaves dot-entries beside the payload).
// loose files MUST stay tolerated: GOG writes goggame-* files into every install root. a false
// positive is loud and reversible (snackbar, flip back in Config); a miss is silent.
// prefix webRoot, NOT installPath -- save-sync resolves cloud paths off the store's install root.
private fun trySingleSubdirProbe(root: File): FingerprintResult? {
    if (!root.isDirectory) return null
    val children = root.listFiles()?.filterNot { it.name.startsWith(".") } ?: return null
    val only = children.filter { it.isDirectory }.singleOrNull() ?: return null
    return tryNestedDirProbe(only, only.name)
}

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

// pre-0.13 NW.js appends the package.nw zip to nw.exe itself. needs commons-compress: java.util.zip
// treats LFH offsets as absolute and lands on the MZ header instead.
private fun tryNwExeProbe(root: File): FingerprintResult? {
    if (!root.isDirectory) return null
    val nwPak = File(root, "nw.pak")
    if (!nwPak.isFile) return null
    // older chromium ships ICU as icudt.dll instead of icudtl.dat.
    val hasIcu = File(root, "icudtl.dat").isFile || File(root, "icudt.dll").isFile
    if (!hasIcu) return null

    val exes = root.listFiles { f -> f.isFile && f.name.endsWith(".exe", ignoreCase = true) }
        ?: return null

    for (exe in exes) {
        val result = runCatching {
            CommonsZipFile.builder().setFile(exe).get().use { zf ->
                val match = matchAgainst(CommonsZipDirectoryRef(zf)) { "zip:${exe.name}" }
                    ?: return@use null
                Timber.tag("EngineFingerprinter").i(
                    "NW.js single-exe match: exe=${exe.name} engine=${match.engine}",
                )
                match as FingerprintResult
            }
        }.onFailure {
            // most .exes aren't zips.
            Timber.tag("EngineFingerprinter").d("nw-exe probe skipped ${exe.name}: ${it.message}")
        }.getOrNull()
        if (result != null) return result
    }
    return null
}
