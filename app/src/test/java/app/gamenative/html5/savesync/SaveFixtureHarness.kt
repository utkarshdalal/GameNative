package app.gamenative.html5.savesync

import java.io.File

// real PC-side saves are often absent (PII review, dev-local stash only), so loadXxx() returns null and
// callers `Assume.assumeNotNull` out.

// sources, in order:
// 1. env var GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT -- the `html5-saves/` folder or a parent containing it
// 2. test classpath resource `html5-saves/`

// layout (as extracted from Windows %LOCALAPPDATA%):
// html5-saves/Local/SolCesto/User Data/Default/{Local Storage/leveldb, IndexedDB/<prefix>.indexeddb.{leveldb,blob}}
// html5-saves/Local/lookOutside/User Data/Default/{Local Storage/leveldb, IndexedDB/<prefix>.indexeddb.leveldb}
// html5-saves/Local/User Data/Default/{...} -- TERMINA Chromium state (no save LevelDBs)
// html5-saves/save/{config,file1,global}.rpgsave -- TERMINA RMMV filesystem saves
object SaveFixtureHarness {

    private const val ENV_VAR = "GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT"
    private const val CLASSPATH_ROOT = "html5-saves"

    // iq80 DbImpl.open mutates the source dir (recovery writes MANIFEST-N + .sst files), so opening the
    // build-intermediates fixture directly pollutes later tests while processDebugUnitTestJavaRes is
    // UP-TO-DATE. tests must snapshot first.
    fun snapshotDir(src: File?, sandbox: File, subName: String = src?.name ?: "snapshot"): File? {
        if (src == null) return null
        val dest = File(sandbox, subName)
        if (dest.exists()) dest.deleteRecursively()
        src.copyRecursively(dest, overwrite = true)
        return dest
    }

    private fun fixturesRoot(): File? {
        System.getenv(ENV_VAR)?.takeIf { it.isNotBlank() }?.let { envPath ->
            val envRoot = File(envPath)
            // accept either the html5-saves dir itself or a parent that contains it
            return when {
                envRoot.resolve("Local").isDirectory -> envRoot
                envRoot.resolve(CLASSPATH_ROOT).isDirectory -> envRoot.resolve(CLASSPATH_ROOT)
                else -> null
            }
        }
        val url = SaveFixtureHarness::class.java.classLoader?.getResource(CLASSPATH_ROOT) ?: return null
        if (url.protocol != "file") return null
        return File(url.toURI()).takeIf { it.isDirectory }
    }

    fun loadSolCesto(): SolCestoFixture? {
        val root = fixturesRoot() ?: return null
        val default = root.resolve("Local/SolCesto/User Data/Default")
        val idbRoot = default.resolve("IndexedDB")
        val idbLevelDb = idbRoot.listFiles { f -> f.isDirectory && f.name.endsWith(".indexeddb.leveldb") }?.firstOrNull()
        val idbBlob = idbRoot.listFiles { f -> f.isDirectory && f.name.endsWith(".indexeddb.blob") }?.firstOrNull()
        val localStorage = default.resolve("Local Storage/leveldb").takeIf { it.isDirectory }
        if (idbLevelDb == null && localStorage == null) return null
        return SolCestoFixture(
            indexedDbLevelDb = idbLevelDb,
            indexedDbBlob = idbBlob,
            localStorageLevelDb = localStorage,
            originPrefix = idbLevelDb?.name?.removeSuffix(".indexeddb.leveldb"),
        )
    }

    fun loadLookOutside(): LookOutsideFixture? {
        val root = fixturesRoot() ?: return null
        val default = root.resolve("Local/lookOutside/User Data/Default")
        val idbRoot = default.resolve("IndexedDB")
        val idbLevelDb = idbRoot.listFiles { f -> f.isDirectory && f.name.endsWith(".indexeddb.leveldb") }?.firstOrNull()
        val idbBlob = idbRoot.listFiles { f -> f.isDirectory && f.name.endsWith(".indexeddb.blob") }?.firstOrNull()
        val localStorage = default.resolve("Local Storage/leveldb").takeIf { it.isDirectory }
        if (idbLevelDb == null && localStorage == null) return null
        return LookOutsideFixture(
            indexedDbLevelDb = idbLevelDb,
            indexedDbBlob = idbBlob,
            localStorageLevelDb = localStorage,
            originPrefix = idbLevelDb?.name?.removeSuffix(".indexeddb.leveldb"),
        )
    }

    // TERMINA saves are .rpgsave files; its Local/User Data/ tree has no game-slug parent and no save LevelDBs
    fun loadTermina(): TerminaFixture? {
        val root = fixturesRoot() ?: return null
        val chromiumState = root.resolve("Local/User Data/Default").takeIf { it.isDirectory }
        val saveDir = root.resolve("save").takeIf { it.isDirectory } ?: return null
        val saves = saveDir.listFiles { f -> f.isFile && f.name.endsWith(".rpgsave") }?.toList().orEmpty()
        if (saves.isEmpty()) return null
        return TerminaFixture(
            rmmvSaveDir = saveDir,
            rpgSaveFiles = saves,
            chromiumStateDefault = chromiumState,
        )
    }

    data class SolCestoFixture(
        val indexedDbLevelDb: File?,
        val indexedDbBlob: File?,
        val localStorageLevelDb: File?,
        val originPrefix: String?, // e.g. "chrome-extension_anopiimlkmdoenonenclohfilpeenfmj_0"
    )

    data class LookOutsideFixture(
        val indexedDbLevelDb: File?,
        val indexedDbBlob: File?,
        val localStorageLevelDb: File?,
        val originPrefix: String?, // e.g. "chrome-extension_iljnakbknbffnbhpfodiohbfdnjjabfp_0"
    )

    data class TerminaFixture(
        val rmmvSaveDir: File,
        val rpgSaveFiles: List<File>,
        val chromiumStateDefault: File?,
    )
}

