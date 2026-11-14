package app.gamenative.html5.host

import java.io.File
import org.apache.commons.compress.archivers.zip.ZipFile
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId

// registry: TitleQuirks.TYRANO_TPATCH.
// TyranoScript patches ship as `*.tpatch` zips next to the exe; each entry shadows the same path in the
// install. Tyrano's own kag.applyPatch unpacks them into the install dir, which our zip-served install
// can't support, so ZipAssetInterceptor consults them BEFORE the main zip instead.
object TyranoTpatchOverlay {

    // mtime-ascending so newer patches win. caller owns the handles and must close them on WebView destroy.
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
