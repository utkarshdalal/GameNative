package app.gamenative.service.rockstar

import android.content.Context
import android.os.SystemClock
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
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

/** Pulls Rockstar Games Launcher cloud saves into the Wine prefix before launch and pushes local changes back after exit. */
object RockstarCloudSavesManager {
    sealed interface PullResult {
        data object Synced : PullResult
        data object NoCloudSaves : PullResult
        data class Conflict(val localMillis: Long, val remoteMillis: Long?) : PullResult
        data class Failed(val reason: String) : PullResult
    }

    private class LocalFile(val file: File, val size: Long, val modified: Long, val md5: String) {
        val state get() = RockstarLocalState(md5, size)
    }

    private class State(val titleId: String, val profile: String, val entries: Map<String, RockstarSyncedState>)

    private class Download(val temp: File, val dest: File)

    private class Session(
        val titleId: String,
        val rosTitleId: Int,
        val saveFolderName: String,
        val names: List<String>,
        val profileDir: File,
        val remote: Map<String, RockstarCloudFile>,
        val forcedLocal: Set<String>,
        val dateSample: String?,
        val rockstarId: String?,
    )

    private class Remembered(val preference: RockstarCloudPreference, val at: Long)

    private class Op(val target: File, val backup: File?, val placed: Boolean, val size: Long = -1, val modified: Long = -1) {
        fun rewritten(): Boolean = placed && size >= 0 && (target.length() != size || target.lastModified() != modified)
    }

    private const val TAG = "RockstarCloud"
    private const val KEEP_BACKUPS = 5
    private const val BACKUP_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val REMEMBER_MS = 10L * 60 * 1000
    private const val METADATA_FILE = "cloudsavedata.dat"
    private val PROFILE_DIR = Regex("[0-9A-Fa-f]{8}")

    private val sessions = ConcurrentHashMap<String, Session>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val remembered = ConcurrentHashMap<String, Remembered>()

    suspend fun syncBeforeLaunch(
        context: Context,
        container: Container,
        gameDir: File,
        preference: RockstarCloudPreference,
    ): PullResult = withContext(Dispatchers.IO) {
        val key = gameDir.absolutePath
        mutexFor(key).withLock {
            sessions.remove(key)
            try {
                if (!recoverJournal(container)) return@withLock PullResult.Failed("journal_rollback")
                if (preference != RockstarCloudPreference.NONE) remembered[key] = Remembered(preference, now())
                val effective = if (preference != RockstarCloudPreference.NONE) {
                    preference
                } else {
                    remembered[key]?.takeIf { now() - it.at < REMEMBER_MS }?.preference ?: RockstarCloudPreference.NONE
                }
                val result = pull(context, container, key, gameDir, effective)
                if (result == PullResult.Synced && effective == RockstarCloudPreference.REMOTE) remembered.remove(key)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Cloud save pull failed for ${gameDir.name}: ${describe(e)}")
                PullResult.Failed(describe(e))
            }
        }
    }

    suspend fun syncAfterExit(context: Context, container: Container, gameDir: File): Boolean = withContext(Dispatchers.IO) {
        val key = gameDir.absolutePath
        mutexFor(key).withLock {
            remembered.remove(key)
            val session = sessions.remove(key) ?: return@withLock false
            try {
                if (!recoverJournal(container)) return@withLock false
                push(context, container, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Cloud save push failed for ${gameDir.name}: ${describe(e)}")
                false
            }
        }
    }

    fun forgetSession(gameDir: File) {
        sessions.remove(gameDir.absolutePath)
    }

    internal fun hasSession(gameDir: File): Boolean = sessions.containsKey(gameDir.absolutePath)

    internal fun resetForTest() {
        sessions.clear()
        mutexes.clear()
        remembered.clear()
    }

    private suspend fun pull(
        context: Context,
        container: Container,
        key: String,
        gameDir: File,
        preference: RockstarCloudPreference,
    ): PullResult {
        val credentials = RockstarAuthManager.load(context)
        if (credentials == null || credentials.scAuthToken.isBlank()) {
            Timber.tag(TAG).i("Cloud save pull skipped for ${gameDir.name}: not signed in to Rockstar")
            return PullResult.NoCloudSaves
        }
        val rgl = RockstarTitleMetadata.find(gameDir)
        val title = rgl?.let { RockstarTitleMetadata.parse(it) }
        if (title == null) {
            Timber.tag(TAG).i("Cloud save pull skipped for ${gameDir.name}: no readable title.rgl")
            return PullResult.NoCloudSaves
        }
        if (title.cloudSaveCompression) {
            Timber.tag(TAG).i("Cloud save pull skipped for ${title.titleId}: compressed cloud saves are not supported")
            return PullResult.NoCloudSaves
        }
        if (!title.cloudEnabled()) {
            Timber.tag(TAG).i("Cloud save pull skipped for ${title.titleId}: cloud saves disabled for platform ${title.gamePlatform}")
            return PullResult.NoCloudSaves
        }
        val saveFolderName = title.saveFolderName
        val names = saveNames(title)
        if (saveFolderName.isNullOrBlank() || saveFolderName.contains("..") || names.isEmpty()) {
            Timber.tag(TAG).i("Cloud save pull skipped for ${title.titleId}: no save folder or save file list")
            return PullResult.NoCloudSaves
        }
        if (title.cloudSaveRoot != null) Timber.tag(TAG).i("title.rgl cloudSaveRoot=${title.cloudSaveRoot}; saves are read from Documents")

        val session = RockstarCloudApi.createLauncherTicket(credentials.scAuthToken)
        val accessToken = RockstarCloudApi.titleAccessToken(session, title.rosTitleId)
        val manifest = RockstarCloudApi.manifest(session, accessToken)
        val accountId = session.rockstarId ?: credentials.rockstarId.takeIf { it.isNotBlank() }
        if (manifest.rockstarId == null) {
            Timber.tag(TAG).w("Manifest for ${title.titleId} carries no RockstarId; account check skipped")
        } else if (accountId != null && manifest.rockstarId != accountId) {
            Timber.tag(TAG).w("Manifest for ${title.titleId} belongs to a different Rockstar account")
            return PullResult.Failed("account_mismatch")
        }
        val rockstarId = accountId ?: manifest.rockstarId

        val remote = remoteByName(names, manifest)
        val savesRoot = savesRoot(container, saveFolderName)
        val profileDir = locateProfile(savesRoot)
        if (profileDir == null) {
            Timber.tag(TAG).i("Cloud save pull skipped for ${title.titleId}: no profile directory yet and no signed-in Social Club profile to name one; syncing starts once the game has created one")
            return PullResult.NoCloudSaves
        }
        Timber.tag(TAG).i("Cloud save profile for ${title.titleId}: ${profileDir.name} (exists=${profileDir.isDirectory})")

        val local = scanLocal(profileDir, names)
        val state = loadState(container, title.titleId, profileDir)
        val synced = state?.entries.orEmpty()
        val plan = RockstarCloudSyncPlanner.planPull(
            names,
            local.mapValues { it.value.state },
            remote.mapValues { RockstarRemoteState(it.value.version, it.value.md5) },
            synced,
            preference,
        )
        if (plan.conflict) {
            Timber.tag(TAG).i("Cloud save conflict for ${title.titleId}: ${plan.conflicts.size} file(s), state=${state != null}")
            val localMillis = plan.conflicts.mapNotNull { local[it]?.modified }.maxOrNull() ?: 0L
            val remoteMillis = plan.conflicts.mapNotNull { RockstarCloudSyncPlanner.parseTimestamp(remote[it]?.serverLastModifiedUtc) }.maxOrNull()
            return PullResult.Conflict(localMillis, remoteMillis)
        }

        val downloads = LinkedHashMap<String, Download>()
        val fetched = LinkedHashMap<String, RockstarCloudDownload>()
        val tmpDir = File(container.rootDir, ".rockstar_cloud/tmp")
        try {
            tmpDir.deleteRecursively()
            for ((index, name) in plan.download.withIndex()) {
                val entry = remote[name] ?: throw RockstarCloudException("Rockstar cloud pull: planned download without manifest entry")
                val temp = File(tmpDir, "$index.part")
                val got = RockstarCloudApi.getFile(context, session, accessToken, entry.id, temp)
                if (entry.size >= 0 && got.size != entry.size) {
                    throw RockstarCloudException("Rockstar cloud pull: downloaded size ${got.size} differs from manifest ${entry.size}")
                }
                if (entry.md5 != null && entry.md5 != got.md5) {
                    throw RockstarCloudException("Rockstar cloud pull: downloaded content does not match the manifest MD5Hash")
                }
                downloads[name] = Download(temp, File(profileDir, local[name]?.file?.name ?: name))
                fetched[name] = got
            }
            applyPull(container, savesRoot, downloads.values)
        } finally {
            tmpDir.deleteRecursively()
        }

        val next = LinkedHashMap(synced)
        for (name in names) {
            val entry = remote[name] ?: continue
            val got = fetched[name]
            val mine = local[name]
            when {
                got != null -> next[name] = RockstarSyncedState(entry.version, got.md5, got.size, entry.serverLastModifiedUtc)
                mine != null && entry.md5 != null && entry.md5 == mine.md5 ->
                    next[name] = RockstarSyncedState(entry.version, mine.md5, mine.size, entry.serverLastModifiedUtc)
            }
        }
        saveState(container, State(title.titleId, profileDir.absolutePath, next))

        sessions[key] = Session(
            titleId = title.titleId,
            rosTitleId = title.rosTitleId,
            saveFolderName = saveFolderName,
            names = names,
            profileDir = profileDir,
            remote = remote,
            forcedLocal = plan.forcedLocal,
            dateSample = manifest.files.firstNotNullOfOrNull { it.serverLastModifiedUtc },
            rockstarId = rockstarId,
        )
        Timber.tag(TAG).i(
            "Cloud save pull for ${title.titleId}: ${downloads.size} downloaded, ${plan.upload.size} pending upload, " +
                "${remote.size} in cloud, ${local.size} local",
        )
        return PullResult.Synced
    }

    private suspend fun push(context: Context, container: Container, session: Session): Boolean {
        val credentials = RockstarAuthManager.load(context)
        if (credentials == null || credentials.scAuthToken.isBlank()) {
            Timber.tag(TAG).w("Cloud save push skipped for ${session.titleId}: not signed in to Rockstar")
            return false
        }
        val profileDir = session.profileDir.takeIf { it.isDirectory }
            ?: locateProfile(savesRoot(container, session.saveFolderName))
        if (profileDir == null) {
            Timber.tag(TAG).i("Cloud save push for ${session.titleId}: no profile directory, nothing to upload")
            return true
        }
        val local = scanLocal(profileDir, session.names)
        val state = loadState(container, session.titleId, profileDir)
        val synced = LinkedHashMap(state?.entries.orEmpty())
        val uploads = RockstarCloudSyncPlanner.planPush(session.names, local.mapValues { it.value.state }, synced)
        if (uploads.isEmpty()) {
            Timber.tag(TAG).i("Cloud save push for ${session.titleId}: no changes")
            return true
        }

        val ticket = RockstarCloudApi.createLauncherTicket(credentials.scAuthToken)
        if (ticket.rockstarId != null && session.rockstarId != null && ticket.rockstarId != session.rockstarId) {
            Timber.tag(TAG).w("Cloud save push skipped for ${session.titleId}: Rockstar account changed since launch")
            return false
        }
        val accessToken = RockstarCloudApi.titleAccessToken(ticket, session.rosTitleId)
        val metadataFile = profileDir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.equals(METADATA_FILE, ignoreCase = true) }
        val metadata = metadataFile?.let { RockstarCloudSaveData.readMetadata(it) }.orEmpty()
        var remote = session.remote
        var refreshed = false
        var ok = true
        var uploaded = 0

        for (name in uploads) {
            val mine = local.getValue(name)
            val resolve = if (name in session.forcedLocal) RockstarResolveType.ACCEPT_LOCAL else RockstarResolveType.NONE
            val lastModified = RockstarCloudSyncPlanner.formatTimestamp(session.dateSample, mine.modified)
            val fileMetadata = metadata.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: "{}"
            if (fileMetadata == "{}") Timber.tag(TAG).w("No cloudsavedata.dat metadata for $name, uploading with {}")
            var expected = remote[name]?.nextVersion ?: 1L
            Timber.tag(TAG).i("PostFile $name: expectedVersion=$expected resolveType=${resolve.wire} lastModified=$lastModified")
            val posted = try {
                RockstarCloudApi.postFile(context, ticket, accessToken, mine.file, name, expected, lastModified, fileMetadata, resolve)
            } catch (e: RockstarCloudException) {
                if (e.code?.startsWith("InvalidVersion", ignoreCase = true) != true) {
                    Timber.tag(TAG).w("PostFile $name failed: ${e.message}")
                    ok = false
                    if (fatal(e)) break
                    continue
                }
                if (!refreshed) {
                    remote = remoteByName(session.names, RockstarCloudApi.manifest(ticket, accessToken))
                    refreshed = true
                }
                val now = remote[name]
                val known = synced[name]
                if (now == null || known == null || now.md5 == null || now.md5 != known.md5) {
                    Timber.tag(TAG).w("PostFile $name rejected (${e.code}); the cloud copy changed since the last sync, left as is")
                    ok = false
                    continue
                }
                expected = now.nextVersion
                Timber.tag(TAG).i("PostFile $name retry with expectedVersion=$expected")
                try {
                    RockstarCloudApi.postFile(context, ticket, accessToken, mine.file, name, expected, lastModified, fileMetadata, resolve)
                } catch (retry: RockstarCloudException) {
                    Timber.tag(TAG).w("PostFile $name retry failed: ${retry.message}")
                    ok = false
                    if (fatal(retry)) break
                    continue
                }
            }
            if (!unchangedSince(mine)) {
                Timber.tag(TAG).w("Save $name changed during upload; it will upload again next time")
                ok = false
                continue
            }
            synced[name] = RockstarSyncedState(posted.version ?: expected, mine.md5, mine.size, posted.serverLastModifiedUtc ?: lastModified)
            saveState(container, State(session.titleId, profileDir.absolutePath, synced))
            uploaded++
        }
        Timber.tag(TAG).i("Cloud save push for ${session.titleId}: $uploaded of ${uploads.size} file(s) uploaded")
        return ok
    }

    private fun fatal(e: RockstarCloudException): Boolean {
        val code = e.code.orEmpty()
        return code.startsWith("NotAllowed", ignoreCase = true) || e.httpStatus == 401 || e.httpStatus == 403
    }

    private fun saveNames(title: RockstarTitleMetadata): List<String> =
        title.cloudSaveFiles.map { it.trim() }.filter { name ->
            val usable = name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..") &&
                !name.equals(METADATA_FILE, ignoreCase = true)
            if (!usable && name.isNotEmpty()) Timber.tag(TAG).w("Cloud save file name ignored: $name")
            usable
        }.distinctBy { it.lowercase() }

    private fun remoteByName(names: List<String>, manifest: RockstarCloudManifest): Map<String, RockstarCloudFile> {
        val byLower = names.associateBy { it.lowercase() }
        val out = LinkedHashMap<String, RockstarCloudFile>()
        val duplicates = HashSet<String>()
        for (file in manifest.files) {
            val leaf = file.path.substringAfterLast('/').substringAfterLast('\\')
            val name = byLower[leaf.lowercase()]
            if (name == null) {
                Timber.tag(TAG).d("Cloud save entry outside cloudSaveFiles ignored: ${file.path}")
                continue
            }
            if (name in out || name in duplicates) {
                Timber.tag(TAG).w("Cloud save entry ignored (duplicate name): ${file.path}")
                out.remove(name)
                duplicates += name
                continue
            }
            out[name] = file
        }
        return out
    }

    private fun savesRoot(container: Container, saveFolderName: String): File =
        File(container.rootDir, ".wine/drive_c/users/${ImageFs.USER}/Documents/Rockstar Games/$saveFolderName/Profiles")

    private fun locateProfile(savesRoot: File): File? {
        val existing = savesRoot.listFiles().orEmpty().filter { it.isDirectory && PROFILE_DIR.matches(it.name) }
        if (existing.size == 1) return existing[0]
        val signedIn = socialClubProfiles(savesRoot)
        if (existing.isEmpty()) {
            val id = signedIn.singleOrNull() ?: return null
            return File(savesRoot, id)
        }
        val matching = existing.filter { dir -> signedIn.any { it.equals(dir.name, ignoreCase = true) } }
        if (matching.size == 1) return matching[0]
        Timber.tag(TAG).w("${existing.size} profile directories under ${savesRoot.parentFile?.name} and no unique match")
        return null
    }

    private fun socialClubProfiles(savesRoot: File): List<String> {
        val documents = savesRoot.parentFile?.parentFile?.parentFile ?: return emptyList()
        val roots = documents.listFiles().orEmpty().filter { it.isDirectory && (it.name == "Rockstar Games" || it.name.startsWith("Rockstar Games ")) }
        return roots.flatMap { root ->
            val base = if (root.name == "Rockstar Games") root else File(root, "Rockstar Games")
            File(base, "Social Club/Profiles").listFiles().orEmpty()
                .filter { it.isDirectory && PROFILE_DIR.matches(it.name) && it.name != "00000000" }
                .map { it.name.uppercase() }
        }.distinct()
    }

    private fun scanLocal(profileDir: File, names: List<String>): Map<String, LocalFile> {
        val files = profileDir.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name.lowercase() }
        val out = LinkedHashMap<String, LocalFile>()
        for (name in names) {
            val file = files[name.lowercase()] ?: continue
            val modified = file.lastModified()
            val (size, md5) = hashFile(file)
            out[name] = LocalFile(file, size, modified, md5)
        }
        return out
    }

    private fun hashFile(file: File): Pair<Long, String> {
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
        return size to RockstarCloudApi.hex(md5.digest())
    }

    private fun unchangedSince(mine: LocalFile): Boolean {
        if (!mine.file.isFile || mine.file.length() != mine.size || mine.file.lastModified() != mine.modified) return false
        val (size, md5) = hashFile(mine.file)
        return size == mine.size && md5 == mine.md5
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun mutexFor(key: String): Mutex = mutexes.getOrPut(key) { Mutex() }

    private fun describe(e: Throwable): String = if (e is RockstarCloudException) e.message.orEmpty() else e.javaClass.simpleName

    private fun applyPull(container: Container, savesRoot: File, downloads: Collection<Download>) {
        if (downloads.isEmpty()) return
        val backupRoot = File(container.rootDir, ".rockstar_cloud/backups")
        val backupDir = File(backupRoot, System.currentTimeMillis().toString())
        fun backupOf(file: File): File {
            val relative = file.toRelativeString(savesRoot).takeIf { !it.startsWith("..") && !File(it).isAbsolute } ?: file.name
            return File(backupDir, relative)
        }
        val steps = ArrayList<Pair<Op, File>>()
        for (download in downloads) {
            val backup = if (download.dest.exists()) backupOf(download.dest) else null
            steps += Op(download.dest, backup, true, download.temp.length(), download.temp.lastModified()) to download.temp
        }
        val ops = steps.map { it.first }
        val journal = journalFile(container)
        writeJournal(journal, ops)
        try {
            for ((op, temp) in steps) {
                if (op.backup != null) move(op.target, op.backup)
                op.target.parentFile?.mkdirs()
                move(temp, op.target)
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
        if (!from.renameTo(to)) throw RockstarCloudException("Rockstar cloud pull: could not move a save file")
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
                    if (!op.target.delete()) throw RockstarCloudException("Rockstar cloud pull: could not remove a placed save")
                }
                move(backup, op.target)
            } else if (op.placed && op.target.exists()) {
                if (op.rewritten()) {
                    Timber.tag(TAG).w("Cloud save rollback kept a save that changed after it was placed: ${op.target.name}")
                } else if (!op.target.delete()) {
                    throw RockstarCloudException("Rockstar cloud pull: could not remove a placed save")
                }
            }
        }
    }

    private fun journalFile(container: Container) = File(container.rootDir, ".rockstar_cloud/apply.json")

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
        if (!tmp.renameTo(journal)) throw RockstarCloudException("Rockstar cloud pull: could not write the apply journal")
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
            if (!journal.delete()) throw RockstarCloudException("Rockstar cloud pull: could not delete the apply journal")
            Timber.tag(TAG).w("Cloud save apply journal found, rolled back ${ops.size} step(s)")
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w("Cloud save apply journal rollback failed: ${describe(e)}")
            false
        }
    }

    private fun stateFile(container: Container) = File(container.rootDir, ".rockstar_cloud/state.json")

    private fun loadState(container: Container, titleId: String, profileDir: File): State? = runCatching {
        val file = stateFile(container)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val state = State(
            json.optString("titleId"),
            json.optString("profile"),
            LinkedHashMap<String, RockstarSyncedState>().apply {
                val files = json.getJSONObject("files")
                for (name in files.keys()) {
                    val entry = files.getJSONObject(name)
                    put(
                        name,
                        RockstarSyncedState(
                            entry.getLong("version"),
                            entry.getString("md5"),
                            entry.getLong("size"),
                            entry.optString("serverModified").takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            },
        )
        if (state.titleId != titleId || state.profile != profileDir.absolutePath) {
            Timber.tag(TAG).i("Cloud save state belongs to another title or profile; starting without it")
            return null
        }
        state
    }.getOrElse {
        Timber.tag(TAG).w("Cloud save state is unreadable (${it.javaClass.simpleName})")
        null
    }

    private fun saveState(container: Container, state: State) {
        val files = JSONObject()
        for ((name, entry) in state.entries) {
            files.put(
                name,
                JSONObject().put("version", entry.version).put("md5", entry.md5).put("size", entry.size)
                    .put("serverModified", entry.serverModified.orEmpty()),
            )
        }
        val json = JSONObject().put("titleId", state.titleId).put("profile", state.profile).put("files", files)
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
