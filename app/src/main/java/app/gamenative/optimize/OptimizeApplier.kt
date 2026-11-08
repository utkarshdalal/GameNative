package app.gamenative.optimize

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.api.SuggestionConfigKeys
import app.gamenative.api.SupportSuggestion
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import app.gamenative.ui.screen.support.WineRegistryText
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

sealed class ApplyOutcome {
    /** [appliedOps] and [skippedOps] count file keys and registry values; a relative rung skips those already lower. */
    data class Applied(val levers: List<JSONObject>, val appliedOps: Int = 0, val skippedOps: Int = 0) : ApplyOutcome()

    /** A relative rung whose every key was already at or below its target: nothing was written. */
    data class NoOp(val skippedOps: Int) : ApplyOutcome()

    data class Failed(val leversOk: Int, val failedIndex: Int, val reason: String) : ApplyOutcome()

    /** Nothing was written and nothing should be recorded; the rung can be asked for again later. */
    data class Deferred(val reason: String) : ApplyOutcome()
}

/** Applies a rung's levers all-or-nothing, keeping what each lever needs to be taken off again. */
object OptimizeApplier {

    const val SOURCE = "optimize"
    private val SCREEN_SIZE = Regex("^[0-9]{2,5}x[0-9]{2,5}$")

    private class LeverFailure(message: String) : Exception(message)

    internal class OpCount(var applied: Int = 0, var skipped: Int = 0)

    /** A kept lever checked before a launch: the [record] to keep, how many keys the game had [reset], and whether its file is kept read-only. */
    data class Reverified(val record: JSONObject, val reset: Int, val readOnly: Boolean)

    suspend fun apply(
        context: Context,
        container: Container,
        roots: PathRoots,
        rungDir: File,
        rung: Rung,
        nowMillis: Long = System.currentTimeMillis(),
        reapply: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ApplyOutcome = withContext(Dispatchers.IO) {
        if (PluviaApp.xEnvironment != null) return@withContext ApplyOutcome.Deferred("game running")
        if (!reapply && rung.isExpired(nowMillis)) return@withContext ApplyOutcome.Deferred("expired")
        if (rung.levers.isEmpty()) return@withContext ApplyOutcome.Failed(0, -1, "no levers")
        rung.levers.indexOfFirst { it is Lever.Unknown }.takeIf { it >= 0 }?.let {
            return@withContext ApplyOutcome.Failed(0, it, "unknown lever ${rung.levers[it].type}")
        }
        val fileLever = rung.levers.indexOfFirst { it is Lever.EngineFile || it is Lever.Registry }
        if (fileLever >= 0) {
            val anticheat = OptimizeFiles.anticheat(container) ?: return@withContext ApplyOutcome.Deferred("game files not scanned yet")
            if (anticheat.isNotEmpty()) return@withContext ApplyOutcome.Failed(0, fileLever, "anti-cheat ${anticheat.joinToString()}")
        }

        rungDir.deleteRecursively()
        rungDir.mkdirs()
        val applied = mutableListOf<JSONObject>()
        val count = OpCount()
        withContext(NonCancellable) {
            for ((i, lever) in rung.levers.withIndex()) {
                try {
                    applied += when (lever) {
                        is Lever.EngineFile -> applyFile(i, lever, roots, rungDir, rung.relative, count) ?: continue
                        is Lever.Registry -> applyRegistry(i, lever, container, rung.relative, count) ?: continue
                        is Lever.ContainerSet -> applyContainer(i, lever, context, container, onProgress)
                        // The bionic Turnip and wrapper are built without driconf files; Mesa still reads each option
                        // from an env var of the same name.
                        is Lever.Drirc -> if (Container.GLIBC.equals(container.containerVariant, ignoreCase = true)) {
                            applyDrirc(i, lever, container, rungDir)
                        } else {
                            val env = Lever.ContainerSet(lever.options.map { "envVars.${it.first}" to it.second })
                            applyContainer(i, env, context, container, onProgress)
                        }
                        is Lever.Unknown -> throw LeverFailure("unknown lever")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag("Optimize").w(e, "Lever $i of ${rung.rungId} failed, taking the others off")
                    OptimizeReverter.revertLevers(context, container, applied)
                    rungDir.deleteRecursively()
                    return@withContext ApplyOutcome.Failed(i, i, e.message ?: e.javaClass.simpleName)
                }
            }
            if (rung.relative && applied.isEmpty() && count.skipped > 0) {
                rungDir.deleteRecursively()
                ApplyOutcome.NoOp(count.skipped)
            } else {
                ApplyOutcome.Applied(applied, count.applied, count.skipped)
            }
        }
    }

    /** Null when a relative rung skipped every op of the lever and nothing was written. */
    internal fun applyFile(index: Int, lever: Lever.EngineFile, roots: PathRoots, rungDir: File, relative: Boolean, count: OpCount): JSONObject? {
        val format = ConfigFormats.forName(lever.format) ?: throw LeverFailure("unknown format ${lever.format}")
        val file = OptimizePaths.resolve(roots, lever.path, lever.createIfMissing) ?: throw LeverFailure("path not resolvable")
        if (file.isDirectory) throw LeverFailure("path is a folder")
        val existed = file.isFile
        if (!existed && !lever.createIfMissing) throw LeverFailure("file missing")
        val wasWritable = !existed || file.canWrite()
        val bytes = if (existed) file.readBytes() else ByteArray(0)
        val codec = if (existed) TextCodecs.codecFor(bytes, lever.encoding) else TextCodecs.forNewFile(lever.encoding)
        val text = TextCodecs.decode(bytes, codec)
        val ops = if (!relative) {
            lever.ops
        } else {
            lever.ops.filter { op ->
                op.isDelete || OptimizeRelative.shouldApply(if (text.isBlank()) null else format.read(text, op), op.value!!, op.ladder, op.lowerIs)
            }
        }
        count.applied += ops.size
        count.skipped += lever.ops.size - ops.size
        if (ops.isEmpty()) return null
        val keys = JSONArray()
        ops.distinctBy { it.section to it.key }.forEach { op ->
            val prior = if (text.isBlank()) null else format.read(text, op)
            val last = ops.last { it.section == op.section && it.key == op.key }
            keys.put(
                JSONObject().apply {
                    put("section", op.section)
                    put("key", op.key)
                    put("prior", prior ?: JSONObject.NULL)
                    put("op", last.op)
                    put("value", last.value ?: JSONObject.NULL)
                },
            )
        }
        val newBytes = TextCodecs.encode(ConfigFormats.applyVerified(format, text, ops), codec)
        val backup = File(rungDir, "files/$index")
        if (existed) {
            backup.parentFile?.mkdirs()
            backup.writeBytes(bytes)
        }
        if (existed && !wasWritable) file.setWritable(true)
        OptimizeFiles.atomicWrite(file, newBytes)
        val written = file.readBytes()
        if (!written.contentEquals(newBytes)) {
            if (existed) OptimizeFiles.atomicWrite(file, bytes) else file.delete()
            if (existed && !wasWritable) file.setWritable(false, false)
            throw LeverFailure("file did not take")
        }
        if ((lever.readOnly || !wasWritable) && !file.setWritable(false, false)) Timber.tag("Optimize").w("Could not make ${file.name} read-only")
        return JSONObject().apply {
            put("type", Lever.TYPE_ENGINE_FILE)
            put("index", index)
            put("path", file.absolutePath)
            put("format", lever.format)
            put("encoding", lever.encoding)
            put("existed", existed)
            put("backup", if (existed) backup.absolutePath else JSONObject.NULL)
            put("sha_before", if (existed) OptimizeFiles.sha256(bytes) else JSONObject.NULL)
            put("sha_after", OptimizeFiles.sha256(written))
            put("keys", keys)
            put("read_only", lever.readOnly)
            put("was_writable", wasWritable)
        }
    }

    /**
     * Puts back the keys of a kept file or registry lever that the game has reset since it was written. The original
     * backup and priors stay, so taking the rung off still restores what the game had before. Null for other levers,
     * and when there is nothing to compare against.
     */
    internal fun reverify(container: Container, record: JSONObject, lever: Lever?): Reverified? = when (record.optString("type")) {
        Lever.TYPE_ENGINE_FILE -> reverifyFile(record, lever)
        Lever.TYPE_REGISTRY -> reverifyRegistry(OptimizeFiles.userHive(container), record, lever)
        else -> null
    }

    internal fun reverifyFile(record: JSONObject, lever: Lever?): Reverified? {
        val file = File(record.getString("path"))
        if (!file.isFile) return null
        val format = ConfigFormats.forName(record.optString("format")) ?: return null
        val written = OptimizeDrift.writtenOps(record, lever) ?: return null
        val readOnly = record.optBoolean("read_only", false)
        val keepReadOnly = readOnly || !record.optBoolean("was_writable", true)
        val bytes = file.readBytes()
        val codec = TextCodecs.codecFor(bytes, record.optString("encoding", "default"))
        val text = TextCodecs.decode(bytes, codec)
        val drifted = OptimizeDrift.fileDrift(format, text, written)
        if (drifted.isEmpty()) {
            if (keepReadOnly && file.canWrite()) file.setWritable(false, false)
            return Reverified(record, 0, readOnly)
        }
        val newBytes = TextCodecs.encode(ConfigFormats.applyVerified(format, text, drifted), codec)
        if (!file.canWrite()) file.setWritable(true)
        OptimizeFiles.atomicWrite(file, newBytes)
        val after = file.readBytes()
        if (!after.contentEquals(newBytes)) throw LeverFailure("file did not take")
        if (keepReadOnly) file.setWritable(false, false)
        return Reverified(JSONObject(record.toString()).put("sha_after", OptimizeFiles.sha256(after)), drifted.size, readOnly)
    }

    internal fun reverifyRegistry(hive: File, record: JSONObject, lever: Lever?): Reverified? {
        if (!hive.isFile) return null
        val text = OptimizeFiles.readHive(hive)
        val drifted = OptimizeDrift.registryDrift(text, record)
        if (drifted.isEmpty()) return Reverified(record, 0, false)
        val entries = record.optJSONArray("values").objects()
        val values = (lever as? Lever.Registry)?.values.orEmpty()
        val changes = drifted.mapNotNull { name ->
            val entry = entries.first { it.getString("name") == name }
            val raw = when {
                entry.isNull("applied") -> null
                entry.has("raw") && !entry.isNull("raw") -> entry.getString("raw")
                else -> values.lastOrNull { it.name.equals(name, ignoreCase = true) && !it.delete }
                    ?.let { OptimizeFiles.encodeRegistryValue(it.type!!, it.data!!) } ?: return@mapNotNull null
            }
            WineRegistryText.Change(name, raw)
        }
        if (changes.isEmpty()) return Reverified(record, 0, false)
        val key = record.getString("key")
        OptimizeFiles.writeHive(hive, WineRegistryText.merge(text, key, changes, System.currentTimeMillis()).text)
        if (OptimizeDrift.registryDrift(OptimizeFiles.readHive(hive), record).any { name -> changes.any { it.name == name } }) {
            throw LeverFailure("registry values did not take")
        }
        return Reverified(record, changes.size, false)
    }

    /** Glibc Mesa reads $HOME/.drirc, and the guest HOME resolves to the container root. */
    private fun applyDrirc(index: Int, lever: Lever.Drirc, container: Container, rungDir: File): JSONObject {
        val file = OptimizeFiles.drirc(container)
        if (file.isDirectory) throw LeverFailure("drirc is a folder")
        val existed = file.isFile
        val bytes = if (existed) file.readBytes() else ByteArray(0)
        val merged = Driconf.merge(bytes.toString(Charsets.UTF_8), lever.executable, lever.options)
        val newBytes = merged.text.toByteArray(Charsets.UTF_8)
        val backup = File(rungDir, "files/$index")
        if (existed) {
            backup.parentFile?.mkdirs()
            backup.writeBytes(bytes)
        }
        OptimizeFiles.atomicWrite(file, newBytes)
        val written = file.readBytes()
        val now = runCatching { Driconf.read(written.toString(Charsets.UTF_8), lever.executable) }.getOrNull()
        if (!written.contentEquals(newBytes) || now == null || lever.options.any { (name, value) -> now[name] != value }) {
            if (existed) OptimizeFiles.atomicWrite(file, bytes) else file.delete()
            throw LeverFailure("drirc did not take")
        }
        return JSONObject().apply {
            put("type", Lever.TYPE_DRIRC)
            put("index", index)
            put("path", file.absolutePath)
            put("executable", lever.executable ?: JSONObject.NULL)
            put("existed", existed)
            put("backup", if (existed) backup.absolutePath else JSONObject.NULL)
            put("sha_before", if (existed) OptimizeFiles.sha256(bytes) else JSONObject.NULL)
            put("sha_after", OptimizeFiles.sha256(written))
            put(
                "keys",
                JSONArray().apply {
                    merged.priors.forEach { (name, prior) -> put(JSONObject().put("name", name).put("prior", prior ?: JSONObject.NULL)) }
                },
            )
        }
    }

    /** Null when a relative rung skipped every value of the lever and nothing was written. */
    private suspend fun applyRegistry(index: Int, lever: Lever.Registry, container: Container, relative: Boolean, count: OpCount): JSONObject? {
        if (!lever.hive.equals("HKCU", ignoreCase = true)) throw LeverFailure("hive ${lever.hive} not allowed")
        if (!lever.key.startsWith("Software\\", ignoreCase = true)) throw LeverFailure("key outside Software")
        val hive = OptimizeFiles.userHive(container)
        if (!hive.isFile) throw LeverFailure("user.reg missing")
        val values = if (!relative) {
            lever.values
        } else {
            val now = WineRegistryText.read(OptimizeFiles.readHive(hive), lever.key, lever.values.map { it.name })
            lever.values.filter { v ->
                v.delete || OptimizeRelative.shouldApply(
                    now[v.name]?.let { OptimizeFiles.decodeRegistryValue(v.type!!, it) }, v.data!!, v.ladder, v.lowerIs,
                )
            }
        }
        count.applied += values.size
        count.skipped += lever.values.size - values.size
        if (values.isEmpty()) return null
        val changes = values.map { v ->
            WineRegistryText.Change(v.name, if (v.delete) null else OptimizeFiles.encodeRegistryValue(v.type!!, v.data!!))
        }
        val merged = WineRegistryText.merge(OptimizeFiles.readHive(hive), lever.key, changes, System.currentTimeMillis())
        val record = JSONObject().apply {
            put("type", Lever.TYPE_REGISTRY)
            put("index", index)
            put("hive", "HKCU")
            put("key", lever.key)
            put("section_existed", merged.sectionExisted)
            put(
                "values",
                JSONArray().apply {
                    merged.priors.forEachIndexed { i, prior ->
                        put(
                            JSONObject().apply {
                                put("name", prior.name)
                                put("prior", prior.lines?.let { JSONArray(it) } ?: JSONObject.NULL)
                                put("applied", changes[i].raw?.let { WineRegistryText.canonical(it) } ?: JSONObject.NULL)
                                put("raw", changes[i].raw ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
        }
        OptimizeFiles.writeHive(hive, merged.text)
        val check = WineRegistryText.read(OptimizeFiles.readHive(hive), lever.key, changes.map { it.name })
        changes.forEach { change ->
            val now = check[change.name]
            val ok = if (change.raw == null) {
                now == null
            } else {
                now != null && WineRegistryText.canonical(now) == WineRegistryText.canonical(change.raw)
            }
            if (!ok) {
                OptimizeReverter.revertLevers(null, container, listOf(record))
                throw LeverFailure("registry value ${change.name} did not take")
            }
        }
        return record
    }

    const val DXVK_CONFIG_KEY = "envVars.DXVK_CONFIG"
    private val DXVK_VALUE = Regex("^[^;\"\\[\\x00-\\x1f\\x7f]{1,128}$")

    /**
     * A dxwrapperConfig sub-key named like a DXVK option ("dxvk.numCompilerThreads"). DXVKHelper only turns its own
     * keys into DXVK settings, so these go into the container's DXVK_CONFIG instead, which DXVK reads last.
     */
    internal fun isDxvkOption(key: String): Boolean = key.startsWith("dxwrapperConfig.") && key.substringAfter('.').contains('.')

    private fun dxvkKey(part: String): String? = part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it).trim() }

    /** A DXVK_CONFIG value's entries, and where its first per-exe section starts. */
    private fun dxvkGlobalParts(config: String): Pair<MutableList<String>, Int> {
        val parts = config.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val firstSection = parts.indexOfFirst { it.startsWith("[") }.takeIf { it >= 0 } ?: parts.size
        return parts to firstSection
    }

    internal fun dxvkConfigGet(config: String, name: String): String? {
        val (parts, globalEnd) = dxvkGlobalParts(config)
        val part = parts.subList(0, globalEnd).lastOrNull { dxvkKey(it) == name } ?: return null
        return part.substringAfter('=').trim().removeSurrounding("\"")
    }

    /** Sets or, for a null [value], removes [name] in a DXVK_CONFIG value, keeping every other entry. */
    internal fun dxvkConfigPut(config: String, name: String, value: String?): String {
        val (parts, globalEnd) = dxvkGlobalParts(config)
        val at = parts.subList(0, globalEnd).indexOfFirst { dxvkKey(it) == name }
        val entry = value?.let { "$name = ${if (it.any(Char::isWhitespace)) "\"$it\"" else it}" }
        when {
            entry == null -> if (at >= 0) parts.removeAt(at)
            at >= 0 -> parts[at] = entry
            else -> parts.add(globalEnd, entry)
        }
        return parts.joinToString(";")
    }

    internal fun liveValue(container: Container, live: ContainerData, key: String): String? =
        if (key == "screenSize") container.screenSize else configValue(live, key)

    internal fun configValue(live: ContainerData, key: String): String? = when {
        isDxvkOption(key) -> configValue(live, DXVK_CONFIG_KEY)?.let { dxvkConfigGet(it, key.substringAfter('.')) }
        key.startsWith("envVars.") -> {
            val env = EnvVars(live.envVars)
            val name = key.substringAfter('.')
            if (env.has(name)) env.get(name) else null
        }
        key.contains('.') -> KeyValueSet(SuggestionConfigKeys.configValueOf(live, key.substringBefore('.')).orEmpty())
            .firstOrNull { it[0] == key.substringAfter('.') }?.get(1)
        else -> SuggestionConfigKeys.configValueOf(live, key)
    }

    internal fun editFor(key: String, value: String?): SupportSuggestionApplier.Edit? = when {
        key.startsWith("envVars.") -> SupportSuggestionApplier.Edit(SupportSuggestion.ENV_KEY, key.substringAfter('.'), value)
        key.contains('.') -> SupportSuggestionApplier.Edit(key, null, value)
        value == null -> null
        else -> SupportSuggestionApplier.Edit(key, null, value)
    }

    internal fun isAllowedContainerKey(key: String): Boolean = when {
        key == "fpsLimit" || key == "screenSize" -> true
        key.startsWith("envVars.") -> key.length > "envVars.".length
        key.contains('.') -> key.substringBefore('.') in SupportSuggestion.KV_PARENTS
        else -> key in SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS && key != SupportSuggestion.ENV_KEY
    }

    internal fun applyFps(container: Container, value: String) {
        val target = parsePositiveFpsLimit(value.trim())
        if (target == null) {
            if (value.trim() != "0") throw LeverFailure("bad fps limit $value")
            container.putExtra(OptimizeFiles.FPS_LIMITER_ENABLED_EXTRA, false)
        } else {
            container.putExtra(OptimizeFiles.FPS_LIMITER_ENABLED_EXTRA, true)
            container.putExtra(OptimizeFiles.FPS_LIMITER_TARGET_EXTRA, target)
        }
    }

    private suspend fun applyContainer(
        index: Int,
        lever: Lever.ContainerSet,
        context: Context,
        container: Container,
        onProgress: (Float, String) -> Unit,
    ): JSONObject {
        lever.set.firstOrNull { !isAllowedContainerKey(it.first) }?.let { throw LeverFailure("container key ${it.first} not allowed") }
        lever.set.firstOrNull { it.first == "screenSize" && !SCREEN_SIZE.matches(it.second) }?.let { throw LeverFailure("bad screen size") }
        val dxvk = lever.set.filter { isDxvkOption(it.first) }
        if (dxvk.isNotEmpty() && lever.set.any { it.first == DXVK_CONFIG_KEY }) throw LeverFailure("DXVK_CONFIG set twice")
        dxvk.firstOrNull { !DXVK_VALUE.matches(it.second) }?.let { throw LeverFailure("bad DXVK value for ${it.first}") }
        val live = ContainerUtils.toContainerData(container)
        val before = JSONArray()
        val edits = mutableListOf<SupportSuggestionApplier.Edit>()
        for ((key, value) in lever.set) {
            if (isDxvkOption(key)) continue
            if (key == "fpsLimit") {
                before.put(
                    JSONObject().apply {
                        put("key", key)
                        put("enabled", container.getExtra(OptimizeFiles.FPS_LIMITER_ENABLED_EXTRA))
                        put("target", container.getExtra(OptimizeFiles.FPS_LIMITER_TARGET_EXTRA))
                    },
                )
                continue
            }
            before.put(
                JSONObject().apply {
                    put("key", key)
                    put("value", liveValue(container, live, key) ?: JSONObject.NULL)
                },
            )
            if (key != "screenSize") editFor(key, value)?.let { edits += it }
        }
        if (dxvk.isNotEmpty()) {
            val prior = configValue(live, DXVK_CONFIG_KEY)
            before.put(JSONObject().put("key", DXVK_CONFIG_KEY).put("value", prior ?: JSONObject.NULL))
            val merged = dxvk.fold(prior.orEmpty()) { config, (key, value) -> dxvkConfigPut(config, key.substringAfter('.'), value) }
            edits += SupportSuggestionApplier.Edit(SupportSuggestion.ENV_KEY, "DXVK_CONFIG", merged)
        }
        val record = JSONObject().apply {
            put("type", Lever.TYPE_CONTAINER)
            put("index", index)
            put("before", before)
        }
        try {
            if (edits.isNotEmpty()) {
                val result = SupportSuggestionApplier.applyEdits(
                    context = context,
                    container = container,
                    live = live,
                    edits = edits,
                    onProgress = onProgress,
                    beforeSave = {},
                    source = SOURCE,
                )
                if (result != SupportSuggestionApplier.Result.Done) throw LeverFailure("container edit failed: $result")
            }
            var direct = false
            lever.set.forEach { (key, value) ->
                when (key) {
                    "fpsLimit" -> { applyFps(container, value); direct = true }
                    "screenSize" -> { container.screenSize = value; direct = true }
                }
            }
            if (direct) container.saveData()
            val now = ContainerUtils.toContainerData(container)
            lever.set.forEach { (key, value) ->
                if (key == "fpsLimit") return@forEach
                if (!liveValue(container, now, key).equals(value, ignoreCase = true)) throw LeverFailure("$key did not take")
            }
        } catch (e: Exception) {
            OptimizeReverter.revertLevers(context, container, listOf(record))
            throw e
        }
        return record
    }
}
