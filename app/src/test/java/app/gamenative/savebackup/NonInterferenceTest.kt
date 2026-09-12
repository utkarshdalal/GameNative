package app.gamenative.savebackup

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Non-interference with official cloud sync — game-save-backup Task 13.2 (Requirements 11.1, 11.2).
 *
 * This is primarily a **code-ownership boundary** test: it asserts on the *source* of the
 * `app.gamenative.savebackup` package itself, proving by inspection that the manual save-backup
 * engine can only ever be entered through a direct UI action and can never touch the official
 * cloud-sync mechanisms (Steam Auto Cloud, GOG cloud sync, Epic cloud sync).
 *
 * Why source inspection rather than pure behaviour: Requirement 11.1 ("initiate only in direct
 * response to a user action, never from a schedule, background trigger, or cloud-sync event") and
 * Requirement 11.2 ("never invoke, enable, disable, or reconfigure the official cloud-sync
 * mechanisms") are *negative* / absence properties. The only way to prove an absence across the
 * whole engine is to demonstrate that the engine's own source contains no dependency on the APIs
 * that would let it happen — a scheduler/worker/event subscription (11.1) or a cloud-sync entry
 * point (11.2 / 11.2a). A behavioural test can only show that one particular execution did not
 * cross the boundary; the boundary itself is a property of the code, so we assert on the code.
 *
 * A lightweight behavioural I/O-confinement example (Requirement 11.2a) complements the source
 * scans: it drives a real [RawTreeCodec] export/import over temp dirs and asserts that only the
 * given save-root and external trees were touched — nothing landed under an unrelated
 * "cloud-sync sentinel" directory the engine was never told about.
 *
 * These are plain JVM unit tests (no Robolectric, no PBT library needed).
 */
class NonInterferenceTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempDirs.forEach { deleteRecursively(it) }
        tempDirs.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Forbidden-token lists (documented rationale)
    // ---------------------------------------------------------------------------------------------

    /**
     * Background-trigger / scheduler / worker tokens (Requirement 11.1). If the engine referenced
     * any of these it could start a transfer from something other than a direct user action. None
     * of these belong anywhere in a strictly manual, user-directed feature.
     *
     *  - `WorkManager` / `androidx.work` — Jetpack background job scheduling.
     *  - `AlarmManager` — time-based OS alarms.
     *  - `JobScheduler` — framework periodic/deferred jobs.
     *  - `BroadcastReceiver` — implicit/system-event driven entry points.
     *  - `scheduleAtFixedRate` / `ScheduledExecutor` — java.util(.concurrent) periodic scheduling.
     *  - `Timer(` — java.util.Timer instantiation (deferred/repeating tasks).
     */
    private val schedulerTokens = listOf(
        "WorkManager",
        "androidx.work",
        "AlarmManager",
        "JobScheduler",
        "BroadcastReceiver",
        "scheduleAtFixedRate",
        "ScheduledExecutor",
        "Timer(",
    )

    /**
     * Event-subscription token (Requirement 11.1). The app broadcasts lifecycle/sync events through
     * `PluviaApp.events`; subscribing via `PluviaApp.events.on(...)` would let the engine start a
     * transfer in response to an event rather than a direct user action. The engine must never
     * subscribe. (Note: the token is `PluviaApp.events.on` specifically — we do not forbid all use
     * of `PluviaApp`, only the subscription entry point.)
     */
    private val eventSubscriptionTokens = listOf(
        "PluviaApp.events.on",
        ".events.on(",
    )

    /**
     * Official cloud-sync entry points (Requirements 11.2 / 11.2a). These are the *actual* cloud-sync
     * APIs used elsewhere in the app; the manual backup engine must never call them:
     *
     *  - `forceSyncUserFiles` — `SteamService.forceSyncUserFiles(...)`, invoked by SteamAppScreen's
     *    "Force cloud sync" menu action. This is the exact boundary the engine must NOT cross.
     *  - `syncUserFiles` — `SteamAutoCloud.syncUserFiles(...)`, the low-level Steam Auto Cloud sync.
     *  - `SteamAutoCloud` — the Steam Auto Cloud module itself; referencing it at all in the engine
     *    would mean touching cloud-sync storage/config (forbidden by 11.2a).
     *  - `AutoCloud` — any Auto Cloud symbol (defensive superset of `SteamAutoCloud`).
     *  - `forceCloudSync` — `GOGAppScreen.forceCloudSync(...)` / the GOG & Epic "Force cloud sync"
     *    handlers; the GOG/Epic cloud-sync entry points.
     *
     * Deliberately NOT forbidden: bare `SteamService`, `getAppInfoOf`, and `userSteamId`. The
     * savebackup package legitimately uses `SteamService.getAppInfoOf(...)` and
     * `SteamService.userSteamId` for READ-ONLY app-info / account-id resolution
     * (SaveLocationResolver, SteamSaveSourceStrategy). Forbidding all `SteamService` use would be a
     * false positive; the boundary is the cloud-sync *entry points*, not read-only identity lookups.
     */
    private val cloudSyncTokens = listOf(
        "forceSyncUserFiles",
        "syncUserFiles",
        "SteamAutoCloud",
        "AutoCloud",
        "forceCloudSync",
    )

    // ---------------------------------------------------------------------------------------------
    // Source-scan boundary tests
    // ---------------------------------------------------------------------------------------------

    // Req 11.1 — the engine only starts from a direct user action: no scheduler/worker/background
    // trigger anywhere in the savebackup package source.
    @Test
    fun engineHasNoSchedulerOrBackgroundTrigger() {
        val sources = savebackupSources()
        assertSourcesPresent(sources)
        assertNoTokens(sources, schedulerTokens, "background trigger / scheduler / worker (Req 11.1)")
    }

    // Req 11.1 — the engine is never entered from an event subscription: no `PluviaApp.events.on`
    // (event subscription) anywhere in the savebackup package source.
    @Test
    fun engineHasNoEventSubscription() {
        val sources = savebackupSources()
        assertSourcesPresent(sources)
        assertNoTokens(sources, eventSubscriptionTokens, "event subscription (Req 11.1)")
    }

    // Req 11.2 / 11.2a — the engine never invokes/enables/reconfigures the official cloud-sync
    // mechanisms and never touches their storage/config: no cloud-sync entry point anywhere in the
    // savebackup package source.
    @Test
    fun engineDoesNotReferenceCloudSyncEntryPoints() {
        val sources = savebackupSources()
        assertSourcesPresent(sources)
        assertNoTokens(sources, cloudSyncTokens, "official cloud-sync entry point (Req 11.2 / 11.2a)")
    }

    // ---------------------------------------------------------------------------------------------
    // Behavioural I/O-confinement example (Req 11.2a)
    // ---------------------------------------------------------------------------------------------

    // Req 11.2a — while a transfer is in progress, the engine confines reads/writes to the resolved
    // save location and the selected external location, and never touches cloud-sync storage. This
    // drives a real export/import over temp dirs and asserts nothing landed under a "cloud-sync
    // sentinel" directory the codec was never given.
    @Test
    fun exportImportConfinesIoToSaveRootAndExternalLocation() {
        val saveRoot = newTempDir("noninterference-saveroot")
        val externalRoot = newTempDir("noninterference-external")
        // A sentinel standing in for cloud-sync storage/config. It is passed to nothing; if any
        // write lands here, the engine would have escaped its two allowed trees (Req 11.2a).
        val cloudSyncSentinel = newTempDir("noninterference-cloudsync-sentinel")

        // Populate the save root with an arbitrary set of files.
        val saveSet = mapOf(
            "Slot1/save.dat" to "alpha".toByteArray(),
            "Slot1/meta.json" to "{\"v\":1}".toByteArray(),
            "settings.cfg" to "volume=7".toByteArray(),
        )
        saveSet.forEach { (rel, bytes) ->
            val f = saveRoot.resolve(rel)
            f.parent?.let { Files.createDirectories(it) }
            Files.write(f, bytes)
        }

        // Export ONLY the save root into ONLY the external location.
        val exported = RawTreeCodec.export(
            roots = listOf(RawTreeCodec.ExportRoot(saveRoot)),
            gameName = "ConfinementGame",
            dest = TempDirTreeWriter(externalRoot),
            timestampMillis = 1_700_000_000_000L,
        )
        assertEquals("export must write every save file", saveSet.size, exported)

        // The external tree must contain exactly the exported set (relative paths + bytes).
        val producedSubdir = singleSubdir(externalRoot)
        assertEquals(
            "external location must contain exactly the exported save set",
            saveSet.mapValues { it.value.toList() },
            readTree(producedSubdir),
        )

        // Import back into a fresh container save root; still only two trees are involved.
        val destRoot = newTempDir("noninterference-dest")
        val staging = newTempDir("noninterference-staging")
        val imported = RawTreeCodec.import(
            source = TempDirTreeReader(producedSubdir),
            destinationRoot = destRoot,
            stagingDir = staging,
        )
        assertEquals("import must write every save file", saveSet.size, imported)
        assertEquals(
            "imported save set must equal the original",
            saveSet.mapValues { it.value.toList() },
            readTree(destRoot),
        )

        // The core confinement assertion: the cloud-sync sentinel tree was never touched. The
        // engine was given only the save root and the external location, so no read or write may
        // have escaped into cloud-sync storage/config (Req 11.2a).
        assertTrue(
            "cloud-sync sentinel must remain empty — no engine I/O may escape the save/external trees",
            isEmptyTree(cloudSyncSentinel),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Source discovery + assertions
    // ---------------------------------------------------------------------------------------------

    private data class SourceFile(val name: String, val text: String)

    /** Read every `.kt` file under the resolved savebackup source directory. */
    private fun savebackupSources(): List<SourceFile> {
        val dir = locateSavebackupSourceDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }?.sortedBy { it.name }
            ?: emptyList()
        return files.map { SourceFile(it.name, it.readText()) }
    }

    /**
     * Resolve `.../app/src/main/java/app/gamenative/savebackup` robustly by probing a set of paths
     * relative to the test runtime working directory (`user.dir`, which may be the module dir
     * `app/` or the repo root) and walking up a few parents. Fails loudly if not found so the source
     * scans can never pass vacuously.
     */
    private fun locateSavebackupSourceDir(): File {
        val relative = "src/main/java/app/gamenative/savebackup"
        val relativeWithModule = "app/$relative"

        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val probed = mutableListOf<File>()
        var current: File? = start
        var hops = 0
        while (current != null && hops < 6) {
            probed += File(current, relative)
            probed += File(current, relativeWithModule)
            current = current.parentFile
            hops++
        }

        val found = probed.firstOrNull { it.isDirectory }
        if (found != null) return found

        fail(
            "Could not locate savebackup source dir. Started from ${start.absolutePath}. " +
                "Probed:\n" + probed.joinToString("\n") { " - ${it.absolutePath}" },
        )
        error("unreachable")
    }

    /** Guard against a vacuous pass: the package must exist and contain the known source files. */
    private fun assertSourcesPresent(sources: List<SourceFile>) {
        assertTrue(
            "expected to scan multiple savebackup source files but found ${sources.size}",
            sources.size >= 5,
        )
        assertTrue(
            "expected SaveBackupEngine.kt among scanned sources: ${sources.map { it.name }}",
            sources.any { it.name == "SaveBackupEngine.kt" },
        )
    }

    /** Assert none of the [tokens] appear in any scanned source file. */
    private fun assertNoTokens(sources: List<SourceFile>, tokens: List<String>, label: String) {
        val violations = mutableListOf<String>()
        sources.forEach { source ->
            tokens.forEach { token ->
                if (source.text.contains(token)) {
                    violations += "${source.name} references forbidden token \"$token\""
                }
            }
        }
        assertTrue(
            "savebackup package must not reference $label. Violations:\n" +
                violations.joinToString("\n") { " - $it" },
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Temp-dir-backed TreeWriter / TreeReader doubles + helpers (mirrors the codec tests)
    // ---------------------------------------------------------------------------------------------

    private class TempDirTreeWriter(private val root: Path) : RawTreeCodec.TreeWriter {
        override fun createDir(relativePath: String): RawTreeCodec.TreeWriter {
            val normalized = relativePath.replace('\\', '/').trim('/')
            val target = if (normalized.isEmpty()) root else root.resolve(normalized)
            Files.createDirectories(target)
            return TempDirTreeWriter(target)
        }

        override fun createFile(name: String): OutputStream {
            Files.createDirectories(root)
            return Files.newOutputStream(root.resolve(name))
        }
    }

    private class TempDirTreeReader(private val root: Path) : RawTreeCodec.TreeReader {
        override fun listFiles(): List<RawTreeCodec.TreeReader.Entry> {
            if (!Files.isDirectory(root)) return emptyList()
            val entries = mutableListOf<RawTreeCodec.TreeReader.Entry>()
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    val rel = root.relativize(file).toString().replace('\\', '/')
                    entries += RawTreeCodec.TreeReader.Entry(
                        relativePath = rel,
                        length = Files.size(file),
                        isSymlink = false,
                        openInput = { Files.newInputStream(file) },
                    )
                }
            }
            return entries
        }
    }

    private fun singleSubdir(dir: Path): Path {
        val subdirs = Files.list(dir).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        assertTrue("export must produce exactly one subdir, found $subdirs", subdirs.size == 1)
        return subdirs.single()
    }

    private fun readTree(root: Path): Map<String, List<Byte>> {
        if (!Files.isDirectory(root)) return emptyMap()
        val result = LinkedHashMap<String, List<Byte>>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                val rel = root.relativize(file).toString().replace('\\', '/')
                result[rel] = Files.readAllBytes(file).toList()
            }
        }
        return result
    }

    private fun isEmptyTree(root: Path): Boolean {
        if (!Files.isDirectory(root)) return true
        Files.walk(root).use { stream ->
            return stream.noneMatch { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        }
    }

    private fun newTempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path: Path -> Files.deleteIfExists(path) }
        }
    }
}
