package app.gamenative.service.xbox

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One installed Xbox package: where its decrypted files live and which package build they came from. */
data class XboxInstallRecord(
    val storeId: String,
    val title: String,
    val contentId: String,
    val packageFamilyName: String?,
    val version: String?,
    val installPath: String,
    val sizeBytes: Long,
    val executableOverride: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
)

/** Installed packages, one JSON file in the app's private storage. */
class XboxInstallRegistry(private val file: File) {
    @Synchronized
    fun all(): List<XboxInstallRecord> = read().let { array ->
        (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
    }

    fun get(storeId: String): XboxInstallRecord? = all().firstOrNull { it.storeId.equals(storeId, true) }

    @Synchronized
    fun put(record: XboxInstallRecord) = write(all().filterNot { it.storeId.equals(record.storeId, true) } + record)

    @Synchronized
    fun remove(storeId: String) = write(all().filterNot { it.storeId.equals(storeId, true) })

    private fun read(): JSONArray = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }

    private fun write(records: List<XboxInstallRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(records.map(::encode)).toString())
        check(tmp.renameTo(file)) { "Cannot write the Xbox install list" }
    }

    private fun encode(r: XboxInstallRecord) = JSONObject().apply {
        put("storeId", r.storeId)
        put("title", r.title)
        put("contentId", r.contentId)
        r.packageFamilyName?.let { put("packageFamilyName", it) }
        r.version?.let { put("version", it) }
        put("installPath", r.installPath)
        put("sizeBytes", r.sizeBytes)
        r.executableOverride?.let { put("executableOverride", it) }
        put("installedAt", r.installedAt)
    }

    private fun decode(o: JSONObject) = XboxInstallRecord(
        storeId = o.getString("storeId"),
        title = o.optString("title"),
        contentId = o.getString("contentId"),
        packageFamilyName = o.optString("packageFamilyName").ifEmpty { null },
        version = o.optString("version").ifEmpty { null },
        installPath = o.getString("installPath"),
        sizeBytes = o.optLong("sizeBytes"),
        executableOverride = o.optString("executableOverride").ifEmpty { null },
        installedAt = o.optLong("installedAt"),
    )
}
