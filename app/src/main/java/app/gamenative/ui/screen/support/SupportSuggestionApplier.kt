package app.gamenative.ui.screen.support

import android.content.Context
import app.gamenative.api.SupportSuggestion
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.SessionReport
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object SupportSuggestionApplier {

    private const val SOURCE_APPLIED = "ai_suggestion"
    private const val SOURCE_RESTORED = "ai_suggestion_restored"
    private const val MATCH_TYPE = "exact_gpu_match"

    private val COMPONENT_KEYS = setOf(
        "wineVersion", "dxwrapper", "dxwrapperConfig", "box64Version", "box64Preset", "emulator",
        "fexcoreVersion", "fexcorePreset", "graphicsDriver", "graphicsDriverVersion", "graphicsDriverConfig",
    )

    val WARNING_KEYS = setOf("wineVersion", "graphicsDriver", "graphicsDriverVersion", "graphicsDriverConfig")

    data class Edit(val key: String, val name: String?, val value: String?)

    sealed class Result {
        data object Done : Result()
        data object NoContainer : Result()
        data class MissingComponents(val names: List<String>) : Result()
        data class Failed(val message: String) : Result()
    }

    fun snapshotFile(container: Container, messageId: Long): File =
        File(container.rootDir, ".gamenative/suggestions/$messageId.json")

    fun appliedRecordFile(container: Container): File =
        File(container.rootDir, ".gamenative/applied_suggestion.json")

    private fun kvGet(data: String, key: String): String? {
        for (pair in KeyValueSet(data)) {
            if (pair[0] == key) return pair[1]
        }
        return null
    }

    private fun kvRemove(data: String, key: String): String {
        val parts = mutableListOf<String>()
        for (pair in KeyValueSet(data)) {
            if (pair[0] != key) parts += "${pair[0]}=${pair[1]}"
        }
        return parts.joinToString(",")
    }

    fun liveValue(data: ContainerData, change: SupportSuggestion.Change): String? {
        if (change.isEnv) {
            val name = change.name ?: return null
            val env = EnvVars(data.envVars)
            return if (env.has(name)) env.get(name) else null
        }
        val subKey = change.subKey
        if (subKey != null) {
            return kvGet(SuggestionConfigKeys.configValueOf(data, change.parent).orEmpty(), subKey)
        }
        return SuggestionConfigKeys.configValueOf(data, change.key)
    }

    fun editFor(change: SupportSuggestion.Change): Edit =
        Edit(change.key, change.name, if (change.isUnset) null else change.to)

    private fun updatesFor(live: ContainerData, edits: List<Edit>): Map<String, Any?> {
        val updates = linkedMapOf<String, Any?>()
        var env: EnvVars? = null
        val kv = linkedMapOf<String, String>()
        for (edit in edits) {
            when {
                edit.key == SupportSuggestion.ENV_KEY -> {
                    val name = edit.name ?: continue
                    val vars = env ?: EnvVars(live.envVars).also { env = it }
                    if (edit.value == null) vars.remove(name) else vars.put(name, edit.value)
                }
                edit.key.contains('.') -> {
                    val parent = edit.key.substringBefore('.')
                    val subKey = edit.key.substringAfter('.')
                    val current = kv[parent] ?: SuggestionConfigKeys.configValueOf(live, parent).orEmpty()
                    kv[parent] = if (edit.value == null) {
                        kvRemove(current, subKey)
                    } else {
                        KeyValueSet(current).put(subKey, edit.value).toString()
                    }
                }
                else -> {
                    val type = SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS[edit.key] ?: continue
                    SuggestionConfigKeys.coerceConfigValue(type, edit.value)?.let { updates[edit.key] = it }
                }
            }
        }
        env?.let { updates[SupportSuggestion.ENV_KEY] = it.toString() }
        updates.putAll(kv)
        return updates
    }

    private fun componentJson(data: ContainerData): JSONObject = JSONObject().apply {
        put("containerVariant", data.containerVariant)
        put("wineVersion", data.wineVersion)
        put("dxwrapper", data.dxwrapper)
        put("dxwrapperConfig", data.dxwrapperConfig)
        put("box64Version", data.box64Version)
        put("box64Preset", data.box64Preset)
        put("emulator", data.emulator)
        put("fexcoreVersion", data.fexcoreVersion)
        put("fexcorePreset", data.fexcorePreset)
        put("graphicsDriver", data.graphicsDriver)
        put("graphicsDriverVersion", data.graphicsDriverVersion)
        put("graphicsDriverConfig", data.graphicsDriverConfig)
    }

    private suspend fun ensureComponents(
        context: Context,
        data: ContainerData,
        onProgress: (Float, String) -> Unit,
    ): Result? {
        val configJson = Json.parseToJsonElement(componentJson(data).toString()).jsonObject
        val requests = BestConfigService.resolveMissingManifestInstallRequests(
            context = context,
            configJson = configJson,
            matchType = MATCH_TYPE,
            preserveConfigValues = true,
        )
        for (request in requests) {
            val label = request.entry.id
            onProgress(-1f, label)
            val result = ManifestInstaller.installManifestEntry(
                context = context,
                entry = request.entry,
                isDriver = request.isDriver,
                contentType = request.contentType,
                onProgress = { progress -> onProgress(progress.coerceIn(0f, 1f), label) },
            )
            if (!result.success) return Result.Failed(result.message)
        }
        val missing = BestConfigService.parseConfigResult(
            context = context,
            configJson = configJson,
            matchType = MATCH_TYPE,
            applyKnownConfig = true,
            preserveConfigValues = true,
        ).missingComponents
        return if (missing.isNotEmpty()) Result.MissingComponents(missing) else null
    }

    private fun writeAppliedExtra(
        container: Container,
        messageId: Long,
        conversationId: String,
        applied: JSONArray,
        restored: Boolean,
    ) {
        val recordFile = appliedRecordFile(container)
        val previous = runCatching { JSONObject(recordFile.readText()) }.getOrNull()
        val record = if (restored && previous != null && previous.optLong("messageId", -1L) == messageId) {
            previous
        } else {
            JSONObject().apply {
                put("messageId", messageId)
                put("conversationId", conversationId)
                put("changes", applied)
                put("appliedAt", Instant.now().toString())
            }
        }
        record.put("restored", restored)
        if (restored) record.put("restoredAt", Instant.now().toString())
        recordFile.parentFile?.mkdirs()
        recordFile.writeText(record.toString())
    }

    internal suspend fun applyEdits(
        context: Context,
        container: Container,
        live: ContainerData,
        edits: List<Edit>,
        onProgress: (Float, String) -> Unit,
        beforeSave: () -> Unit,
        source: String,
    ): Result {
        val updated = ContainerUtils.applyBestConfigMapToContainerData(live, updatesFor(live, edits))
        if (edits.any { it.key.substringBefore('.') in COMPONENT_KEYS }) {
            ensureComponents(context, updated, onProgress)?.let { return it }
        }
        withContext(NonCancellable) {
            ContainerUtils.applyToContainer(context, container, updated)
            beforeSave()
            SessionReport.markConfigApplied(container, source)
        }
        return Result.Done
    }

    suspend fun apply(
        context: Context,
        appId: String,
        messageId: Long,
        conversationId: String,
        suggestion: SupportSuggestion,
        onProgress: (Float, String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!suggestion.applicable) return@withContext Result.Failed("")
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext Result.NoContainer
            val container = ContainerUtils.getContainer(context, appId)
            val live = ContainerUtils.toContainerData(container)
            val snapshot = JSONArray()
            val applied = JSONArray()
            suggestion.changes.forEach { change ->
                val before = liveValue(live, change)
                snapshot.put(
                    JSONObject().apply {
                        put("key", change.key)
                        if (change.name != null) put("name", change.name)
                        put("before", before ?: JSONObject.NULL)
                    },
                )
                applied.put(
                    JSONObject().apply {
                        put("key", change.key)
                        if (change.name != null) put("name", change.name)
                        if (change.op != null) put("op", change.op)
                        put("from", before ?: JSONObject.NULL)
                        put("to", if (change.isUnset) JSONObject.NULL else change.to)
                    },
                )
            }
            val file = snapshotFile(container, messageId)
            val result = applyEdits(
                context = context,
                container = container,
                live = live,
                edits = suggestion.changes.map { editFor(it) },
                onProgress = onProgress,
                beforeSave = {
                    if (!file.exists()) {
                        file.parentFile?.mkdirs()
                        file.writeText(
                            JSONObject().apply {
                                put("messageId", messageId)
                                put("conversationId", conversationId)
                                put("createdAt", Instant.now().toString())
                                put("entries", snapshot)
                            }.toString(),
                        )
                    }
                    writeAppliedExtra(container, messageId, conversationId, applied, restored = false)
                },
                source = SOURCE_APPLIED,
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Applying a support suggestion failed")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun restore(
        context: Context,
        appId: String,
        messageId: Long,
        conversationId: String,
        onProgress: (Float, String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext Result.NoContainer
            val container = ContainerUtils.getContainer(context, appId)
            val file = snapshotFile(container, messageId)
            if (!file.exists()) return@withContext Result.Failed("")
            val entries = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            val edits = buildList {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val key = entry.optString("key", "")
                    if (key.isEmpty()) continue
                    val name = if (entry.isNull("name")) null else entry.optString("name").ifEmpty { null }
                    val before = if (entry.isNull("before")) null else entry.optString("before")
                    if (before == null && key != SupportSuggestion.ENV_KEY && !key.contains('.')) continue
                    add(Edit(key, name, before))
                }
            }
            val live = ContainerUtils.toContainerData(container)
            val result = applyEdits(
                context = context,
                container = container,
                live = live,
                edits = edits,
                onProgress = onProgress,
                beforeSave = { writeAppliedExtra(container, messageId, conversationId, JSONArray(), restored = true) },
                source = SOURCE_RESTORED,
            )
            if (result == Result.Done) file.delete()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Restoring support suggestion settings failed")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun hasSnapshot(context: Context, appId: String, messageId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            ContainerUtils.hasContainer(context, appId) &&
                snapshotFile(ContainerUtils.getContainer(context, appId), messageId).exists()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readLive(context: Context, appId: String, suggestion: SupportSuggestion): List<String?>? =
        withContext(Dispatchers.IO) {
            try {
                if (!ContainerUtils.hasContainer(context, appId)) return@withContext null
                val live = ContainerUtils.toContainerData(ContainerUtils.getContainer(context, appId))
                suggestion.changes.map { liveValue(live, it) }
            } catch (e: Exception) {
                Timber.w(e, "Reading live settings for a support suggestion failed")
                null
            }
        }
}
