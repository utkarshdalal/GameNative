package app.gamenative.filedetect

import android.content.Context
import app.gamenative.mods.FomodEnvironmentSnapshotBuilder
import app.gamenative.mods.NativeBinaryArchitecture
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import kotlin.streams.asSequence
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object GameFileDetection {

    const val METADATA_KEY = "file_detection"
    private const val RESULT_FILE = "file_detection.json"
    private const val MAX_DEPTH = 12
    private const val MAX_FILES = 200_000

    data class DetectionResult(
        val detection: Detection,
        val bitness: Int?,
        val fileCount: Int,
    )

    fun ensure(context: Context, container: Container) {
        try {
            FileDetectionRules.refreshIfStale(context)
            val rulesVersion = FileDetectionRules.rulesVersion(context)
            val existing = container.getSessionMetadata(METADATA_KEY)
            if (existing.isNotEmpty() && runCatching { JSONObject(existing).optString("rv") }.getOrNull() == rulesVersion) return

            val stored = container.rootDir?.let { File(it, RESULT_FILE) }
            val cached = stored?.takeIf { it.exists() }?.readText()
            val json = if (cached != null && runCatching { JSONObject(cached).optString("rv") }.getOrNull() == rulesVersion) {
                cached
            } else {
                val root = ContainerUtils.getADrivePath(container.drives)?.let { File(it) } ?: return
                if (!root.isDirectory) return
                val exe = container.executablePath.takeIf { it.isNotEmpty() && !ContainerUtils.isAbsoluteWindowsPath(it) }
                    ?.let { File(root, it.replace('\\', File.separatorChar)) }
                val started = System.currentTimeMillis()
                val result = detectDirectory(root, exe, FileDetectionRules.ruleSet(context))
                Timber.tag("FileDetect").i(
                    "Scanned ${result.fileCount} files in ${System.currentTimeMillis() - started} ms: ${result.detection}",
                )
                toJson(result, rulesVersion).toString().also { text -> stored?.let { runCatching { it.writeText(text) } } }
            }
            container.putSessionMetadata(METADATA_KEY, JSONObject(json))
            container.saveData()
        } catch (e: Exception) {
            Timber.tag("FileDetect").w(e, "File detection failed")
        }
    }

    fun detectDirectory(root: File, exe: File?, ruleSet: RuleSet): DetectionResult {
        val rootPath = root.toPath()
        val paths = ArrayList<String>()
        Files.walk(rootPath, MAX_DEPTH).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .take(MAX_FILES)
                .forEach { paths.add(relativePath(rootPath, it)) }
        }
        val detection = FileDetector(ruleSet).detect(paths)
        val bitness = exe?.takeIf { it.isFile }?.let { exeBitness(it) }
        return DetectionResult(detection, bitness, paths.size)
    }

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }

    fun exeBitness(exe: File): Int? = when (FomodEnvironmentSnapshotBuilder.readPeArchitecture(exe)) {
        NativeBinaryArchitecture.X86 -> 32
        NativeBinaryArchitecture.X64, NativeBinaryArchitecture.ARM64 -> 64
        NativeBinaryArchitecture.UNKNOWN -> null
    }

    fun toJson(result: DetectionResult, rulesVersion: String): JSONObject = JSONObject().apply {
        put("rv", rulesVersion)
        put("at", System.currentTimeMillis() / 1000)
        put("engines", JSONArray(result.detection.engines))
        put("anticheat", JSONArray(result.detection.antiCheat))
        put("sdks", JSONArray(result.detection.sdks))
        put("launchers", JSONArray(result.detection.launchers))
        put("emulators", JSONArray(result.detection.emulators))
        result.bitness?.let { put("bitness", it) }
    }

    fun properties(container: Container): Map<String, Any> =
        propertiesFromJson(container.getSessionMetadata(METADATA_KEY))

    fun propertiesFromJson(json: String): Map<String, Any> = buildMap {
        if (json.isEmpty()) return@buildMap
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@buildMap
        obj.optJSONArray("engines")?.takeIf { it.length() > 0 }?.optString(0)?.takeIf { it.isNotEmpty() }?.let {
            put("engine", it.substringAfter('.').lowercase(Locale.ROOT))
        }
        strings(obj, "anticheat")?.let { put("detected_anticheat", it) }
        strings(obj, "sdks")?.let { put("detected_sdks", it) }
        strings(obj, "launchers")?.let { put("detected_launchers", it) }
        strings(obj, "emulators")?.let { put("detected_emulators", it) }
        if (obj.has("bitness")) put("exe_bitness", obj.getInt("bitness"))
        obj.optString("rv").takeIf { it.isNotEmpty() }?.let { put("file_detection_rules", it) }
    }

    private fun strings(obj: JSONObject, key: String): List<String>? {
        val array = obj.optJSONArray(key) ?: return null
        if (array.length() == 0) return null
        return List(array.length()) { array.getString(it) }
    }
}
