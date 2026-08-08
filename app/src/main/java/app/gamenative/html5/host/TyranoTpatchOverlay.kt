package app.gamenative.html5.host

import java.io.File
import org.apache.commons.compress.archivers.zip.ZipFile
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId

// registry: TitleQuirks.TYRANO_TPATCH.
// pack:tyrano-specific overlay scanner. TyranoScript ships scenario / asset patches as
// `*.tpatch` zips dropped alongside the .exe in the install dir. each entry shadows a file
// at the same relative path under the install. Tyrano's own runtime apply
// (kag.applyPatch in kag.js) tries to read these via Node fs + adm-zip + writeFileSync into
// the install dir -- that pathway is incompatible with our zip-served install (no on-disk
// install root to write into), so we apply the same overlay semantics at the interceptor
// layer instead. ZipAssetInterceptor checks these overlays BEFORE its main zip; mtime-asc
// ordering means newer patches override older ones when multiple stack.
//
// scoped to pack:tyrano. other packs don't use the .tpatch convention.
object TyranoTpatchOverlay {

    // returns the list of opened overlay ZipFile handles (mtime-asc). caller owns the
    // lifecycle -- close them when the WebView is destroyed. empty list if engine isn't
    // pack:tyrano OR no `.tpatch` files exist OR installPath is unreadable.
    fun scan(installPath: String, engine: String?): List<ZipFile> {
        if (engine != EnginePackId.TYRANO) return emptyList()
        return runCatching {
            File(installPath)
                .listFiles { f -> f.isFile && f.name.endsWith(".tpatch", ignoreCase = true) }
                .orEmpty()
                .sortedBy { it.lastModified() }
                .mapNotNull { patchFile ->
                    runCatching { ZipFile.builder().setFile(patchFile).get() }
                        .onSuccess { Timber.tag(TAG).i("loaded tpatch overlay: %s", patchFile.name) }
                        .onFailure { Timber.tag(TAG).w(it, "tpatch open failed: %s", patchFile.name) }
                        .getOrNull()
                }
        }.getOrElse { emptyList() }
    }

    private const val TAG = "TyranoTpatchOverlay"
}
