package app.gamenative.ui.screen.support

import android.content.Context
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.api.SupportPatch
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object SupportPatchApplier {

    enum class State { NONE, APPLIED, RESTORED, GAME_UPDATED }

    enum class Step { DOWNLOAD, VERIFY, BACKUP, REPLACE, RESTORE }

    sealed class Result {
        data object Done : Result()
        data object NoGame : Result()
        data class HashMismatch(val path: String) : Result()
        data class Failed(val message: String) : Result()
    }

    private class StepFailure(message: String) : Exception(message)

    fun appliedRecordFile(container: Container): File =
        File(container.rootDir, ".gamenative/applied_patch.json")

    private fun backupDir(root: File, patchsetId: String): File =
        File(root, "${SupportGameFiles.PATCH_DIR}/$patchsetId")

    private fun manifestFile(root: File, patchsetId: String): File =
        File(backupDir(root, patchsetId), "manifest.json")

    private fun backupFile(root: File, patchsetId: String, index: Int): File =
        File(backupDir(root, patchsetId), "files/$index")

    private fun registryBackupFile(root: File, patchsetId: String, index: Int): File =
        File(backupDir(root, patchsetId), "registry/$index.json")

    private fun opsJson(patch: SupportPatch): JSONArray = JSONArray().apply {
        patch.ops.forEach { op ->
            put(
                JSONObject().apply {
                    put("path", op.path)
                    if (op.isRegistry) {
                        put("op", op.op)
                        put("hive", op.hive)
                        put("key", op.key)
                    } else {
                        put("originalSha256", op.originalSha256)
                        put("sha256", op.sha256)
                    }
                },
            )
        }
    }

    private fun readHive(file: File): String = file.readText(Charsets.ISO_8859_1)

    private fun writeHive(file: File, text: String, scratch: File) {
        scratch.parentFile?.mkdirs()
        scratch.writeText(text, Charsets.ISO_8859_1)
        try {
            SupportGameFiles.replaceAtomically(scratch, file)
        } finally {
            scratch.delete()
        }
    }

    private fun registryChanges(op: SupportPatch.Op): List<WineRegistryText.Change> = op.values.map { value ->
        WineRegistryText.Change(value.name, if (value.delete) null else WineRegistryText.encodeValue(value.type!!, value.data!!))
    }

    private fun mergeRegistry(hiveFile: File, op: SupportPatch.Op, backup: File, scratch: File) {
        val key = op.key!!
        val changes = registryChanges(op)
        val merged = WineRegistryText.merge(readHive(hiveFile), key, changes, System.currentTimeMillis())
        val record = JSONObject().apply {
            put("hive", op.hive)
            put("key", key)
            put("sectionExisted", merged.sectionExisted)
            put(
                "values",
                JSONArray().apply {
                    merged.priors.forEachIndexed { index, prior ->
                        put(
                            JSONObject().apply {
                                put("name", prior.name)
                                put("prior", prior.lines?.let { JSONArray(it) } ?: JSONObject.NULL)
                                put("applied", changes[index].raw?.let { WineRegistryText.canonical(it) } ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
        }
        backup.parentFile?.mkdirs()
        backup.writeText(record.toString(2))
        writeHive(hiveFile, merged.text, scratch)
        val check = WineRegistryText.read(readHive(hiveFile), key, changes.map { it.name })
        changes.forEach { change ->
            val now = check[change.name]
            val ok = if (change.raw == null) now == null else now != null && WineRegistryText.canonical(now) == WineRegistryText.canonical(change.raw)
            if (!ok) throw StepFailure("registry value ${change.name} did not take in ${op.path}")
        }
    }

    private fun restoreRegistry(hiveFile: File, backup: File, scratch: File) {
        val record = JSONObject(backup.readText())
        val values = record.getJSONArray("values")
        val priors = (0 until values.length()).map { i ->
            val entry = values.getJSONObject(i)
            val prior = entry.optJSONArray("prior")
            WineRegistryText.Prior(entry.getString("name"), prior?.let { array -> (0 until array.length()).map { array.getString(it) } })
        }
        val key = record.getString("key")
        val text = WineRegistryText.restore(readHive(hiveFile), key, record.optBoolean("sectionExisted", true), priors, System.currentTimeMillis())
        writeHive(hiveFile, text, scratch)
    }

    private fun registryApplied(hiveFile: File, backup: File): Boolean {
        val record = JSONObject(backup.readText())
        val values = record.getJSONArray("values")
        val entries = (0 until values.length()).map { values.getJSONObject(it) }
        val current = WineRegistryText.read(readHive(hiveFile), record.getString("key"), entries.map { it.getString("name") })
        return entries.all { entry ->
            val now = current[entry.getString("name")]
            if (entry.isNull("applied")) now == null else now != null && WineRegistryText.canonical(now) == entry.getString("applied")
        }
    }

    private fun writeAppliedExtra(context: Context, appId: String, patch: SupportPatch, appliedAt: String, restored: Boolean) {
        if (!ContainerUtils.hasContainer(context, appId)) return
        val file = appliedRecordFile(ContainerUtils.getContainer(context, appId))
        val record = if (restored) {
            runCatching { JSONObject(file.readText()) }.getOrNull()
                ?.takeIf { it.optString("patchsetId") == patch.patchsetId }
                ?: return
        } else {
            JSONObject().apply {
                put("patchsetId", patch.patchsetId)
                put("appliedAt", appliedAt)
                put("ops", opsJson(patch))
                patch.run?.let { put("run", it.toRunJson()) }
            }
        }
        record.put("restored", restored)
        file.parentFile?.mkdirs()
        file.writeText(record.toString())
    }

    private suspend fun postOutcome(conversationId: String, patchsetId: String, status: String, detail: String?) {
        try {
            val result = SupportApi.patchOutcome(conversationId, patchsetId, status, detail)
            if (result !is ApiResult.Success) Timber.w("Posting patch outcome $status failed: $result")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Posting patch outcome $status failed")
        }
    }

    private fun targets(roots: SupportGameFiles.Roots, patch: SupportPatch): List<File> =
        patch.ops.map { op ->
            if (op.isRegistry) {
                SupportGameFiles.registryHive(roots, op.hive!!) ?: throw StepFailure("registry ${op.hive} not found")
            } else {
                SupportGameFiles.resolve(roots, op.path) ?: throw StepFailure("bad path ${op.path}")
            }
        }

    suspend fun apply(
        context: Context,
        appId: String,
        conversationId: String,
        patch: SupportPatch,
        onProgress: (Step, Float, String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        val cache = File(context.cacheDir, "support-patches/${patch.patchsetId}")
        var outcome: Pair<String, String?>? = null
        try {
            if (!patch.applicable) return@withContext Result.Failed("")
            val roots = SupportGameFiles.roots(context, appId)
            if (roots == null) {
                outcome = SupportApi.PATCH_FAILED to "game folder not found"
                return@withContext Result.NoGame
            }
            val root = roots.install
            val files = targets(roots, patch)

            cache.deleteRecursively()
            cache.mkdirs()
            val artifacts = patch.ops.mapIndexed { index, op ->
                if (op.isRegistry) return@mapIndexed null
                val dest = File(cache, "$index")
                val url = op.artifactUrl ?: throw StepFailure("no artifact for ${op.path}")
                onProgress(Step.DOWNLOAD, 0f, op.path)
                when (val result = SupportApi.downloadSigned(url, dest) { onProgress(Step.DOWNLOAD, it, op.path) }) {
                    is ApiResult.Success -> if (result.data != op.sha256) throw StepFailure("artifact hash differs for ${op.path}")
                    is ApiResult.HttpError -> throw StepFailure("artifact download HTTP ${result.code} for ${op.path}")
                    is ApiResult.NetworkError -> throw StepFailure("artifact download failed for ${op.path}")
                }
                if (op.size >= 0 && dest.length() != op.size) throw StepFailure("artifact size differs for ${op.path}")
                dest
            }

            patch.ops.forEachIndexed { index, op ->
                if (op.isRegistry) return@forEachIndexed
                val target = files[index]
                onProgress(Step.VERIFY, -1f, op.path)
                val local = if (target.isFile) SupportGameFiles.sha256(target, job) else null
                if (local != op.originalSha256) {
                    outcome = SupportApi.PATCH_HASH_MISMATCH to "${op.path}: local ${local?.take(12) ?: "missing"}, expected ${op.originalSha256.take(12)}"
                    return@withContext Result.HashMismatch(op.path)
                }
            }

            val backup = backupDir(root, patch.patchsetId)
            backup.deleteRecursively()
            backup.mkdirs()
            patch.ops.forEachIndexed { index, op ->
                if (op.isRegistry) return@forEachIndexed
                onProgress(Step.BACKUP, -1f, op.path)
                val copy = backupFile(root, patch.patchsetId, index)
                copy.parentFile?.mkdirs()
                files[index].copyTo(copy, overwrite = true)
                if (SupportGameFiles.sha256(copy, job) != op.originalSha256) throw StepFailure("backup hash differs for ${op.path}")
            }
            val appliedAt = Instant.now().toString()
            val manifest = JSONObject().apply {
                put("patchsetId", patch.patchsetId)
                put("appliedAt", appliedAt)
                put("restored", false)
                put(
                    "ops",
                    JSONArray().apply {
                        patch.ops.forEachIndexed { index, op ->
                            put(
                                JSONObject().apply {
                                    put("path", op.path)
                                    if (op.isRegistry) {
                                        put("op", op.op)
                                        put("hive", op.hive)
                                        put("key", op.key)
                                        put("backup", "registry/$index.json")
                                    } else {
                                        put("backup", "files/$index")
                                        put("originalSha256", op.originalSha256)
                                        put("originalSize", files[index].length())
                                        put("sha256", op.sha256)
                                        put("size", artifacts[index]!!.length())
                                    }
                                },
                            )
                        }
                    },
                )
            }

            withContext(NonCancellable) {
                manifestFile(root, patch.patchsetId).writeText(manifest.toString(2))
                val replaced = mutableListOf<Int>()
                val merged = mutableListOf<Int>()
                val scratch = File(cache, "hive")
                try {
                    patch.ops.forEachIndexed { index, op ->
                        if (op.isRegistry) return@forEachIndexed
                        onProgress(Step.REPLACE, -1f, op.path)
                        SupportGameFiles.replaceAtomically(artifacts[index]!!, files[index])
                        replaced += index
                        if (SupportGameFiles.sha256(files[index]) != op.sha256) throw StepFailure("patched hash differs for ${op.path}")
                    }
                    patch.ops.forEachIndexed { index, op ->
                        if (!op.isRegistry) return@forEachIndexed
                        onProgress(Step.REPLACE, -1f, op.path)
                        merged += index
                        mergeRegistry(files[index], op, registryBackupFile(root, patch.patchsetId, index), scratch)
                    }
                } catch (e: Exception) {
                    merged.asReversed().forEach { index ->
                        val backup = registryBackupFile(root, patch.patchsetId, index)
                        if (backup.isFile) {
                            runCatching { restoreRegistry(files[index], backup, scratch) }
                                .onFailure { Timber.e(it, "Rolling back registry values failed") }
                        }
                    }
                    replaced.forEach { index ->
                        runCatching { SupportGameFiles.replaceAtomically(backupFile(root, patch.patchsetId, index), files[index]) }
                            .onFailure { Timber.e(it, "Rolling back a patched file failed") }
                    }
                    manifest.put("restored", true)
                    runCatching { manifestFile(root, patch.patchsetId).writeText(manifest.toString(2)) }
                    throw e
                }
                runCatching { writeAppliedExtra(context, appId, patch, appliedAt, restored = false) }
                    .onFailure { Timber.e(it, "Recording the applied patch failed") }
                postOutcome(conversationId, patch.patchsetId, SupportApi.PATCH_APPLIED, null)
            }
            Result.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Applying a support patch failed")
            val message = e.message ?: e.javaClass.simpleName
            outcome = SupportApi.PATCH_FAILED to message
            Result.Failed(message)
        } finally {
            withContext(NonCancellable) {
                runCatching { cache.deleteRecursively() }
                outcome?.let { (status, detail) -> postOutcome(conversationId, patch.patchsetId, status, detail) }
            }
        }
    }

    suspend fun restore(
        context: Context,
        appId: String,
        conversationId: String,
        patch: SupportPatch,
        onProgress: (Step, Float, String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val roots = SupportGameFiles.roots(context, appId) ?: return@withContext Result.NoGame
            val root = roots.install
            val record = manifestFile(root, patch.patchsetId)
            if (!record.isFile) return@withContext Result.Failed("")
            val manifest = JSONObject(record.readText())
            val ops = manifest.optJSONArray("ops") ?: JSONArray()
            val entries = (0 until ops.length()).mapNotNull { ops.optJSONObject(it) }
            val dir = backupDir(root, patch.patchsetId)
            val registry = entries.filter { it.optString("op") == SupportPatch.OP_REGMERGE }.map { entry ->
                val path = entry.optString("path")
                val hive = SupportGameFiles.registryHive(roots, entry.optString("hive")) ?: throw StepFailure("registry missing for $path")
                val backup = File(dir, entry.optString("backup"))
                if (!backup.canonicalPath.startsWith(dir.canonicalPath + File.separator) || !backup.isFile) {
                    throw StepFailure("backup missing for $path")
                }
                Triple(path, backup, hive)
            }
            val plan = entries.filter { it.optString("op") != SupportPatch.OP_REGMERGE }.map { entry ->
                val path = entry.optString("path")
                val target = SupportGameFiles.resolve(roots, path) ?: throw StepFailure("bad path $path")
                val copy = File(dir, entry.optString("backup"))
                if (!copy.canonicalPath.startsWith(dir.canonicalPath + File.separator) || !copy.isFile) {
                    throw StepFailure("backup missing for $path")
                }
                onProgress(Step.VERIFY, -1f, path)
                val original = entry.optString("originalSha256")
                if (SupportGameFiles.sha256(copy) != original) throw StepFailure("backup hash differs for $path")
                Triple(path, copy, target) to original
            }
            withContext(NonCancellable) {
                plan.forEach { (files, original) ->
                    val (path, copy, target) = files
                    onProgress(Step.RESTORE, -1f, path)
                    SupportGameFiles.replaceAtomically(copy, target)
                    if (SupportGameFiles.sha256(target) != original) throw StepFailure("restored hash differs for $path")
                }
                registry.asReversed().forEach { (path, backup, hive) ->
                    onProgress(Step.RESTORE, -1f, path)
                    restoreRegistry(hive, backup, File(context.cacheDir, "support-patches/${patch.patchsetId}-hive"))
                }
                manifest.put("restored", true)
                manifest.put("restoredAt", Instant.now().toString())
                record.writeText(manifest.toString(2))
                File(dir, "files").deleteRecursively()
                File(dir, "registry").deleteRecursively()
                runCatching { writeAppliedExtra(context, appId, patch, manifest.optString("appliedAt"), restored = true) }
                    .onFailure { Timber.e(it, "Recording the restored patch failed") }
                postOutcome(conversationId, patch.patchsetId, SupportApi.PATCH_RESTORED, null)
            }
            Result.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Restoring support patch files failed")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun readState(context: Context, appId: String, patch: SupportPatch): State? = withContext(Dispatchers.IO) {
        try {
            val roots = SupportGameFiles.roots(context, appId) ?: return@withContext null
            val root = roots.install
            val record = manifestFile(root, patch.patchsetId)
            if (!record.isFile) return@withContext State.NONE
            val manifest = JSONObject(record.readText())
            if (manifest.optBoolean("restored", false)) return@withContext State.RESTORED
            val ops = manifest.optJSONArray("ops") ?: return@withContext State.NONE
            for (i in 0 until ops.length()) {
                val entry = ops.optJSONObject(i) ?: continue
                if (entry.optString("op") == SupportPatch.OP_REGMERGE) {
                    val hive = SupportGameFiles.registryHive(roots, entry.optString("hive")) ?: return@withContext State.GAME_UPDATED
                    val backup = File(backupDir(root, patch.patchsetId), entry.optString("backup"))
                    if (!backup.isFile || !registryApplied(hive, backup)) return@withContext State.GAME_UPDATED
                    continue
                }
                val target = SupportGameFiles.resolve(roots, entry.optString("path")) ?: return@withContext State.GAME_UPDATED
                if (!target.isFile) return@withContext State.GAME_UPDATED
                val size = entry.optLong("size", -1L)
                if (size >= 0 && target.length() != size) return@withContext State.GAME_UPDATED
                if (SupportGameFiles.sha256(target) != entry.optString("sha256")) return@withContext State.GAME_UPDATED
            }
            State.APPLIED
        } catch (e: Exception) {
            Timber.w(e, "Reading support patch state failed")
            null
        }
    }
}
