package app.gamenative.ui.screen.support

import android.content.Context
import android.net.Uri
import app.gamenative.api.ApiResult
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SupportApi
import app.gamenative.api.SupportComponent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestInstaller
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object SupportComponentApplier {

    private const val SOURCE_APPLIED = "ai_component"
    private const val SOURCE_RESTORED = "ai_component_restored"

    enum class State { NONE, APPLIED, RESTORED }

    enum class Step { DOWNLOAD, VERIFY, IMPORT, APPLY, RESTORE }

    sealed class Result {
        data object Done : Result()
        data object NoContainer : Result()
        data object HashMismatch : Result()
        data class Failed(val message: String) : Result()
    }

    private class StepFailure(message: String) : Exception(message)

    fun appliedRecordFile(container: Container): File =
        File(container.rootDir, ".gamenative/applied_component.json")

    private fun snapshotFile(container: Container, componentId: String): File =
        File(container.rootDir, ".gamenative/components/$componentId.json")

    private fun kvGet(data: String, key: String): String? {
        for (pair in KeyValueSet(data)) {
            if (pair[0] == key) return pair[1]
        }
        return null
    }

    private fun liveValue(data: ContainerData, key: String): String? =
        if (key.contains('.')) {
            kvGet(SuggestionConfigKeys.configValueOf(data, key.substringBefore('.')).orEmpty(), key.substringAfter('.'))
        } else {
            SuggestionConfigKeys.configValueOf(data, key)
        }

    private fun editsFor(component: SupportComponent, installedId: String): List<SupportSuggestionApplier.Edit> =
        when (component.type) {
            SupportComponent.Type.TURNIP, SupportComponent.Type.WRAPPER ->
                listOf(SupportSuggestionApplier.Edit("graphicsDriverConfig.version", null, installedId))
            SupportComponent.Type.FEXCORE -> listOf(SupportSuggestionApplier.Edit("fexcoreVersion", null, installedId))
            SupportComponent.Type.BOX64 -> listOf(SupportSuggestionApplier.Edit("box64Version", null, installedId))
            SupportComponent.Type.DXVK -> listOf(
                SupportSuggestionApplier.Edit("dxwrapper", null, "dxvk"),
                SupportSuggestionApplier.Edit("dxwrapperConfig.version", null, installedId),
            )
            SupportComponent.Type.VKD3D -> listOf(
                SupportSuggestionApplier.Edit("dxwrapper", null, "vkd3d"),
                SupportSuggestionApplier.Edit("dxwrapperConfig.vkd3dVersion", null, installedId),
            )
            SupportComponent.Type.PROTON -> listOf(SupportSuggestionApplier.Edit("wineVersion", null, installedId))
        }

    private fun contentTypes(type: SupportComponent.Type): Set<ContentProfile.ContentType> = when (type) {
        SupportComponent.Type.FEXCORE -> setOf(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)
        SupportComponent.Type.BOX64 -> setOf(ContentProfile.ContentType.CONTENT_TYPE_BOX64, ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64)
        SupportComponent.Type.DXVK -> setOf(ContentProfile.ContentType.CONTENT_TYPE_DXVK)
        SupportComponent.Type.VKD3D -> setOf(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)
        SupportComponent.Type.PROTON -> setOf(ContentProfile.ContentType.CONTENT_TYPE_PROTON, ContentProfile.ContentType.CONTENT_TYPE_WINE)
        SupportComponent.Type.TURNIP, SupportComponent.Type.WRAPPER -> emptySet()
    }

    private suspend fun installedIds(context: Context, type: SupportComponent.Type): List<String> {
        val lists = ManifestComponentHelper.loadInstalledContentLists(context)
        val installed = lists.installed
        return when (type) {
            SupportComponent.Type.TURNIP, SupportComponent.Type.WRAPPER -> lists.installedDrivers
            SupportComponent.Type.FEXCORE -> installed.fexcore
            SupportComponent.Type.BOX64 -> installed.box64 + installed.wowBox64
            SupportComponent.Type.DXVK -> installed.dxvk
            SupportComponent.Type.VKD3D -> installed.vkd3d
            SupportComponent.Type.PROTON -> installed.proton + installed.wine
        }
    }

    private suspend fun importDriver(context: Context, component: SupportComponent, file: File): String {
        val name = component.applyValue
        if (name !in installedIds(context, component.type)) {
            val installed = AdrenotoolsManager(context).installDriver(Uri.fromFile(file))
            if (installed.isEmpty()) throw StepFailure("driver import failed: bad zip, no meta.json, or name already used")
            if (installed != name) throw StepFailure("driver installed as $installed, expected $name")
        }
        if (name !in installedIds(context, component.type)) throw StepFailure("driver $name is not in the installed list")
        return name
    }

    private suspend fun importContent(context: Context, component: SupportComponent, file: File): String {
        val manager = ContentsManager(context)
        val (profile, reason, error) = ManifestInstaller.extractContent(manager, Uri.fromFile(file))
        if (profile == null) {
            throw StepFailure("package import failed: ${reason?.name ?: error?.message ?: "unknown"}")
        }
        if (profile.type !in contentTypes(component.type)) {
            ContentsManager.cleanTmpDir(context)
            throw StepFailure("package type is ${profile.type}, expected ${component.type.id}")
        }
        if (profile.verName != component.applyValue) {
            ContentsManager.cleanTmpDir(context)
            throw StepFailure("package version is ${profile.verName}, expected ${component.applyValue}")
        }
        val id = "${profile.verName}-${profile.verCode}"
        if (installedIds(context, component.type).none { it.equals(id, ignoreCase = true) }) {
            if (!ManifestInstaller.finishInstall(manager, profile)) throw StepFailure("package install failed")
        } else {
            ContentsManager.cleanTmpDir(context)
        }
        return installedIds(context, component.type).firstOrNull { it.equals(id, ignoreCase = true) }
            ?: throw StepFailure("$id is not in the installed list")
    }

    private fun writeAppliedRecord(
        container: Container,
        component: SupportComponent,
        from: String?,
        to: String?,
        appliedAt: String,
        restored: Boolean,
    ) {
        val file = appliedRecordFile(container)
        val record = if (restored) {
            runCatching { JSONObject(file.readText()) }.getOrNull()
                ?.takeIf { it.optString("componentId") == component.componentId }
                ?: return
        } else {
            JSONObject().apply {
                put("componentId", component.componentId)
                put("type", component.type.id)
                put("key", component.applyKey)
                put("from", from ?: JSONObject.NULL)
                put("to", to ?: JSONObject.NULL)
                put("version", component.versionName)
                put("appliedAt", appliedAt)
                component.run?.let { put("run", it.toRunJson()) }
            }
        }
        record.put("restored", restored)
        if (restored) record.put("restoredAt", Instant.now().toString())
        file.parentFile?.mkdirs()
        file.writeText(record.toString())
    }

    private suspend fun postOutcome(conversationId: String, componentId: String, status: String, detail: String?) {
        try {
            val result = SupportApi.componentOutcome(conversationId, componentId, status, detail)
            if (result !is ApiResult.Success) Timber.w("Posting component outcome $status failed: $result")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Posting component outcome $status failed")
        }
    }

    private fun editFailure(result: SupportSuggestionApplier.Result): String = when (result) {
        is SupportSuggestionApplier.Result.MissingComponents -> "missing ${result.names.joinToString(", ")}"
        is SupportSuggestionApplier.Result.Failed -> result.message.ifEmpty { "settings not saved" }
        SupportSuggestionApplier.Result.NoContainer -> "no game settings"
        SupportSuggestionApplier.Result.Done -> ""
    }

    suspend fun apply(
        context: Context,
        appId: String,
        conversationId: String,
        component: SupportComponent,
        onProgress: (Step, Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "support-components/${component.componentId}")
        var outcome: Pair<String, String?>? = null
        try {
            if (!ContainerUtils.hasContainer(context, appId)) {
                outcome = SupportApi.PATCH_FAILED to "no container for the game"
                return@withContext Result.NoContainer
            }
            val container = ContainerUtils.getContainer(context, appId)
            val driver = component.packageFormat == SupportComponent.PackageFormat.ADRENOTOOLS_ZIP
            if (driver && !ContainerUtils.toContainerData(container).containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                throw StepFailure("AdrenoTools drivers need a bionic container")
            }

            cache.deleteRecursively()
            cache.mkdirs()
            val file = File(cache, if (driver) "package.zip" else "package.wcp")
            onProgress(Step.DOWNLOAD, 0f)
            when (val result = SupportApi.downloadSigned(component.artifactUrl, file) { onProgress(Step.DOWNLOAD, it) }) {
                is ApiResult.Success -> if (result.data != component.sha256) {
                    outcome = SupportApi.PATCH_HASH_MISMATCH to "artifact ${result.data.take(12)}, expected ${component.sha256.take(12)}"
                    return@withContext Result.HashMismatch
                }
                is ApiResult.HttpError -> throw StepFailure("artifact download HTTP ${result.code}")
                is ApiResult.NetworkError -> throw StepFailure("artifact download failed")
            }
            onProgress(Step.VERIFY, -1f)
            if (file.length() != component.size) {
                outcome = SupportApi.PATCH_HASH_MISMATCH to "artifact size ${file.length()}, expected ${component.size}"
                return@withContext Result.HashMismatch
            }

            onProgress(Step.IMPORT, -1f)
            val installedId = if (driver) importDriver(context, component, file) else importContent(context, component, file)

            onProgress(Step.APPLY, -1f)
            val live = ContainerUtils.toContainerData(container)
            val edits = editsFor(component, installedId)
            val befores = edits.map { liveValue(live, it.key) }
            val appliedAt = Instant.now().toString()
            val snapshot = JSONObject().apply {
                put("componentId", component.componentId)
                put("conversationId", conversationId)
                put("createdAt", appliedAt)
                put("restored", false)
                put(
                    "entries",
                    JSONArray().apply {
                        edits.forEachIndexed { index, edit ->
                            put(
                                JSONObject().apply {
                                    put("key", edit.key)
                                    put("before", befores[index] ?: JSONObject.NULL)
                                },
                            )
                        }
                    },
                )
            }
            val record = snapshotFile(container, component.componentId)
            record.parentFile?.mkdirs()
            record.writeText(snapshot.toString())
            val result = SupportSuggestionApplier.applyEdits(
                context = context,
                container = container,
                live = live,
                edits = edits,
                onProgress = { value, _ -> onProgress(Step.APPLY, value) },
                beforeSave = {
                    runCatching { writeAppliedRecord(container, component, befores.last(), installedId, appliedAt, restored = false) }
                        .onFailure { Timber.e(it, "Recording the applied component failed") }
                },
                source = SOURCE_APPLIED,
            )
            if (result != SupportSuggestionApplier.Result.Done) {
                record.delete()
                throw StepFailure(editFailure(result))
            }
            withContext(NonCancellable) {
                postOutcome(conversationId, component.componentId, SupportApi.PATCH_APPLIED, null)
            }
            Result.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Applying a support component failed")
            val message = e.message ?: e.javaClass.simpleName
            outcome = SupportApi.PATCH_FAILED to message
            Result.Failed(message)
        } finally {
            withContext(NonCancellable) {
                runCatching { cache.deleteRecursively() }
                outcome?.let { (status, detail) -> postOutcome(conversationId, component.componentId, status, detail) }
            }
        }
    }

    suspend fun restore(
        context: Context,
        appId: String,
        conversationId: String,
        component: SupportComponent,
        onProgress: (Step, Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext Result.NoContainer
            val container = ContainerUtils.getContainer(context, appId)
            val record = snapshotFile(container, component.componentId)
            if (!record.isFile) return@withContext Result.Failed("")
            val snapshot = JSONObject(record.readText())
            if (snapshot.optBoolean("restored", false)) return@withContext Result.Failed("")
            val entries = snapshot.optJSONArray("entries") ?: JSONArray()
            val edits = buildList {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val key = entry.optString("key", "")
                    if (key.isEmpty()) continue
                    val before = if (entry.isNull("before")) null else entry.optString("before")
                    if (before == null && !key.contains('.')) continue
                    add(SupportSuggestionApplier.Edit(key, null, before))
                }
            }
            onProgress(Step.RESTORE, -1f)
            val result = SupportSuggestionApplier.applyEdits(
                context = context,
                container = container,
                live = ContainerUtils.toContainerData(container),
                edits = edits,
                onProgress = { value, _ -> onProgress(Step.RESTORE, value) },
                beforeSave = { },
                source = SOURCE_RESTORED,
            )
            if (result != SupportSuggestionApplier.Result.Done) return@withContext Result.Failed(editFailure(result))
            withContext(NonCancellable) {
                snapshot.put("restored", true)
                snapshot.put("restoredAt", Instant.now().toString())
                record.writeText(snapshot.toString())
                runCatching { writeAppliedRecord(container, component, null, null, "", restored = true) }
                    .onFailure { Timber.e(it, "Recording the restored component failed") }
                postOutcome(conversationId, component.componentId, SupportApi.PATCH_RESTORED, null)
            }
            Result.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Restoring the previous component failed")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun readState(context: Context, appId: String, component: SupportComponent): State? = withContext(Dispatchers.IO) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext null
            val record = snapshotFile(ContainerUtils.getContainer(context, appId), component.componentId)
            if (!record.isFile) return@withContext State.NONE
            if (JSONObject(record.readText()).optBoolean("restored", false)) State.RESTORED else State.APPLIED
        } catch (e: Exception) {
            Timber.w(e, "Reading support component state failed")
            null
        }
    }
}
