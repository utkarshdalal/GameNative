package app.gamenative.service.ea

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.winlator.container.Container
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Pulls EA cloud saves into the Wine prefix before launch and pushes local changes back after exit. */
object EaCloudSavesManager {
    sealed interface PullResult {
        data object Synced : PullResult
        data object NoCloudSaves : PullResult
        data class Conflict(val localMillis: Long, val remoteMillis: Long?) : PullResult
        data class Failed(val reason: String) : PullResult
    }

    internal enum class Md5Format { HEX, HEX_UPPER, BASE64, MAXIMA }

    private class LocalFile(val localName: String, val file: File, val size: Long, val modified: Long, val digest: ByteArray) {
        val key = localKey(size, digest)
    }

    private class StateEntry(val key: String, val cloudIdentity: String, val localName: String)

    private class State(val format: Md5Format?, val entries: Map<String, StateEntry>)

    private class CloudView(val entries: Map<String, EaCloudFile>, val ignored: Int, val ambiguous: Set<String>) {
        val duplicates: Boolean get() = ambiguous.isNotEmpty()
    }

    private class Download(val temp: File, val dest: File, val key: String)

    private class FormatProbe {
        var format: Md5Format? = null
        var unrecognised = false

        fun observe(entry: EaCloudFile, digest: ByteArray) {
            val md5 = entry.md5 ?: return
            val seen = recognise(md5, digest)
            if (seen == null || (format != null && format != seen)) unrecognised = true else format = seen
        }
    }

    private class Session(
        val target: EaCloudSaveTarget,
        val cloudAtPull: Map<String, String>,
        val entries: Map<String, EaCloudFile>,
        val forcedNames: Set<String>,
        val uploadAllowed: Boolean,
        val format: Md5Format?,
    )

    private class Remembered(val preference: EaCloudPreference, val at: Long)

    private class Op(val target: File, val backup: File?, val placed: Boolean, val size: Long = -1, val modified: Long = -1) {
        fun rewritten(): Boolean = placed && size >= 0 && (target.length() != size || target.lastModified() != modified)
    }

    private const val TAG = "EA"
    private const val KEEP_BACKUPS = 5
    private const val MASS_REMOVE_MIN = 3
    private const val BACKUP_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val REMEMBER_MS = 10L * 60 * 1000
    private const val LOCK_LIMIT_MS = 4L * 60 * 1000

    private val EMPTY_SLOT_FORMAT: Md5Format? = Md5Format.MAXIMA

    private val SHAPE_HEX_LOWER = Regex("[0-9a-f]{32}")
    private val SHAPE_HEX_UPPER = Regex("[0-9A-F]{32}")
    private val SHAPE_BASE64 = Regex("[A-Za-z0-9+/]{22}==")
    private val SHAPE_MAXIMA_PADDED = Regex("[0-9]+=+")
    private val SHAPE_MAXIMA_LONG = Regex("[0-9]{24,39}")

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val mutexes = ConcurrentHashMap<Int, Mutex>()
    private val remembered = ConcurrentHashMap<Int, Remembered>()

    @Volatile internal var lockLimitMs: Long = LOCK_LIMIT_MS

    suspend fun syncBeforeLaunch(
        context: Context,
        container: Container,
        steamAppId: Int,
        gameDir: File,
        preference: EaCloudPreference,
    ): PullResult = withContext(Dispatchers.IO) {
        mutexFor(steamAppId).withLock {
            sessions.remove(steamAppId)
            try {
                if (!recoverJournal(container)) return@withLock PullResult.Failed("journal_rollback")
                if (preference != EaCloudPreference.NONE) remembered[steamAppId] = Remembered(preference, now())
                val effective = if (preference != EaCloudPreference.NONE) {
                    preference
                } else {
                    remembered[steamAppId]?.takeIf { now() - it.at < REMEMBER_MS }?.preference ?: EaCloudPreference.NONE
                }
                val result = pull(context, container, steamAppId, gameDir, effective)
                if (result == PullResult.Synced && effective == EaCloudPreference.REMOTE) remembered.remove(steamAppId)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Cloud save pull failed for $steamAppId: ${describe(e)}")
                PullResult.Failed(describe(e))
            }
        }
    }

    suspend fun syncAfterExit(context: Context, container: Container, steamAppId: Int): Boolean = withContext(Dispatchers.IO) {
        mutexFor(steamAppId).withLock {
            remembered.remove(steamAppId)
            val session = sessions.remove(steamAppId)
            if (!recoverJournal(container)) return@withLock false
            if (session == null) return@withLock false
            try {
                push(context, container, steamAppId, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Cloud save push failed for $steamAppId: ${describe(e)}")
                false
            }
        }
    }

    internal fun hasSession(steamAppId: Int): Boolean = sessions.containsKey(steamAppId)

    internal fun uploadAllowed(steamAppId: Int): Boolean = sessions[steamAppId]?.uploadAllowed == true

    internal fun resetForTest() {
        sessions.clear()
        mutexes.clear()
        remembered.clear()
        lockLimitMs = LOCK_LIMIT_MS
    }

    internal fun encodeMd5(format: Md5Format, digest: ByteArray): String = when (format) {
        Md5Format.HEX -> EaCrypto.hex(digest)
        Md5Format.HEX_UPPER -> EaCrypto.hex(digest).uppercase()
        Md5Format.BASE64 -> Base64.encodeToString(digest, Base64.NO_WRAP)
        Md5Format.MAXIMA -> BigInteger(1, digest.reversedArray()).toString().padEnd(24, '=')
    }

    internal fun recognise(md5: String, digest: ByteArray): Md5Format? {
        val hex = EaCrypto.hex(digest)
        return when {
            md5 == hex -> Md5Format.HEX
            md5.equals(hex, ignoreCase = true) -> Md5Format.HEX_UPPER
            md5 == encodeMd5(Md5Format.BASE64, digest) -> Md5Format.BASE64
            md5 == encodeMd5(Md5Format.MAXIMA, digest) -> Md5Format.MAXIMA
            else -> null
        }
    }

    internal fun guessFormat(md5s: List<String?>): Md5Format? {
        val shapes = md5s.filterNotNull().map { md5 ->
            when {
                md5.length == 32 && md5.all { it in '0'..'9' } -> null
                SHAPE_HEX_LOWER.matches(md5) -> Md5Format.HEX
                SHAPE_HEX_UPPER.matches(md5) -> Md5Format.HEX_UPPER
                SHAPE_MAXIMA_PADDED.matches(md5) && md5.length == 24 -> Md5Format.MAXIMA
                SHAPE_MAXIMA_LONG.matches(md5) -> Md5Format.MAXIMA
                SHAPE_BASE64.matches(md5) -> Md5Format.BASE64
                else -> null
            }
        }.distinct()
        return shapes.singleOrNull()
    }

    internal fun matches(entry: EaCloudFile, size: Long, digest: ByteArray): Boolean {
        if (entry.size != size) return false
        val md5 = entry.md5 ?: return entry.href == "$size-${EaCrypto.hex(digest)}"
        return recognise(md5, digest) != null
    }

    private suspend fun pull(
        context: Context,
        container: Container,
        steamAppId: Int,
        gameDir: File,
        preference: EaCloudPreference,
    ): PullResult {
        if (!EaAuthManager.isLoggedIn(context)) return PullResult.Failed("not_signed_in")
        val target = EaCloudSaveConfig.resolve(context, contentIds(gameDir)) ?: return PullResult.NoCloudSaves
        val driveC = File(container.rootDir, ".wine/drive_c")
        val state = loadState(container)
        val lock = EaCloudSyncApi.acquire(context, target.cloudId, EaCloudLockMode.READ)
        val acquiredAt = now()
        try {
            val manifest = EaCloudSyncApi.fetchManifest(lock)
            if (!manifest.exists && state?.entries?.isNotEmpty() == true) return PullResult.Failed("manifest_missing")
            val view = viewCloud(target, driveC, manifest)
            val local = scanLocal(target, driveC).filterKeys { it !in view.ambiguous }
            val cloud = cloudKeys(view.entries, local, state)
            val base = state?.entries?.filterKeys { it !in view.ambiguous }?.mapValues { it.value.key }?.takeIf { local.isNotEmpty() }
            val plan = EaCloudSyncPlanner.planPull(local.mapValues { it.value.key }, cloud, base, preference)
            if (plan.conflict) {
                Timber.tag(TAG).i("Cloud save conflict for $steamAppId (${local.size} local, ${cloud.size} cloud)")
                return PullResult.Conflict(local.values.maxOfOrNull { it.modified } ?: 0L, manifest.lastModifiedMillis)
            }
            if (plan.removeLocal.size >= MASS_REMOVE_MIN && plan.removeLocal.size * 2 > local.size) {
                Timber.tag(TAG).w("Cloud save pull for $steamAppId would remove ${plan.removeLocal.size} of ${local.size} local saves, skipped")
                return PullResult.Failed("mass_delete_guard")
            }

            val probe = FormatProbe()
            val downloads = LinkedHashMap<String, Download>()
            val tmpDir = File(container.rootDir, ".ea_cloud/tmp")
            try {
                tmpDir.deleteRecursively()
                if (plan.download.isNotEmpty()) {
                    val wanted = plan.download.map { name ->
                        name to (view.entries[name] ?: throw EaCloudSyncException("EA cloud pull: planned download without cloud entry"))
                    }
                    val urls = EaCloudSyncApi.authorizeDownloads(context, lock, wanted.map { it.second.href })
                    wanted.forEachIndexed { index, (name, entry) ->
                        val dest = EaCloudSaveConfig.toFile(entry.localName, driveC)?.takeIf { !it.isDirectory }
                            ?: throw EaCloudSyncException("EA cloud pull: no local path for cloud entry")
                        val url = urls[entry.href] ?: throw EaCloudSyncException("EA cloud pull: no download URL for cloud entry")
                        val temp = File(tmpDir, "$index.part")
                        val (size, digest) = EaCloudSyncApi.download(url, temp)
                        if (size != entry.size) throw EaCloudSyncException("EA cloud pull: downloaded size $size differs from manifest ${entry.size}")
                        val md5 = entry.md5
                        val known = probe.format ?: state?.format
                        if (md5 != null && known != null && !verifies(known, md5, digest)) {
                            throw EaCloudSyncException("EA cloud pull: downloaded content does not match the manifest md5")
                        }
                        probe.observe(entry, digest)
                        downloads[name] = Download(temp, dest, localKey(size, digest))
                    }
                }
                if (now() - acquiredAt > lockLimitMs) return PullResult.Failed("lock_timeout")
                applyPull(container, driveC, downloads.values, plan.removeLocal.mapNotNull { local[it]?.file })
            } finally {
                tmpDir.deleteRecursively()
            }

            val synced = LinkedHashMap<String, StateEntry>()
            for ((name, entry) in view.entries) {
                val download = downloads[name]
                val mine = local[name]
                if (download != null) {
                    synced[name] = StateEntry(download.key, identity(entry), entry.localName)
                } else if (mine != null && mine.key == cloud[name]) {
                    probe.observe(entry, mine.digest)
                    synced[name] = StateEntry(mine.key, identity(entry), entry.localName)
                }
            }
            if (base != null) {
                for ((name, old) in state?.entries.orEmpty()) {
                    if (name !in synced && view.entries[name]?.let { identity(it) } == old.cloudIdentity) synced[name] = old
                }
            }
            for (name in view.ambiguous) state?.entries?.get(name)?.let { synced[name] = it }
            saveState(container, State(probe.format ?: state?.format, synced))

            val format = when {
                probe.unrecognised -> null
                manifest.files.isEmpty() -> state?.format ?: EMPTY_SLOT_FORMAT
                else -> probe.format ?: state?.format ?: guessFormat(manifest.files.map { it.md5 })
            }
            val uploadAllowed = !view.duplicates && format != null
            sessions[steamAppId] = Session(
                target = target,
                cloudAtPull = cloud.mapValues { (name, key) -> synced[name]?.key ?: key },
                entries = view.entries,
                forcedNames = plan.forcedNames,
                uploadAllowed = uploadAllowed,
                format = format,
            )
            Timber.tag(TAG).i(
                "Cloud save pull for $steamAppId: ${downloads.size} downloaded, ${plan.removeLocal.size} removed, " +
                    "${view.ignored} ignored, uploadPending=${plan.uploadPending}, uploadAllowed=$uploadAllowed",
            )
            return PullResult.Synced
        } finally {
            EaCloudSyncApi.release(context, lock)
        }
    }

    private suspend fun push(context: Context, container: Container, steamAppId: Int, session: Session): Boolean {
        val format = session.format
        if (!session.uploadAllowed || format == null) {
            Timber.tag(TAG).w("Cloud save push skipped for $steamAppId: cloud md5 format not known")
            return false
        }
        val driveC = File(container.rootDir, ".wine/drive_c")
        val state = loadState(container)
        val lock = EaCloudSyncApi.acquire(context, session.target.cloudId, EaCloudLockMode.WRITE)
        val acquiredAt = now()
        try {
            val manifest = EaCloudSyncApi.fetchManifest(lock)
            if (!manifest.exists && state?.entries?.isNotEmpty() == true) {
                Timber.tag(TAG).w("Cloud save push skipped for $steamAppId: manifest_missing")
                return false
            }
            val local = scanLocal(session.target, driveC)
            val view = viewCloud(session.target, driveC, manifest)
            if (view.duplicates) {
                Timber.tag(TAG).w("Cloud save push skipped for $steamAppId: duplicate cloud names")
                return false
            }
            val fresh = cloudKeys(view.entries, local, state)
            val cloud = view.entries.mapValues { (name, entry) ->
                val seen = session.entries[name]
                val pulled = session.cloudAtPull[name]
                if (seen != null && pulled != null && identity(seen) == identity(entry)) pulled else fresh.getValue(name)
            }
            val plan = EaCloudSyncPlanner.planPush(
                local = local.mapValues { it.value.key },
                cloud = cloud,
                base = state?.entries?.mapValues { it.value.key },
                cloudAtPull = session.cloudAtPull,
                forcedNames = session.forcedNames,
            )
            if (plan.skip) {
                Timber.tag(TAG).i("Cloud save push skipped for $steamAppId: ${plan.reason}")
                return plan.reason == EaCloudSyncPlanner.REASON_NO_CHANGES
            }

            val created = LinkedHashMap<String, EaCloudFile>()
            for (name in plan.upload) {
                val mine = local[name] ?: throw EaCloudSyncException("EA cloud push: planned upload without local file")
                created[name] = EaCloudFile(
                    href = "${mine.size}-${EaCrypto.hex(mine.digest)}",
                    size = mine.size,
                    md5 = encodeMd5(format, mine.digest),
                    localName = view.entries[name]?.localName ?: mine.localName,
                )
            }
            if (plan.manifest.keys.any { it !in created && it !in view.entries }) {
                throw EaCloudSyncException("EA cloud push: planned manifest entry without source")
            }
            val files = ArrayList<EaCloudFile>()
            for (entry in manifest.files) {
                val name = EaCloudSaveConfig.normalizeName(entry.localName)
                when {
                    view.entries[name] !== entry -> files += entry
                    name in created -> files += created.getValue(name)
                    name in plan.manifest -> files += entry
                }
            }
            for ((name, entry) in created) if (name !in view.entries) files += entry

            val uploads = created.entries.distinctBy { it.value.href }
            val urls = EaCloudSyncApi.authorizeUploads(
                context,
                lock,
                uploads.map { EaCloudUpload(it.value.href, it.value.md5, null) } +
                    EaCloudUpload(EaCloudSyncApi.MANIFEST_RESOURCE, null, "text/xml"),
            )
            val manifestUrl = urls[EaCloudSyncApi.MANIFEST_RESOURCE] ?: throw EaCloudSyncException("EA cloud push: no upload URL for manifest")
            for ((name, entry) in uploads) {
                val url = urls[entry.href] ?: throw EaCloudSyncException("EA cloud push: no upload URL for file")
                EaCloudSyncApi.uploadFile(url, local.getValue(name).file)
            }
            for (name in created.keys) {
                if (!unchangedSince(local.getValue(name))) {
                    Timber.tag(TAG).w("Cloud save push aborted for $steamAppId: a save file changed during upload")
                    return false
                }
            }
            if (now() - acquiredAt > lockLimitMs) {
                Timber.tag(TAG).w("Cloud save push aborted for $steamAppId: lock_timeout")
                return false
            }
            EaCloudSyncApi.uploadBytes(manifestUrl, EaCloudSyncApi.manifestXml(files).toByteArray())

            val synced = LinkedHashMap<String, StateEntry>()
            for (entry in files) {
                val name = EaCloudSaveConfig.normalizeName(entry.localName)
                val mine = local[name] ?: continue
                if (created[name] === entry || (view.entries[name] === entry && mine.key == cloud[name])) {
                    synced[name] = StateEntry(mine.key, identity(entry), entry.localName)
                }
            }
            saveState(container, State(format, synced))
            Timber.tag(TAG).i("Cloud save push for $steamAppId: ${uploads.size} file(s) uploaded, manifest has ${files.size} entries")
            return true
        } finally {
            EaCloudSyncApi.release(context, lock)
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun verifies(format: Md5Format, md5: String, digest: ByteArray): Boolean = when (format) {
        Md5Format.HEX, Md5Format.HEX_UPPER -> md5.equals(EaCrypto.hex(digest), ignoreCase = true)
        else -> md5 == encodeMd5(format, digest)
    }

    private fun mutexFor(steamAppId: Int): Mutex = mutexes.getOrPut(steamAppId) { Mutex() }

    private fun describe(e: Throwable): String = if (e is EaCloudSyncException) e.message.orEmpty() else e.javaClass.simpleName

    private fun contentIds(gameDir: File): List<String> =
        File(gameDir, "__Installer/installerdata.xml").takeIf { it.exists() }
            ?.let { f -> runCatching { f.readText() }.getOrNull() }
            ?.let { EaLaunchSession.parseContentIds(it) }
            .orEmpty()

    private fun identity(entry: EaCloudFile): String = "${entry.href}|${entry.size}|${entry.md5.orEmpty()}"

    private fun localKey(size: Long, digest: ByteArray): String = "$size:${EaCrypto.hex(digest)}"

    private fun scanLocal(target: EaCloudSaveTarget, driveC: File): Map<String, LocalFile> {
        val out = LinkedHashMap<String, LocalFile>()
        for ((localName, file) in EaCloudSaveConfig.localFiles(target, driveC)) {
            val modified = file.lastModified()
            val (size, digest) = hashFile(file)
            out[EaCloudSaveConfig.normalizeName(localName)] = LocalFile(localName, file, size, modified, digest)
        }
        return out
    }

    private fun hashFile(file: File): Pair<Long, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        var size = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n)
                size += n
            }
        }
        return size to md5.digest()
    }

    private fun unchangedSince(mine: LocalFile): Boolean {
        if (!mine.file.isFile || mine.file.length() != mine.size || mine.file.lastModified() != mine.modified) return false
        val (size, digest) = hashFile(mine.file)
        return size == mine.size && digest.contentEquals(mine.digest)
    }

    private fun viewCloud(target: EaCloudSaveTarget, driveC: File, manifest: EaCloudManifest): CloudView {
        val entries = LinkedHashMap<String, EaCloudFile>()
        var ignored = 0
        val ambiguous = LinkedHashSet<String>()
        for (entry in manifest.files) {
            val name = EaCloudSaveConfig.normalizeName(entry.localName)
            val dest = EaCloudSaveConfig.toFile(entry.localName, driveC)
            if (!EaCloudSaveConfig.isAllowed(target, entry.localName) || dest == null) {
                Timber.tag(TAG).w("Cloud save entry ignored (outside save criteria): ${entry.localName}")
                ignored++
            } else if (dest.isDirectory || generateSequence(dest.parentFile) { it.parentFile }.takeWhile { it != driveC }.any { it.isFile }) {
                Timber.tag(TAG).w("Cloud save entry ignored (path blocked locally): ${entry.localName}")
                ignored++
            } else if (name in entries || name in ambiguous) {
                Timber.tag(TAG).w("Cloud save entry ignored (duplicate name): ${entry.localName}")
                ignored++
                if (entries.remove(name) != null) ignored++
                ambiguous += name
            } else {
                entries[name] = entry
            }
        }
        return CloudView(entries, ignored, ambiguous)
    }

    private fun cloudKeys(entries: Map<String, EaCloudFile>, local: Map<String, LocalFile>, state: State?): Map<String, String> =
        entries.mapValues { (name, entry) ->
            val id = identity(entry)
            val known = state?.entries?.get(name)
            val mine = local[name]
            when {
                known != null && known.cloudIdentity == id -> known.key
                mine != null && matches(entry, mine.size, mine.digest) -> mine.key
                else -> "cloud:$id"
            }
        }

    private fun applyPull(container: Container, driveC: File, downloads: Collection<Download>, removals: List<File>) {
        if (downloads.isEmpty() && removals.isEmpty()) return
        val backupRoot = File(container.rootDir, ".ea_cloud/backup")
        val backupDir = File(backupRoot, System.currentTimeMillis().toString())
        fun backupOf(file: File): File {
            val relative = file.toRelativeString(driveC).takeIf { !it.startsWith("..") && !File(it).isAbsolute } ?: file.name
            return File(backupDir, relative)
        }
        val steps = ArrayList<Pair<Op, File?>>()
        for (download in downloads) {
            val backup = if (download.dest.exists()) backupOf(download.dest) else null
            steps += Op(download.dest, backup, true, download.temp.length(), download.temp.lastModified()) to download.temp
        }
        for (file in removals) if (file.exists()) steps += Op(file, backupOf(file), false) to null
        val ops = steps.map { it.first }
        val journal = journalFile(container)
        writeJournal(journal, ops)
        try {
            for ((op, temp) in steps) {
                if (op.backup != null) move(op.target, op.backup)
                if (temp != null) {
                    op.target.parentFile?.mkdirs()
                    move(temp, op.target)
                }
            }
        } catch (e: Throwable) {
            runCatching {
                rollback(ops)
                journal.delete()
            }
            throw e
        }
        journal.delete()
        val now = System.currentTimeMillis()
        backupRoot.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name.toLongOrNull() ?: 0L }
            ?.drop(KEEP_BACKUPS)
            ?.filter { now - (it.name.toLongOrNull() ?: 0L) > BACKUP_MIN_AGE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw EaCloudSyncException("EA cloud pull: could not move a save file")
    }

    private fun rollback(ops: List<Op>) {
        for (op in ops.asReversed()) {
            val backup = op.backup
            if (backup != null) {
                if (!backup.exists()) continue
                if (op.target.exists()) {
                    if (!op.placed) continue
                    if (op.rewritten()) {
                        Timber.tag(TAG).w("Cloud save rollback kept a save that changed after it was placed: ${op.target.name}")
                        continue
                    }
                    if (!op.target.delete()) throw EaCloudSyncException("EA cloud pull: could not remove a placed save")
                }
                move(backup, op.target)
            } else if (op.placed && op.target.exists()) {
                if (op.rewritten()) {
                    Timber.tag(TAG).w("Cloud save rollback kept a save that changed after it was placed: ${op.target.name}")
                } else if (!op.target.delete()) {
                    throw EaCloudSyncException("EA cloud pull: could not remove a placed save")
                }
            }
        }
    }

    private fun journalFile(container: Container) = File(container.rootDir, ".ea_cloud/apply.json")

    private fun writeJournal(journal: File, ops: List<Op>) {
        val array = JSONArray()
        for (op in ops) {
            val entry = JSONObject().put("target", op.target.path).put("placed", op.placed).put("size", op.size).put("modified", op.modified)
            if (op.backup != null) entry.put("backup", op.backup.path)
            array.put(entry)
        }
        journal.parentFile?.mkdirs()
        val tmp = File(journal.parentFile, journal.name + ".tmp")
        tmp.writeText(JSONObject().put("ops", array).toString())
        if (!tmp.renameTo(journal)) throw EaCloudSyncException("EA cloud pull: could not write the apply journal")
    }

    private fun recoverJournal(container: Container): Boolean {
        val journal = journalFile(container)
        if (!journal.exists()) return true
        return try {
            val array = JSONObject(journal.readText()).getJSONArray("ops")
            val ops = (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                Op(
                    File(entry.getString("target")),
                    entry.optString("backup").takeIf { it.isNotEmpty() }?.let { File(it) },
                    entry.getBoolean("placed"),
                    entry.optLong("size", -1),
                    entry.optLong("modified", -1),
                )
            }
            rollback(ops)
            if (!journal.delete()) throw EaCloudSyncException("EA cloud pull: could not delete the apply journal")
            Timber.tag(TAG).w("Cloud save apply journal found, rolled back ${ops.size} step(s)")
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w("Cloud save apply journal rollback failed: ${describe(e)}")
            false
        }
    }

    private fun stateFile(container: Container) = File(container.rootDir, ".ea_cloud/state.json")

    private fun loadState(container: Container): State? = runCatching {
        val file = stateFile(container)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val format = json.optString("format").takeIf { it.isNotEmpty() }?.let { name -> Md5Format.entries.firstOrNull { it.name == name } }
        val files = json.getJSONObject("files")
        val entries = LinkedHashMap<String, StateEntry>()
        for (name in files.keys()) {
            val entry = files.getJSONObject(name)
            entries[name] = StateEntry(entry.getString("key"), entry.getString("cloudIdentity"), entry.getString("localName"))
        }
        State(format, entries)
    }.getOrElse {
        Timber.tag(TAG).w("Cloud save state is unreadable (${it.javaClass.simpleName})")
        null
    }

    private fun saveState(container: Container, state: State) {
        val files = JSONObject()
        for ((name, entry) in state.entries) {
            files.put(name, JSONObject().put("key", entry.key).put("cloudIdentity", entry.cloudIdentity).put("localName", entry.localName))
        }
        val json = JSONObject().put("format", state.format?.name.orEmpty()).put("files", files)
        val file = stateFile(container)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }
}
