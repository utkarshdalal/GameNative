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

    private fun opsJson(patch: SupportPatch): JSONArray = JSONArray().apply {
        patch.ops.forEach { op ->
            put(
                JSONObject().apply {
                    put("path", op.path)
                    put("originalSha256", op.originalSha256)
                    put("sha256", op.sha256)
                },
            )
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

    private fun targets(root: File, patch: SupportPatch): List<File> =
        patch.ops.map { op -> SupportGameFiles.resolve(root, op.path) ?: throw StepFailure("bad path ${op.path}") }

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
            val root = SupportGameFiles.installRoot(context, appId)
            if (root == null) {
                outcome = SupportApi.PATCH_FAILED to "game folder not found"
                return@withContext Result.NoGame
            }
            val files = targets(root, patch)

            cache.deleteRecursively()
            cache.mkdirs()
            val artifacts = patch.ops.mapIndexed { index, op ->
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
            patch.ops.forEachIndexed { index, op ->
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
                                    put("backup", "files/$index")
                                    put("originalSha256", op.originalSha256)
                                    put("originalSize", files[index].length())
                                    put("sha256", op.sha256)
                                    put("size", artifacts[index].length())
                                },
                            )
                        }
                    },
                )
            }

            withContext(NonCancellable) {
                manifestFile(root, patch.patchsetId).writeText(manifest.toString(2))
                val replaced = mutableListOf<Int>()
                try {
                    patch.ops.forEachIndexed { index, op ->
                        onProgress(Step.REPLACE, -1f, op.path)
                        SupportGameFiles.replaceAtomically(artifacts[index], files[index])
                        replaced += index
                        if (SupportGameFiles.sha256(files[index]) != op.sha256) throw StepFailure("patched hash differs for ${op.path}")
                    }
                } catch (e: Exception) {
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
            val root = SupportGameFiles.installRoot(context, appId) ?: return@withContext Result.NoGame
            val record = manifestFile(root, patch.patchsetId)
            if (!record.isFile) return@withContext Result.Failed("")
            val manifest = JSONObject(record.readText())
            val ops = manifest.optJSONArray("ops") ?: JSONArray()
            val entries = (0 until ops.length()).mapNotNull { ops.optJSONObject(it) }
            val dir = backupDir(root, patch.patchsetId)
            val plan = entries.map { entry ->
                val path = entry.optString("path")
                val target = SupportGameFiles.resolve(root, path) ?: throw StepFailure("bad path $path")
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
                manifest.put("restored", true)
                manifest.put("restoredAt", Instant.now().toString())
                record.writeText(manifest.toString(2))
                File(dir, "files").deleteRecursively()
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
            val root = SupportGameFiles.installRoot(context, appId) ?: return@withContext null
            val record = manifestFile(root, patch.patchsetId)
            if (!record.isFile) return@withContext State.NONE
            val manifest = JSONObject(record.readText())
            if (manifest.optBoolean("restored", false)) return@withContext State.RESTORED
            val ops = manifest.optJSONArray("ops") ?: return@withContext State.NONE
            for (i in 0 until ops.length()) {
                val entry = ops.optJSONObject(i) ?: continue
                val target = SupportGameFiles.resolve(root, entry.optString("path")) ?: return@withContext State.GAME_UPDATED
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
