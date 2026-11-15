package app.gamenative.utils

import java.util.Locale

/**
 * Picks the game's hot thread from per-thread tick deltas the sampler already read: a name filter, no extra reads.
 * Android's own UI threads (RenderThread, hwuiTask, GLThread) count only inside the game's process, so a game
 * that names its render thread that way keeps it while the app's hwui threads never stand in for the game. Wine,
 * wineserver and dxvk helper threads never count. Threads rank by user + system time, so kernel-heavy spinners
 * stay visible; the game's main thread (tid == pid) wins when its time is within 10 % of the busiest thread's.
 */
internal object HotThreadFilter {
    const val MAIN_PREFERENCE_SHARE = 0.9

    class Candidate(val pid: Int, val tid: Int, val name: String, val totalTicks: Long)

    private val androidUiPrefixes = listOf("renderthread", "hwuitask", "glthread")
    private val helperPrefixes = listOf("wineserver", "wine", "dxvk-", "audiotrack", "aaudio", "binder")

    fun excluded(c: Candidate, gamePid: Int): Boolean {
        val lower = c.name.lowercase(Locale.US)
        if (androidUiPrefixes.any { lower.startsWith(it) }) return c.pid != gamePid
        if (c.pid == gamePid && c.tid == gamePid) return false
        return lower == "main" || helperPrefixes.any { lower.startsWith(it) }
    }

    fun pick(candidates: Collection<Candidate>, gamePid: Int): Candidate? {
        val eligible = candidates.filter { it.pid == gamePid && !excluded(it, gamePid) }
        val top = eligible.maxByOrNull { it.totalTicks } ?: return null
        val main = eligible.firstOrNull { it.tid == gamePid } ?: return top
        if (main === top) return main
        return if (main.totalTicks >= MAIN_PREFERENCE_SHARE * top.totalTicks) main else top
    }
}
