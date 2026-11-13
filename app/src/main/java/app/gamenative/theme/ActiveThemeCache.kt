package app.gamenative.theme

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The merged spec of the enabled themes as last translated, kept in one file so an app start applies it without
 * reading or translating any theme. Pure JVM.
 */
object ActiveThemeCache {

    data class Saved(val spec: ThemeSpec, val enabledCount: Int)

    const val FORMAT = 1
    private const val MAX_BYTES = 1024L * 1024

    /** [spec] as [decode] gives it back, so a spec published before a restart equals the one published after. */
    fun normalize(spec: ThemeSpec): ThemeSpec = ThemeSpecJson.decode(ThemeSpecJson.encode(spec, files = true), files = true) ?: ThemeSpec.EMPTY

    fun encode(saved: Saved, key: String): String = JsonObject(
        linkedMapOf(
            "format" to JsonPrimitive(FORMAT),
            "key" to JsonPrimitive(key),
            "enabled" to JsonPrimitive(saved.enabledCount),
            "spec" to ThemeSpecJson.encode(saved.spec, files = true),
        ),
    ).toString()

    /** The saved spec, or null when [text] is unreadable, of another format or [key], or names a file that is gone. */
    fun decode(text: String, key: String): Saved? {
        val o = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (o.int("format") != FORMAT) return null
        if ((o["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content != key) return null
        val enabled = o.int("enabled")?.takeIf { it >= 0 } ?: return null
        val spec = ThemeSpecJson.decode(o["spec"] as? JsonObject ?: return null, files = true) ?: ThemeSpec.EMPTY
        if (spec.fontFile?.let { !File(it).isFile } == true) return null
        if (spec.backgroundImage?.let { !File(it).isFile } == true) return null
        return Saved(spec, enabled)
    }

    fun read(file: File, key: String): Saved? = try {
        if (file.isFile && file.length() <= MAX_BYTES) decode(file.readText(), key) else null
    } catch (e: IOException) {
        null
    }

    fun write(file: File, saved: Saved, key: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encode(saved, key))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot replace ${file.name}")
        }
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
