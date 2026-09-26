package app.gamenative.html5.savesync

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

// NW.js (nw.dll layout) keeps web storage under chrome-extension://<id>, the id derived from the
// manifest `name` -- so the pack default file:// never matches the PC profile.
object NwjsAppOrigin {

    private const val TAG = "NwjsAppOrigin"

    // null when the install isn't an NW.js build or its manifest name can't be read.
    fun fromInstall(installDir: File): String? {
        if (!File(installDir, "nw.dll").isFile) return null
        val name = manifestName(installDir) ?: return null
        return OriginCodec.nwjsAppOrigin(name)
    }

    // package.json beside the exe, else package.nw (zip or unpacked dir).
    internal fun manifestName(installDir: File): String? {
        val text = runCatching {
            val loose = File(installDir, "package.json")
            val packaged = File(installDir, "package.nw")
            when {
                loose.isFile -> loose.readText()
                packaged.isDirectory -> File(packaged, "package.json").takeIf { it.isFile }?.readText()
                packaged.isFile -> ZipFile(packaged).use { zip ->
                    zip.getEntry("package.json")?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                }
                else -> null
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "manifest read failed in %s", installDir.absolutePath)
        }.getOrNull() ?: return null
        return runCatching { Json.parseToJsonElement(text).jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
