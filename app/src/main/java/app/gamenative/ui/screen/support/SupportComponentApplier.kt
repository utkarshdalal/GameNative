package app.gamenative.ui.screen.support

import android.content.Context
import android.net.Uri
import app.gamenative.api.ApiResult
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SupportApi
import app.gamenative.api.SupportComponent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.LsfgVkManager
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.SessionReport
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
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

    private fun isExtraKey(key: String): Boolean = key == SupportComponent.KEY_LSFG_LAYER_VERSION

    private fun extraValue(container: Container, key: String): String? =
        container.getExtra(key, "").trim().takeIf { it.isNotEmpty() }

    private fun editsFor(component: SupportComponent, installedId: String): List<SupportSuggestionApplier.Edit> =
        when (component.type) {
            SupportComponent.Type.TURNIP ->
                listOf(SupportSuggestionApplier.Edit("graphicsDriverConfig.version", null, installedId))
            SupportComponent.Type.WRAPPER ->
                listOf(SupportSuggestionApplier.Edit(SupportComponent.KEY_GRAPHICS_DRIVER, null, component.versionName))
            SupportComponent.Type.LSFG ->
                listOf(SupportSuggestionApplier.Edit(SupportComponent.KEY_LSFG_LAYER_VERSION, null, installedId))
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
        SupportComponent.Type.WRAPPER -> setOf(ContentProfile.ContentType.CONTENT_TYPE_WRAPPER)
        SupportComponent.Type.TURNIP, SupportComponent.Type.LSFG -> emptySet()
    }

    private suspend fun installedIds(context: Context, type: SupportComponent.Type): List<String> {
        if (type == SupportComponent.Type.LSFG) return LsfgVkManager.installedLayerVersions(context)
        val lists = ManifestComponentHelper.loadInstalledContentLists(context)
        val installed = lists.installed
        return when (type) {
            SupportComponent.Type.TURNIP -> lists.installedDrivers
            SupportComponent.Type.WRAPPER -> installed.wrapper
            SupportComponent.Type.FEXCORE -> installed.fexcore
            SupportComponent.Type.BOX64 -> installed.box64 + installed.wowBox64
            SupportComponent.Type.DXVK -> installed.dxvk
            SupportComponent.Type.VKD3D -> installed.vkd3d
            SupportComponent.Type.PROTON -> installed.proton + installed.wine
            SupportComponent.Type.LSFG -> emptyList()
        }
    }

    private val LAYER_ENTRIES = setOf("meta.json", LsfgVkManager.LAYER_LIB_FILENAME, LsfgVkManager.LAYER_MANIFEST_FILENAME)

    private suspend fun importLayer(context: Context, component: SupportComponent, file: File): String {
        val name = component.applyValue
        if (name !in installedIds(context, component.type)) {
            val target = LsfgVkManager.layerDir(context, name)
            val staging = File(target.parentFile, ".$name.tmp")
            staging.deleteRecursively()
            staging.mkdirs()
            var metaName: String? = null
            ZipFile(file).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (entry.name !in LAYER_ENTRIES) throw StepFailure("layer zip holds an unexpected file ${entry.name}")
                    val out = File(staging, entry.name)
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (entry.name == "meta.json") metaName = runCatching { JSONObject(out.readText()).optString("name") }.getOrNull()
                }
            }
            if (metaName != name) throw StepFailure("meta.json name is ${metaName ?: "missing"}, expected $name")
            if (!File(staging, LsfgVkManager.LAYER_LIB_FILENAME).isFile) throw StepFailure("layer zip has no ${LsfgVkManager.LAYER_LIB_FILENAME}")
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw StepFailure("layer install failed")
        }
        if (name !in installedIds(context, component.type)) throw StepFailure("layer $name is not in the installed list")
        return name
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

    private suspend fun applyComponentEdits(
        context: Context,
        container: Container,
        live: ContainerData,
        edits: List<SupportSuggestionApplier.Edit>,
        onProgress: (Float) -> Unit,
        beforeSave: () -> Unit,
        source: String,
    ): SupportSuggestionApplier.Result {
        val (extras, config) = edits.partition { isExtraKey(it.key) }
        if (config.isNotEmpty()) {
            val result = SupportSuggestionApplier.applyEdits(
                context = context,
                container = container,
                live = live,
                edits = config,
                onProgress = { value, _ -> onProgress(value) },
                beforeSave = if (extras.isEmpty()) beforeSave else ({ }),
                source = source,
            )
            if (result != SupportSuggestionApplier.Result.Done) return result
        }
        if (extras.isNotEmpty()) {
            withContext(NonCancellable) {
                for (edit in extras) container.putExtra(edit.key, edit.value)
                beforeSave()
                container.saveData()
                SessionReport.markConfigApplied(container, source)
            }
        }
        return SupportSuggestionApplier.Result.Done
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
            val layer = component.packageFormat == SupportComponent.PackageFormat.LSFG_LAYER_ZIP
            val bionicOnly = driver || layer || component.type == SupportComponent.Type.WRAPPER
            if (bionicOnly && !ContainerUtils.toContainerData(container).containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
                throw StepFailure("${component.type.id} builds need a bionic container")
            }

            cache.deleteRecursively()
            cache.mkdirs()
            val file = File(cache, if (driver || layer) "package.zip" else "package.wcp")
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
            val installedId = when {
                driver -> importDriver(context, component, file)
                layer -> importLayer(context, component, file)
                else -> importContent(context, component, file)
            }

            onProgress(Step.APPLY, -1f)
            val live = ContainerUtils.toContainerData(container)
            val edits = editsFor(component, installedId)
            val befores = edits.map { if (isExtraKey(it.key)) extraValue(container, it.key) else liveValue(live, it.key) }
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
            val result = applyComponentEdits(
                context = context,
                container = container,
                live = live,
                edits = edits,
                onProgress = { value -> onProgress(Step.APPLY, value) },
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
                    if (before == null && !key.contains('.') && !isExtraKey(key)) continue
                    add(SupportSuggestionApplier.Edit(key, null, before))
                }
            }
            onProgress(Step.RESTORE, -1f)
            val result = applyComponentEdits(
                context = context,
                container = container,
                live = ContainerUtils.toContainerData(container),
                edits = edits,
                onProgress = { value -> onProgress(Step.RESTORE, value) },
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
