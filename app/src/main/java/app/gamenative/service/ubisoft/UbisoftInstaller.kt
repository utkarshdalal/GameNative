package app.gamenative.service.ubisoft

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Where an install reports to; the download screen's progress object sits behind it. */
interface UbisoftProgress {
    /** [done] bytes were already downloaded by an earlier run. */
    fun total(bytes: Long, done: Long)
    fun advance(bytes: Long)
    fun phase(phase: Phase)

    enum class Phase { DOWNLOADING, ASSEMBLING }
}

/** What a finished install leaves in `.gamenative-ubisoft/install.json`. */
data class UbisoftInstallRecord(
    val productId: Long,
    val uplayId: Long,
    val manifestId: String,
    val language: String?,
    val gameVersion: String,
    val installBytes: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("productId", productId).put("uplayId", uplayId).put("manifestId", manifestId)
        .put("language", language ?: "").put("gameVersion", gameVersion).put("installBytes", installBytes)

    companion object {
        fun fromJson(json: JSONObject) = UbisoftInstallRecord(
            productId = json.getLong("productId"),
            uplayId = json.optLong("uplayId", json.getLong("productId")),
            manifestId = json.getString("manifestId"),
            language = json.optString("language").ifEmpty { null },
            gameVersion = json.optString("gameVersion"),
            installBytes = json.optLong("installBytes"),
        )
    }
}

/**
 * Downloads a build into its install folder, or brings an installed one to a newer build. Each
 * stored piece is fetched once, checked against the manifest, unpacked and written where it first
 * occurs; repeated pieces are copied from there. Files are written as `.ugpart` and renamed when
 * whole. Progress is saved as it goes, so a paused or failed run continues where it stopped.
 */
class UbisoftInstaller(
    private val session: UbisoftStoreSession,
    private val http: OkHttpClient,
    private val installDir: File,
    private val parallel: Int = 6,
) {
    private val metaDir = File(installDir, UbisoftHelperDeployment.DIRECTORY)
    private val stateFile = File(metaDir, STATE_FILE)

    suspend fun install(
        game: UbisoftStoreGame,
        manifestBytes: ByteArray,
        language: String?,
        ownedIds: Set<Long>,
        progress: UbisoftProgress,
    ): UbisoftInstallRecord = withContext(Dispatchers.IO) {
        val manifest = UbisoftManifest.parse(manifestBytes)
        manifest.requireSupported()
        val selected = manifest.selectFiles(language, ownedIds)
        val previous = installedSelection()
        val state = loadState(game.latestManifest)
        val plan = UbisoftInstallPlan.build(selected, previous) { name, size -> target(name).let { it.isFile && it.length() == size } }
        Timber.i(
            "Ubisoft install %d build %s: %d files to write, %d kept, %d removed, %d pieces (%d bytes)",
            game.productId, game.latestManifest, plan.write.size, plan.keep.size, plan.remove.size, plan.fetch.size, plan.downloadBytes,
        )
        check(metaDir.isDirectory || metaDir.mkdirs()) { "Cannot create ${metaDir.path}" }
        // A stopped run, paused or failed, keeps what it has written for the next one.
        try {
            prepareFiles(plan, state)
            progress.total(
                plan.downloadBytes,
                plan.fetch.filter { it.id in state.fetched }.sumOf { wire(it) } +
                    plan.sequential.filterKeys { it in state.done }.values.sumOf { list -> list.sumOf { wire(it) } },
            )

            progress.phase(UbisoftProgress.Phase.DOWNLOADING)
            val matches = java.util.concurrent.ConcurrentHashMap<UbisoftSliceCodec.Match, Int>()
            val pending = plan.fetch.filter { it.id !in state.fetched && it.file !in state.done }
            for (batch in pending.chunked(URL_BATCH)) {
                coroutineContext.ensureActive()
                val urls = urlsFor(game.productId, manifest, batch)
                val gate = Semaphore(parallel)
                coroutineScope {
                    batch.mapIndexed { i, piece ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                val decoded = fetchPiece(game.productId, manifest, piece, urls[i])
                                matches.merge(decoded.match, 1, Int::plus)
                                RandomAccessFile(part(piece.file), "rw").use { it.seek(piece.offset); it.write(decoded.bytes) }
                                state.markFetched(piece.id)
                                progress.advance(wire(piece))
                            }
                        }
                    }.awaitAll()
                }
                saveState(state, force = false)
            }
            if (matches.isNotEmpty()) Timber.i("Ubisoft install %d: piece hashes matched %s", game.productId, matches)

            for ((name, pieces) in plan.sequential) {
                if (name in state.done) continue
                coroutineContext.ensureActive()
                val out = part(name)
                out.delete()
                for (batch in pieces.chunked(URL_BATCH)) {
                    val urls = urlsFor(game.productId, manifest, batch)
                    batch.forEachIndexed { i, piece ->
                        val decoded = fetchPiece(game.productId, manifest, piece, urls[i])
                        out.appendBytes(decoded.bytes)
                        progress.advance(wire(piece))
                    }
                }
                finish(name, plan.write.first { it.name == name }.size, state)
            }

            progress.phase(UbisoftProgress.Phase.ASSEMBLING)
            for (file in plan.write) {
                if (file.isDir || file.size == 0L || file.name in state.done || file.name in plan.sequential) continue
                coroutineContext.ensureActive()
                val copies = plan.copy[file.name].orEmpty()
                if (copies.isNotEmpty()) {
                    RandomAccessFile(part(file.name), "rw").use { out ->
                        for ((owner, piece) in copies) {
                            val bytes = RandomAccessFile(current(owner.file), "r").use { src ->
                                ByteArray(piece.size.toInt()).also { src.seek(owner.offset); src.readFully(it) }
                            }
                            out.seek(piece.offset)
                            out.write(bytes)
                        }
                    }
                }
                finish(file.name, file.size, state)
            }
        } catch (e: Throwable) {
            runCatching { saveState(state) }
            throw e
        }

        for (name in plan.remove) {
            val f = target(name)
            if (!f.path.startsWith(metaDir.path) && f.isFile) f.delete()
        }
        val record = UbisoftInstallRecord(
            productId = game.productId,
            uplayId = game.uplayId,
            manifestId = game.latestManifest,
            language = language,
            gameVersion = manifest.gameVersion,
            installBytes = plan.installBytes,
        )
        File(metaDir, MANIFEST_FILE).writeBytes(manifestBytes)
        File(metaDir, CONFIG_FILE).writeText(game.configuration)
        File(metaDir, RECORD_FILE).writeText(record.toJson().toString())
        stateFile.delete()
        Timber.i("Ubisoft install %d build %s complete", game.productId, game.latestManifest)
        record
    }

    private fun installedManifest(): UbisoftManifest? = readManifest(installDir)

    private fun record(): UbisoftInstallRecord? = readRecord(installDir)

    /** The files of the build on disk: its base chunks and the installed language. */
    private fun installedSelection(): List<UbisoftManifest.File> {
        val record = record() ?: return emptyList()
        val manifest = installedManifest() ?: return emptyList()
        return manifest.chunks.filter { it.language.isEmpty() || it.language.equals(record.language, true) }.flatMap { it.files }
    }

    /** A part file that has to be created again holds none of its pieces, whatever the saved progress says. */
    private fun prepareFiles(plan: UbisoftInstallPlan, state: State) {
        val owned = plan.fetch.groupBy { it.file }
        for (file in plan.write) {
            if (file.name in state.done) continue
            when {
                file.isDir -> target(file.name).mkdirs()
                file.size == 0L -> target(file.name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }
                file.name in plan.sequential -> part(file.name).parentFile?.mkdirs()
                else -> {
                    val p = part(file.name)
                    p.parentFile?.mkdirs()
                    if (!p.isFile || p.length() != file.size) {
                        owned[file.name]?.forEach { state.fetched.remove(it.id) }
                        RandomAccessFile(p, "rw").use { it.setLength(file.size) }
                    }
                }
            }
        }
    }

    private fun finish(name: String, size: Long, state: State) {
        val p = part(name)
        if (p.length() != size) throw IOException("$name: assembled ${p.length()} bytes, the manifest says $size")
        val t = target(name)
        if (t.exists() && !t.delete()) throw IOException("$name: cannot replace the old file")
        if (!p.renameTo(t)) throw IOException("$name: cannot move the finished file into place")
        state.markDone(name)
        saveState(state, force = false)
    }

    private fun urlsFor(productId: Long, manifest: UbisoftManifest, pieces: List<UbisoftPiece>): List<List<String>> =
        session.urls(productId, pieces.map { manifest.slicePath(it.id) })

    private suspend fun fetchPiece(productId: Long, manifest: UbisoftManifest, piece: UbisoftPiece, urls: List<String>): UbisoftSliceCodec.Decoded {
        var last: Exception? = null
        var candidates = urls
        repeat(ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            for (url in candidates) {
                try {
                    return UbisoftSliceCodec.decode(manifest, piece, get(url, piece))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UbisoftUnsupportedException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            // Signed URLs expire; the next attempt asks for new ones.
            if (attempt < ATTEMPTS - 1) candidates = session.urls(productId, listOf(manifest.slicePath(piece.id))).first()
        }
        throw IOException("piece ${piece.id.take(8)} of ${piece.file} could not be downloaded: ${last?.message}", last)
    }

    private fun get(url: String, piece: UbisoftPiece): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val limit = if (piece.downloadSize > 0) piece.downloadSize else MAX_PIECE
            val body = resp.body ?: throw IOException("empty answer")
            if (body.contentLength() > limit) throw IOException("piece is larger than the manifest says")
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream(if (piece.downloadSize in 1..MAX_PIECE) piece.downloadSize.toInt() else 1 shl 20)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) throw IOException("piece is larger than the manifest says")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }

    private fun target(name: String) = File(installDir, name)

    private fun part(name: String) = File(installDir, "$name$PART")

    private fun current(name: String) = part(name).takeIf { it.isFile } ?: target(name)

    private fun wire(piece: UbisoftPiece) = if (piece.downloadSize > 0) piece.downloadSize else maxOf(piece.size, 0L)

    /** Saved progress of an unfinished run: pieces written to their `.ugpart` file, and files already in place. */
    private class State(val manifestId: String, fetched: Collection<String>, done: Collection<String>) {
        val fetched: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet(fetched))
        val done: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet(done))

        fun markFetched(id: String) { fetched += id }

        fun markDone(name: String) { done += name }
    }

    private fun loadState(manifestId: String): State {
        val json = stateFile.takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        if (json == null || json.optString("manifestId") != manifestId) return State(manifestId, emptyList(), emptyList())
        fun list(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return State(manifestId, list("fetched"), list("done"))
    }

    @Volatile private var lastSave = 0L

    private fun saveState(state: State, force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < SAVE_EVERY_MS) return
        lastSave = now
        val json = JSONObject().put("manifestId", state.manifestId)
            .put("fetched", JSONArray(synchronized(state.fetched) { state.fetched.toList() }))
            .put("done", JSONArray(synchronized(state.done) { state.done.toList() }))
        val tmp = File(metaDir, "$STATE_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }

    companion object {
        const val RECORD_FILE = "install.json"
        const val MANIFEST_FILE = "installed.manifest"
        const val CONFIG_FILE = "product.yml"
        private const val STATE_FILE = "download-state.json"
        private const val PART = ".ugpart"
        private const val URL_BATCH = 30
        private const val SAVE_EVERY_MS = 2_000L
        private const val ATTEMPTS = 3
        private const val MAX_PIECE = 256L * 1024 * 1024

        fun readManifest(installDir: File): UbisoftManifest? =
            File(installDir, "${UbisoftHelperDeployment.DIRECTORY}/$MANIFEST_FILE").takeIf { it.isFile }
                ?.let { runCatching { UbisoftManifest.parse(it.readBytes()) }.getOrNull() }

        fun readConfig(installDir: File): UbisoftProductConfig? =
            File(installDir, "${UbisoftHelperDeployment.DIRECTORY}/$CONFIG_FILE").takeIf { it.isFile }
                ?.let { runCatching { UbisoftProductConfig.parse(it.readText()) }.getOrNull() }

        fun readRecord(installDir: File): UbisoftInstallRecord? =
            File(installDir, "${UbisoftHelperDeployment.DIRECTORY}/$RECORD_FILE").takeIf { it.isFile }
                ?.let { runCatching { UbisoftInstallRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
    }
}
