package app.gamenative.service.ubisoft

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.gamenative.PrefManager
import app.gamenative.utils.CloudSaveBackupManager
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Pulls Ubisoft Connect cloud saves into the Wine prefix before launch and pushes local changes back
 * after exit. The cloud record is written before the bytes, and existing cloud slots are never dropped.
 */
object UbisoftCloudSavesManager {
    sealed interface PullResult {
        data object Synced : PullResult
        data object NoCloudSaves : PullResult
        data class Conflict(val localMillis: Long, val remoteMillis: Long?) : PullResult
        data class Failed(val reason: String) : PullResult
    }

    /** Where one game's saves and sync bookkeeping live for one account. */
    internal class Target(val saveDir: File, val stateDir: File, val userId: String, val uplayId: Long, val steamAppId: Int)

    /** What a sync run takes from the device. */
    internal class Env(val nowMillis: () -> Long, val pcName: String, val backup: suspend (File, List<File>) -> Unit)

    internal class State(val userId: String, val uplayId: Long, val revision: Int?, val slots: Map<Int, UbisoftSyncedSlot>)

    private class LocalFile(val file: File, val bytes: ByteArray, val modified: Long, val slot: UbisoftLocalSlot)

    /** [generation] is the pull counter value of the launch that made the session. */
    private class Session(val userId: String, val uplayId: Long, val generation: Long)

    internal class Remembered(val preference: UbisoftCloudPreference, val at: Long, val userId: String)

    private class Op(val target: File, val backup: File?, val placed: Boolean, val size: Long = -1, val modified: Long = -1) {
        fun rewritten(): Boolean = placed && size >= 0 && (target.length() != size || target.lastModified() != modified)
    }

    /** A check of this client's own; its message carries no account data. */
    private class SyncAbort(message: String) : Exception(message)

    private const val TAG = "UbisoftCloud"
    private const val STATE_DIR = ".ubisoft_cloud"
    private const val KEEP_BACKUPS = 5
    private const val KEEP_REMOTE_BACKUPS = 5
    private const val BACKUP_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val REMEMBER_MS = 10L * 60 * 1000
    private const val PART_SUFFIX = ".part"

    internal var transportFactory: (Context, UbisoftCredentials) -> UbisoftCloudTransport =
        { context, creds -> UbisoftCloudApi.open(context, creds) }

    @Volatile private var uploadsOverride: Boolean? = null

    /**
     * The user's setting, off unless they turn it on; assigning a value fixes the answer, for tests. While off,
     * nothing writes to the cloud and local files are only ever replaced by downloads.
     */
    internal var uploadsEnabled: Boolean
        get() = uploadsOverride ?: PrefManager.ubisoftCloudSaveUploads
        set(value) {
            uploadsOverride = value
        }

    @Volatile private var uploadsOffLogged = false

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val mutexes = ConcurrentHashMap<Int, Mutex>()
    private val remembered = ConcurrentHashMap<Int, Remembered>()
    private val pulls = ConcurrentHashMap<Int, Long>()

    suspend fun syncBeforeLaunch(
        context: Context,
        container: Container,
        steamAppId: Int,
        preference: UbisoftCloudPreference,
    ): PullResult = withContext(Dispatchers.IO) {
        mutexFor(steamAppId).withLock {
            sessions.remove(steamAppId)
            val generation = pulls.compute(steamAppId) { _, value -> (value ?: 0L) + 1 } ?: 0L
            try {
                pull(context, container, steamAppId, preference, generation)
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
            val session = sessions.remove(steamAppId) ?: return@withLock false
            if (!uploadsEnabled) {
                if (!uploadsOffLogged) {
                    uploadsOffLogged = true
                    Timber.tag(TAG).i("Cloud save uploads are off; saves are only downloaded")
                }
                return@withLock false
            }
            if (session.generation != pulls[steamAppId]) {
                Timber.tag(TAG).w("Cloud save push skipped for $steamAppId: a newer launch replaced this sync")
                return@withLock false
            }
            try {
                val target = target(container, session.userId, session.uplayId, steamAppId)
                if (!hasSaveFiles(target.saveDir)) {
                    Timber.tag(TAG).i("Cloud save push for $steamAppId: no save files, nothing to upload")
                    return@withLock true
                }
                val creds = credentials(context, steamAppId, "push") ?: return@withLock false
                if (creds.userId != session.userId) {
                    Timber.tag(TAG).w("Cloud save push skipped for $steamAppId: Ubisoft account changed since launch")
                    return@withLock false
                }
                transportFactory(context, creds).use { transport -> pushCore(transport, target, env(context, steamAppId)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Cloud save push failed for $steamAppId: ${describe(e)}")
                false
            }
        }
    }

    /** Drops this game's sync session and remembered choice; for launches that skip cloud sync. */
    fun forgetSession(steamAppId: Int) {
        sessions.remove(steamAppId)
        remembered.remove(steamAppId)
    }

    internal fun hasSession(steamAppId: Int): Boolean = sessions.containsKey(steamAppId)

    internal fun resetForTest() {
        sessions.clear()
        mutexes.clear()
        remembered.clear()
        pulls.clear()
        uploadsOverride = null
    }

    /** The choice for this pull: the one just made, else a recent one by the same account. */
    internal fun effectivePreference(requested: UbisoftCloudPreference, kept: Remembered?, userId: String, now: Long): UbisoftCloudPreference {
        if (requested != UbisoftCloudPreference.NONE) return requested
        return kept?.takeIf { it.userId == userId && now - it.at in 0L until REMEMBER_MS }?.preference ?: UbisoftCloudPreference.NONE
    }

    private suspend fun pull(
        context: Context,
        container: Container,
        steamAppId: Int,
        requested: UbisoftCloudPreference,
        generation: Long,
    ): PullResult {
        val creds = credentials(context, steamAppId, "pull") ?: return PullResult.NoCloudSaves
        if (requested != UbisoftCloudPreference.NONE) remembered[steamAppId] = Remembered(requested, now(), creds.userId)
        val preference = effectivePreference(requested, remembered[steamAppId], creds.userId, now())
        return transportFactory(context, creds).use { transport ->
            val uplayId = resolveUplayId(context, transport, steamAppId)
            if (uplayId == null) {
                Timber.tag(TAG).i("Cloud save pull skipped for $steamAppId: the account lists no Ubisoft product for this game")
                return@use PullResult.NoCloudSaves
            }
            val target = target(container, creds.userId, uplayId, steamAppId)
            val result = pullCore(transport, target, env(context, steamAppId), preference)
            if (result == PullResult.Synced) {
                sessions[steamAppId] = Session(creds.userId, uplayId, generation)
                remembered.remove(steamAppId)
            }
            result
        }
    }

    private suspend fun credentials(context: Context, steamAppId: Int, phase: String): UbisoftCredentials? {
        if (!UbisoftCloudApi.isConfigured(context)) {
            Timber.tag(TAG).i("Cloud save $phase skipped for $steamAppId: helper configuration has no cloud save settings")
            return null
        }
        val stored = UbisoftAuthManager.load(context)
        if (stored == null) {
            Timber.tag(TAG).i("Cloud save $phase skipped for $steamAppId: not signed in to Ubisoft")
            return null
        }
        // A ticket that is still valid is used as it is: a sync never extends a session early.
        if (stored.expiresAt > System.currentTimeMillis() && stored.userId.isNotBlank()) return stored
        val fresh = UbisoftAuthManager.ensureFresh(context)
        val creds = (fresh as? UbisoftAuthManager.Freshness.Ready)?.credentials
        if (creds == null || creds.userId.isBlank()) {
            Timber.tag(TAG).i("Cloud save $phase skipped for $steamAppId: no usable Ubisoft session")
            return null
        }
        return creds
    }

    private fun resolveUplayId(context: Context, transport: UbisoftCloudTransport, steamAppId: Int): Long? {
        UbisoftLaunchSupport.productId(context, steamAppId)?.let { return it }
        val owned = transport.ownedProducts().firstOrNull { it.steamAppId == steamAppId } ?: return null
        UbisoftLaunchSupport.rememberProductId(context, steamAppId, owned.uplayId)
        return owned.uplayId
    }

    private fun target(container: Container, userId: String, uplayId: Long, steamAppId: Int): Target = Target(
        saveDir = File(container.rootDir, ".wine/drive_c/users/${ImageFs.USER}/Saved Games/Ubisoft/$userId/$uplayId"),
        stateDir = File(container.rootDir, STATE_DIR),
        userId = userId,
        uplayId = uplayId,
        steamAppId = steamAppId,
    )

    private fun env(context: Context, steamAppId: Int): Env = Env(
        nowMillis = System::currentTimeMillis,
        pcName = pcName(),
        backup = { dir, files ->
            CloudSaveBackupManager.backup(
                context,
                CloudSaveBackupManager.PROVIDER_UBISOFT,
                steamAppId.toString(),
                listOf(CloudSaveBackupManager.BackupRoot("saves", dir, files)),
            )
        },
    )

    private fun pcName(): String =
        Build.MODEL.orEmpty().filter { it.code in 32..126 }.trim().take(32).ifEmpty { "Android" }

    internal suspend fun pullCore(
        transport: UbisoftCloudTransport,
        target: Target,
        env: Env,
        preference: UbisoftCloudPreference,
    ): PullResult {
        val uploads = uploadsEnabled
        if (!recoverJournal(target)) return PullResult.Failed("journal_rollback")
        clearParts(target.saveDir)
        val record = transport.entity(target.uplayId)
        val remote = slotsByNumber(record)
        val local = scanLocal(target.saveDir)
        val state = loadState(target)
        val synced = state?.slots.orEmpty()
        val remoteState = remote.mapValues { UbisoftRemoteSlot(it.value.hash, it.value.size) }
        val deleted = deletedSlots(target.saveDir) - local.keys
        val plan = UbisoftCloudSyncPlanner.planPull(local.mapValues { it.value.slot }, remoteState, synced, preference, deleted, record != null)
        if (plan.conflict) {
            Timber.tag(TAG).i("Cloud save conflict for ${target.steamAppId}: ${plan.conflicts.size} slot(s), state=${state != null}")
            val localMillis = plan.conflicts.mapNotNull { local[it]?.modified }.maxOrNull() ?: 0L
            return PullResult.Conflict(localMillis, conflictRemoteMillis(record, plan.conflicts))
        }

        val fetched = LinkedHashMap<Int, ByteArray>()
        if (plan.download.isNotEmpty()) {
            val got = transport.download(target.uplayId, plan.download.map { remote.getValue(it).filename })
            for (slot in plan.download) {
                val entry = remote.getValue(slot)
                val bytes = got[entry.filename]
                if (bytes == null) {
                    Timber.tag(TAG).w("Cloud save slot $slot has no stored file; left as is")
                    continue
                }
                if (bytes.size.toLong() != entry.size) {
                    throw SyncAbort("downloaded slot $slot is ${bytes.size} bytes, the record says ${entry.size}")
                }
                val stamped = UbisoftSaveFile.stampedHash(bytes)
                if (stamped != null && !stamped.equals(entry.hash, ignoreCase = true)) {
                    throw SyncAbort("downloaded slot $slot carries a different hash than the record")
                }
                fetched[slot] = bytes
            }
        }
        if (fetched.isNotEmpty()) {
            if (fetched.keys.any { it in local }) env.backup(target.saveDir, local.values.map { it.file })
            applyPull(target, fetched)
            if (uploads) for (slot in fetched.keys) marker(target.saveDir, slot).delete()
        }

        val next = LinkedHashMap<Int, UbisoftSyncedSlot>()
        for ((slot, entry) in remote) {
            val bytes = fetched[slot]
            val mine = local[slot]
            when {
                bytes != null -> next[slot] = UbisoftSyncedSlot(UbisoftSaveFile.contentKey(bytes), entry.hash, bytes.size.toLong())
                // Keeping the local copy without uploading it settles this pair until either side changes again.
                !uploads && mine != null && slot in plan.forcedLocal ->
                    next[slot] = UbisoftSyncedSlot(mine.slot.contentKey, entry.hash, mine.slot.size)
                mine != null && slot !in plan.download && slot !in plan.upload &&
                    UbisoftCloudSyncPlanner.decide(mine.slot, remoteState[slot], synced[slot]) == UbisoftCloudAction.NONE ->
                    next[slot] = UbisoftSyncedSlot(mine.slot.contentKey, entry.hash, mine.slot.size)
                else -> synced[slot]?.let { next[slot] = it }
            }
        }
        for ((slot, known) in synced) {
            if (slot in remote) continue
            val mine = local[slot]
            val settle = slot in plan.keptRemoved || (!uploads && slot in plan.forcedLocal)
            next[slot] = if (settle && mine != null) known.copy(contentKey = mine.slot.contentKey, size = mine.slot.size) else known
        }
        saveState(target, State(target.userId, target.uplayId, record?.revision, next))
        Timber.tag(TAG).i(
            "Cloud save pull for ${target.steamAppId}: ${fetched.size} downloaded, ${plan.upload.size} " +
                (if (uploads) "to upload" else "kept local") + ", ${remote.size} in cloud, ${local.size} local",
        )

        if (uploads && plan.upload.isNotEmpty()) {
            try {
                pushCore(transport, target, env, plan.upload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Cloud save upload at launch failed for ${target.steamAppId}: ${describe(e)}")
            }
        }
        return PullResult.Synced
    }

    /** Uploads [only] when given, else every slot that changed since the last sync. */
    internal fun pushCore(transport: UbisoftCloudTransport, target: Target, env: Env, only: Set<Int>? = null): Boolean {
        if (!uploadsEnabled) return false
        if (!recoverJournal(target)) return false
        val local = scanLocal(target.saveDir)
        if (local.isEmpty()) {
            Timber.tag(TAG).i("Cloud save push for ${target.steamAppId}: no save files, nothing to upload")
            return true
        }
        val state = loadState(target)
        if (state == null) {
            Timber.tag(TAG).w("Cloud save push skipped for ${target.steamAppId}: no sync state from this launch")
            return false
        }
        val wanted = only ?: UbisoftCloudSyncPlanner.planPush(local.mapValues { it.value.slot }, state.slots)
        val candidates = wanted.filter { slot ->
            val mine = local[slot] ?: return@filter false
            UbisoftSaveFile.canStamp(mine.bytes).also { if (!it) Timber.tag(TAG).w("Save slot $slot is too short to upload") }
        }
        if (candidates.isEmpty()) {
            Timber.tag(TAG).i("Cloud save push for ${target.steamAppId}: no changes")
            return true
        }

        val record = transport.entity(target.uplayId)
        if (record?.revision != state.revision) {
            Timber.tag(TAG).w(
                "Cloud save push skipped for ${target.steamAppId}: the cloud record changed since the last sync; it is settled at the next launch",
            )
            return false
        }
        val existing = slotsByNumber(record)
        // Uploads planned by this launch's pull were checked against this same record revision.
        val pushes = if (only != null) {
            candidates
        } else {
            candidates.filter { slot ->
                pushAllowed(existing[slot], state.slots[slot], local.getValue(slot).slot).also { allowed ->
                    if (!allowed) Timber.tag(TAG).w("Save slot $slot changed in the cloud, kept for the next launch")
                }
            }
        }
        if (pushes.isEmpty()) return false
        val productId = transport.ownedProducts().let { owned ->
            owned.firstOrNull { it.uplayId == target.uplayId } ?: owned.firstOrNull { it.steamAppId == target.steamAppId }
        }?.productId
        val inCloud = pushes.filter { it in existing }
        if (inCloud.isNotEmpty()) {
            val copies = transport.download(target.uplayId, inCloud.map { existing.getValue(it).filename })
            for (slot in inCloud) {
                val entry = existing.getValue(slot)
                copies[entry.filename]?.let { keepRemoteBackup(target, slot, entry.hash, it) }
            }
        }

        val now = env.nowMillis()
        val lastModified = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString()
        val uploads = LinkedHashMap<String, ByteArray>()
        val written = LinkedHashMap<Int, UbisoftSyncedSlot>()
        val pushedSlots = ArrayList<UbisoftCloudSlot>()
        for (slot in pushes) {
            val bytes = local.getValue(slot).bytes
            val hash = UbisoftSaveFile.newHash(bytes)
            val upload = UbisoftSaveFile.stamp(bytes, hash)
            val filename = existing[slot]?.filename ?: slot.toString()
            uploads[filename] = upload
            pushedSlots += UbisoftCloudSlot(existing[slot]?.id ?: slot, filename, hash, upload.size.toLong(), lastModified)
            written[slot] = UbisoftSyncedSlot(UbisoftSaveFile.contentKey(bytes), hash, bytes.size.toLong())
        }

        val stored = transport.writeEntity(target.uplayId, record, mergeSlots(record?.slots.orEmpty(), pushedSlots), now / 1000, env.pcName)
        // The record now names the new hashes; slot entries wait for the bytes so a failed upload is retried.
        saveState(target, State(target.userId, target.uplayId, stored.revision, state.slots))
        try {
            transport.upload(target.uplayId, productId, uploads)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("Cloud save upload for ${target.steamAppId} failed (${describe(e)}), trying once more")
            transport.upload(target.uplayId, productId, uploads)
        }
        saveState(target, State(target.userId, target.uplayId, stored.revision, state.slots + written))
        for (slot in pushes) {
            marker(target.saveDir, slot).delete()
            stampLocal(target.saveDir, local.getValue(slot), uploads.getValue(existing[slot]?.filename ?: slot.toString()))
        }
        Timber.tag(TAG).i("Cloud save push for ${target.steamAppId}: ${pushes.size} slot(s) uploaded, revision ${stored.revision}")
        return true
    }

    /**
     * An exit push may replace a cloud slot only while it still holds what this client last synced, or
     * already names these bytes; a slot the cloud never had is new. A synced slot the cloud dropped is not recreated.
     */
    internal fun pushAllowed(cloud: UbisoftCloudSlot?, known: UbisoftSyncedSlot?, mine: UbisoftLocalSlot): Boolean = when {
        cloud == null -> known == null
        known != null && cloud.hash.equals(known.remoteHash, ignoreCase = true) -> true
        else -> cloud.hash.equals(mine.uploadHash, ignoreCase = true)
    }

    /** Newest change time among the conflicting cloud slots, else the record's own time. */
    internal fun conflictRemoteMillis(record: UbisoftCloudEntity?, conflicts: Set<Int>): Long? {
        val slots = slotsByNumber(record)
        return conflicts.mapNotNull { parseTime(slots[it]?.lastModified) }.maxOrNull()
            ?: record?.timestamp?.trim()?.toLongOrNull()?.times(1000)
    }

    internal fun parseTime(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    /** The record's slots with [pushed] replacing same-named ones and new ones appended; nothing is dropped. */
    internal fun mergeSlots(existing: List<UbisoftCloudSlot>, pushed: List<UbisoftCloudSlot>): List<UbisoftCloudSlot> {
        val byName = pushed.associateBy { it.filename }
        val merged = existing.map { byName[it.filename] ?: it }
        val known = existing.mapTo(HashSet()) { it.filename }
        return merged + pushed.filter { it.filename !in known }
    }

    private fun slotsByNumber(record: UbisoftCloudEntity?): Map<Int, UbisoftCloudSlot> {
        val out = sortedMapOf<Int, UbisoftCloudSlot>()
        for (entry in record?.slots.orEmpty()) {
            val slot = entry.filename.toIntOrNull()?.takeIf { it > 0 }
            if (slot == null) {
                Timber.tag(TAG).d("Cloud save slot with an unexpected name kept as is: ${entry.filename}")
                continue
            }
            out[slot] = entry
        }
        return out
    }

    private fun scanLocal(dir: File): Map<Int, LocalFile> {
        val out = sortedMapOf<Int, LocalFile>()
        for (file in dir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val slot = UbisoftSaveFile.slotOf(file.name) ?: continue
            val modified = file.lastModified()
            val bytes = file.readBytes()
            out[slot] = LocalFile(
                file,
                bytes,
                modified,
                UbisoftLocalSlot(
                    contentKey = UbisoftSaveFile.contentKey(bytes),
                    size = bytes.size.toLong(),
                    stampedHash = UbisoftSaveFile.stampedHash(bytes),
                    uploadHash = UbisoftSaveFile.newHash(bytes),
                    wantsUpload = marker(dir, slot).exists(),
                ),
            )
        }
        return out
    }

    private fun deletedSlots(dir: File): Set<Int> = dir.listFiles().orEmpty().mapNotNullTo(HashSet()) { file ->
        file.name.takeIf { file.isFile && it.endsWith(UbisoftSaveFile.DELETE_MARKER) }
            ?.let { UbisoftSaveFile.slotOf(it.removeSuffix(UbisoftSaveFile.DELETE_MARKER)) }
    }

    /** Leaves the local file stamped with the hash it was uploaded under, keeping its time; skipped if it changed meanwhile. */
    private fun stampLocal(dir: File, mine: LocalFile, stamped: ByteArray) {
        val file = mine.file
        if (!file.isFile || file.length() != mine.bytes.size.toLong() || file.lastModified() != mine.modified) return
        val temp = File(dir, "." + file.name + PART_SUFFIX)
        try {
            temp.writeBytes(stamped)
            temp.setLastModified(mine.modified)
            if (!temp.renameTo(file)) Timber.tag(TAG).w("Could not stamp ${file.name} after upload")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Could not stamp ${file.name} after upload: ${describe(e)}")
        } finally {
            temp.delete()
        }
    }

    private fun hasSaveFiles(dir: File): Boolean = dir.listFiles().orEmpty().any { it.isFile && UbisoftSaveFile.slotOf(it.name) != null }

    private fun marker(dir: File, slot: Int) = File(dir, UbisoftSaveFile.fileName(slot) + UbisoftSaveFile.UPLOAD_MARKER)

    private fun partFile(dir: File, slot: Int) = File(dir, "." + UbisoftSaveFile.fileName(slot) + PART_SUFFIX)

    private fun clearParts(dir: File) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(UbisoftSaveFile.EXTENSION + PART_SUFFIX) }
            .forEach { it.delete() }
    }

    private fun keepRemoteBackup(target: Target, slot: Int, hash: String, bytes: ByteArray) {
        val dir = File(target.stateDir, "remote_backup/${target.uplayId}")
        if (!dir.isDirectory && !dir.mkdirs()) throw SyncAbort("could not create the cloud copy folder")
        val safeHash = hash.filter { it.isLetterOrDigit() }.take(64).ifEmpty { "unknown" }
        val file = File(dir, "$slot-$safeHash${UbisoftSaveFile.EXTENSION}")
        val tmp = File(dir, file.name + PART_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw SyncAbort("could not keep the cloud copy of slot $slot")
        }
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("$slot-") && it.name.endsWith(UbisoftSaveFile.EXTENSION) }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP_REMOTE_BACKUPS)
            .forEach { it.delete() }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun mutexFor(steamAppId: Int): Mutex = mutexes.getOrPut(steamAppId) { Mutex() }

    private fun describe(e: Throwable): String = when (e) {
        is SyncAbort -> e.message.orEmpty()
        is UbisoftCloudException -> if (e.httpStatus != 0) "UbisoftCloudException HTTP ${e.httpStatus}" else "UbisoftCloudException"
        else -> e.javaClass.simpleName
    }

    private fun applyPull(target: Target, fetched: Map<Int, ByteArray>) {
        val saveDir = target.saveDir
        if (!saveDir.isDirectory && !saveDir.mkdirs()) throw SyncAbort("could not create the save folder")
        val backupRoot = File(target.stateDir, "backups")
        val backupDir = File(backupRoot, System.currentTimeMillis().toString())
        val steps = ArrayList<Pair<Op, File>>()
        try {
            for ((slot, bytes) in fetched) {
                val temp = partFile(saveDir, slot)
                temp.writeBytes(bytes)
                val dest = File(saveDir, UbisoftSaveFile.fileName(slot))
                val backup = if (dest.exists()) File(backupDir, dest.name) else null
                steps += Op(dest, backup, true, temp.length(), temp.lastModified()) to temp
            }
            val ops = steps.map { it.first }
            val journal = journalFile(target)
            writeJournal(journal, ops)
            try {
                for ((op, temp) in steps) {
                    if (op.backup != null) move(op.target, op.backup)
                    move(temp, op.target)
                }
            } catch (e: Throwable) {
                runCatching {
                    rollback(ops)
                    journal.delete()
                }
                throw e
            }
            finishJournal(journal)
        } finally {
            for ((_, temp) in steps) temp.delete()
            clearParts(saveDir)
        }
        val now = System.currentTimeMillis()
        backupRoot.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name.toLongOrNull() ?: 0L }
            ?.drop(KEEP_BACKUPS)
            ?.filter { now - (it.name.toLongOrNull() ?: 0L) > BACKUP_MIN_AGE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw SyncAbort("could not move a save file")
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
                    if (!op.target.delete()) throw SyncAbort("could not remove a placed save")
                }
                move(backup, op.target)
            } else if (op.placed && op.target.exists()) {
                if (op.rewritten()) {
                    Timber.tag(TAG).w("Cloud save rollback kept a save that changed after it was placed: ${op.target.name}")
                } else if (!op.target.delete()) {
                    throw SyncAbort("could not remove a placed save")
                }
            }
        }
    }

    private fun journalFile(target: Target) = File(target.stateDir, "apply.json")

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
        if (!tmp.renameTo(journal)) throw SyncAbort("could not write the apply journal")
    }

    /** Marks the apply complete before removing the journal, so a journal that cannot be deleted is never rolled back. */
    private fun finishJournal(journal: File) {
        runCatching {
            val text = JSONObject().put("ops", JSONArray()).put("done", true).toString()
            val tmp = File(journal.parentFile, journal.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(journal)) {
                tmp.delete()
                journal.writeText(text)
            }
        }.onFailure { Timber.tag(TAG).w("Could not mark the apply journal done: ${describe(it)}") }
        if (!journal.delete()) Timber.tag(TAG).w("Could not delete the finished apply journal")
    }

    private fun recoverJournal(target: Target): Boolean {
        val journal = journalFile(target)
        if (!journal.exists()) return true
        return try {
            val json = JSONObject(journal.readText())
            if (json.optBoolean("done")) {
                journal.delete()
                return true
            }
            val array = json.getJSONArray("ops")
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
            if (!journal.delete()) throw SyncAbort("could not delete the apply journal")
            Timber.tag(TAG).w("Cloud save apply journal found, rolled back ${ops.size} step(s)")
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w("Cloud save apply journal rollback failed: ${describe(e)}")
            false
        }
    }

    private fun stateFile(target: Target) = File(target.stateDir, "state.json")

    internal fun loadState(target: Target): State? = runCatching {
        val file = stateFile(target)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val userId = json.optString("userId")
        val uplayId = json.optLong("uplayId", -1)
        if (userId != target.userId || uplayId != target.uplayId) {
            Timber.tag(TAG).i("Cloud save state belongs to another account or game; starting without it")
            return null
        }
        val revision = if (json.has("revision") && !json.isNull("revision")) json.getInt("revision") else null
        val slots = LinkedHashMap<Int, UbisoftSyncedSlot>()
        val entries = json.getJSONObject("slots")
        for (name in entries.keys()) {
            val slot = name.toIntOrNull() ?: continue
            val entry = entries.getJSONObject(name)
            slots[slot] = UbisoftSyncedSlot(entry.getString("contentKey"), entry.getString("remoteHash"), entry.getLong("size"))
        }
        State(userId, uplayId, revision, slots)
    }.getOrElse {
        Timber.tag(TAG).w("Cloud save state is unreadable (${it.javaClass.simpleName})")
        null
    }

    internal fun saveState(target: Target, state: State) {
        val slots = JSONObject()
        for ((slot, entry) in state.slots.toSortedMap()) {
            slots.put(slot.toString(), JSONObject().put("contentKey", entry.contentKey).put("remoteHash", entry.remoteHash).put("size", entry.size))
        }
        val json = JSONObject().put("userId", state.userId).put("uplayId", state.uplayId).put("slots", slots)
        if (state.revision != null) json.put("revision", state.revision)
        val file = stateFile(target)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }
}
