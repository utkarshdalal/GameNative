package app.gamenative.service.rockstar

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class RockstarCloudPreference { NONE, LOCAL, REMOTE }

data class RockstarLocalState(val md5: String, val size: Long)

data class RockstarRemoteState(val version: Long, val md5: String?)

data class RockstarSyncedState(val version: Long, val md5: String, val size: Long, val serverModified: String?)

enum class RockstarCloudAction { NONE, DOWNLOAD, UPLOAD, CONFLICT }

data class RockstarCloudPullPlan(
    val download: Set<String>,
    val upload: Set<String>,
    val conflicts: Set<String>,
    val forcedLocal: Set<String>,
) {
    val conflict: Boolean get() = conflicts.isNotEmpty()
}

/** Pure per-file planner for Rockstar cloud saves: local md5, server version, last-synced state. */
object RockstarCloudSyncPlanner {
    private const val ISO_SECONDS = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    fun decide(local: RockstarLocalState?, remote: RockstarRemoteState?, synced: RockstarSyncedState?): RockstarCloudAction {
        if (local == null && remote == null) return RockstarCloudAction.NONE
        if (local == null) return RockstarCloudAction.DOWNLOAD
        if (remote == null) return RockstarCloudAction.UPLOAD
        if (remote.md5 != null && remote.md5.equals(local.md5, ignoreCase = true)) return RockstarCloudAction.NONE
        if (synced == null) return RockstarCloudAction.CONFLICT
        val localChanged = !local.md5.equals(synced.md5, ignoreCase = true) || local.size != synced.size
        val remoteChanged = remote.version != synced.version
        return when {
            localChanged && remoteChanged -> RockstarCloudAction.CONFLICT
            remoteChanged -> RockstarCloudAction.DOWNLOAD
            localChanged -> RockstarCloudAction.UPLOAD
            else -> RockstarCloudAction.NONE
        }
    }

    fun planPull(
        names: Collection<String>,
        local: Map<String, RockstarLocalState>,
        remote: Map<String, RockstarRemoteState>,
        synced: Map<String, RockstarSyncedState>,
        preference: RockstarCloudPreference,
    ): RockstarCloudPullPlan {
        val download = LinkedHashSet<String>()
        val upload = LinkedHashSet<String>()
        val conflicts = LinkedHashSet<String>()
        val forcedLocal = LinkedHashSet<String>()
        for (name in names) {
            when (decide(local[name], remote[name], synced[name])) {
                RockstarCloudAction.NONE -> Unit
                RockstarCloudAction.DOWNLOAD -> download += name
                RockstarCloudAction.UPLOAD -> upload += name
                RockstarCloudAction.CONFLICT -> when (preference) {
                    RockstarCloudPreference.NONE -> conflicts += name
                    RockstarCloudPreference.REMOTE -> download += name
                    RockstarCloudPreference.LOCAL -> {
                        upload += name
                        forcedLocal += name
                    }
                }
            }
        }
        return RockstarCloudPullPlan(download, upload, conflicts, forcedLocal)
    }

    fun planPush(names: Collection<String>, local: Map<String, RockstarLocalState>, synced: Map<String, RockstarSyncedState>): Set<String> =
        names.filterTo(LinkedHashSet()) { name ->
            val mine = local[name] ?: return@filterTo false
            val known = synced[name]
            known == null || !known.md5.equals(mine.md5, ignoreCase = true) || known.size != mine.size
        }

    fun parseTimestamp(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.all { it.isDigit() }) {
            val n = text.toLongOrNull() ?: return null
            return if (text.length <= 10) n * 1000 else n
        }
        val iso = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.(\d+))?(Z|[+-]\d{2}:?\d{2})?""").matchEntire(text)
        if (iso != null) {
            val base = utc("yyyy-MM-dd'T'HH:mm:ss").parseStrict(iso.groupValues[1]) ?: return null
            val millis = iso.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val zone = iso.groupValues[4]
            val offset = if (zone.isEmpty() || zone == "Z") {
                0L
            } else {
                val digits = zone.drop(1).replace(":", "")
                val minutes = digits.take(2).toLong() * 60 + digits.drop(2).toLong()
                (if (zone[0] == '-') -minutes else minutes) * 60_000
            }
            return base + millis - offset
        }
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "M/d/yyyy h:mm:ss a", "yyyy-MM-dd HH:mm:ss")) {
            utc(pattern).parseStrict(text)?.let { return it }
        }
        return null
    }

    fun formatTimestamp(sample: String?, millis: Long): String {
        val text = sample?.trim().orEmpty()
        if (text.isNotEmpty() && text.all { it.isDigit() }) return if (text.length <= 10) (millis / 1000).toString() else millis.toString()
        val iso = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.(\d+))?(Z?)""").matchEntire(text)
        if (iso != null) {
            val fraction = iso.groupValues[2].length
            val base = utc("yyyy-MM-dd'T'HH:mm:ss").format(java.util.Date(millis))
            val frac = if (fraction > 0) "." + (millis % 1000).toString().padStart(3, '0').padEnd(fraction, '0').take(fraction) else ""
            return base + frac + iso.groupValues[3]
        }
        if (Regex("""[A-Za-z]{3}, \d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT""").matches(text)) {
            return utc("EEE, dd MMM yyyy HH:mm:ss 'GMT'").format(java.util.Date(millis))
        }
        if (Regex("""\d{1,2}/\d{1,2}/\d{4} \d{1,2}:\d{2}:\d{2} [AP]M""").matches(text)) {
            return utc("M/d/yyyy h:mm:ss a").format(java.util.Date(millis))
        }
        if (Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(text)) {
            return utc("yyyy-MM-dd HH:mm:ss").format(java.util.Date(millis))
        }
        return utc(ISO_SECONDS).format(java.util.Date(millis))
    }

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    private fun SimpleDateFormat.parseStrict(text: String): Long? {
        val pos = ParsePosition(0)
        val date = parse(text, pos) ?: return null
        return if (pos.index == text.length) date.time else null
    }
}
