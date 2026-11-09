package app.gamenative.optimize

import android.content.Context
import app.gamenative.ui.screen.support.SupportSuggestionApplier
import app.gamenative.ui.screen.support.WineRegistryText
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/** Takes applied levers off again from the records [OptimizeApplier] kept. */
object OptimizeReverter {

    const val SOURCE = "optimize_restored"

    /** Reverts [records] newest first; true when every one came off. */
    suspend fun revertLevers(context: Context?, container: Container, records: List<JSONObject>): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            var ok = true
            for (record in records.asReversed()) {
                try {
                    when (record.optString("type")) {
                        Lever.TYPE_ENGINE_FILE -> revertFile(record)
                        Lever.TYPE_DRIRC -> revertDrirc(record)
                        Lever.TYPE_REGISTRY -> revertRegistry(container, record)
                        Lever.TYPE_CONTAINER -> {
                            if (context == null) throw IllegalStateException("no context for a container revert")
                            revertContainer(context, container, record)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ok = false
                    Timber.tag("Optimize").e(e, "Reverting a ${record.optString("type")} lever failed")
                }
            }
            ok
        }

    /** Takes an engine_file lever off; a file Optimize made read-only is writable again unless it was read-only before. */
    internal fun revertFile(record: JSONObject) {
        val file = File(record.getString("path"))
        if (file.isFile && !file.canWrite()) file.setWritable(true)
        try {
            restoreFile(record, file)
        } finally {
            if (file.isFile && !record.optBoolean("was_writable", true)) file.setWritable(false, false)
        }
    }

    private fun restoreFile(record: JSONObject, file: File) {
        val existed = record.optBoolean("existed", true)
        val backup = record.optStringOrNull("backup")?.let { File(it) }?.takeIf { it.isFile }
        if (!file.exists()) {
            if (existed && backup != null) OptimizeFiles.atomicWrite(file, backup.readBytes())
            return
        }
        val current = file.readBytes()
        if (OptimizeFiles.sha256(current) == record.optString("sha_after")) {
            if (!existed) {
                file.delete()
                return
            }
            if (backup != null) {
                OptimizeFiles.atomicWrite(file, backup.readBytes())
                return
            }
        }
        val format = ConfigFormats.forName(record.optString("format")) ?: throw IllegalStateException("unknown format")
        val codec = TextCodecs.codecFor(current, record.optString("encoding", "default"))
        val ops = record.optJSONArray("keys").objects().map { key ->
            val prior = key.optStringOrNull("prior") ?: if (key.has("prior") && !key.isNull("prior")) "" else null
            FileOp(if (prior != null) "set" else "delete", key.optString("section"), key.getString("key"), prior)
        }
        val text = TextCodecs.decode(current, codec)
        OptimizeFiles.atomicWrite(file, TextCodecs.encode(format.apply(text, ops), codec))
    }

    private fun revertDrirc(record: JSONObject) {
        val file = File(record.getString("path"))
        val existed = record.optBoolean("existed", true)
        val backup = record.optStringOrNull("backup")?.let { File(it) }?.takeIf { it.isFile }
        if (!file.exists()) {
            if (existed && backup != null) OptimizeFiles.atomicWrite(file, backup.readBytes())
            return
        }
        val current = file.readBytes()
        if (OptimizeFiles.sha256(current) == record.optString("sha_after")) {
            if (!existed) {
                file.delete()
                return
            }
            if (backup != null) {
                OptimizeFiles.atomicWrite(file, backup.readBytes())
                return
            }
        }
        val priors = record.optJSONArray("keys").objects().map { key ->
            key.getString("name") to (if (key.isNull("prior")) null else key.optString("prior"))
        }
        val text = Driconf.merge(current.toString(Charsets.UTF_8), record.optStringOrNull("executable"), priors).text
        if (!existed && Driconf.applicationCount(text) == 0) {
            file.delete()
        } else {
            OptimizeFiles.atomicWrite(file, text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun revertRegistry(container: Container, record: JSONObject) {
        val hive = OptimizeFiles.userHive(container)
        if (!hive.isFile) return
        val priors = record.optJSONArray("values").objects().map { entry ->
            val prior = entry.optJSONArray("prior")
            WineRegistryText.Prior(entry.getString("name"), prior?.let { array -> (0 until array.length()).map { array.getString(it) } })
        }
        val text = WineRegistryText.restore(
            OptimizeFiles.readHive(hive),
            record.getString("key"),
            record.optBoolean("section_existed", true),
            priors,
            System.currentTimeMillis(),
        )
        OptimizeFiles.writeHive(hive, text)
    }

    private suspend fun revertContainer(context: Context, container: Container, record: JSONObject) {
        val entries = record.optJSONArray("before").objects()
        val edits = entries.mapNotNull { entry ->
            val key = entry.optString("key")
            if (key == "fpsLimit" || key == "screenSize" || key in OptimizeApplier.EXTRA_KEYS) return@mapNotNull null
            val before = if (entry.isNull("value")) null else entry.optString("value")
            OptimizeApplier.editFor(key, before)
        }
        if (edits.isNotEmpty()) {
            val result = SupportSuggestionApplier.applyEdits(
                context = context,
                container = container,
                live = ContainerUtils.toContainerData(container),
                edits = edits,
                onProgress = { _, _ -> },
                beforeSave = {},
                source = SOURCE,
            )
            if (result != SupportSuggestionApplier.Result.Done) throw IllegalStateException("container restore failed: $result")
        }
        var direct = false
        entries.forEach { entry ->
            when (entry.optString("key")) {
                "fpsLimit" -> {
                    container.putExtra(OptimizeFiles.FPS_LIMITER_ENABLED_EXTRA, entry.optString("enabled").ifEmpty { null })
                    container.putExtra(OptimizeFiles.FPS_LIMITER_TARGET_EXTRA, entry.optString("target").ifEmpty { null })
                    direct = true
                }
                "screenSize" -> {
                    entry.optStringOrNull("value")?.let { container.screenSize = it }
                    direct = true
                }
                in OptimizeApplier.EXTRA_KEYS -> {
                    container.putExtra(entry.optString("key"), entry.optStringOrNull("value"))
                    direct = true
                }
            }
        }
        if (direct) container.saveData()
    }
}
