package app.gamenative.powercontrol.loop

import android.os.Process
import timber.log.Timber

/**
 * Per-thread affinity/priority control for guest (wine) threads.
 *
 * ---------------------------------------------------------------------------
 * PATH DECISION: Linux-side fallback (NOT the WinHandler native path).
 * ---------------------------------------------------------------------------
 * We want per-THREAD placement/boost keyed by guest thread identity, mirroring
 * the existing per-PROCESS affinity path (Win32AppWorkarounds.setProcessAffinity
 * -> WinHandler.setProcessAffinity -> RequestCodes.SET_PROCESS_AFFINITY over UDP).
 *
 * The WinHandler wire protocol cannot carry a thread-level op:
 *   - com.winlator.winhandler.RequestCodes enumerates opcodes 0..13
 *     (EXIT, INIT, EXEC, KILL_PROCESS, LIST_PROCESSES, GET_PROCESS,
 *      SET_PROCESS_AFFINITY=6, MOUSE_EVENT, GET_GAMEPAD, GET_GAMEPAD_STATE,
 *      RELEASE_GAMEPAD, KEYBOARD_EVENT, BRING_TO_FRONT, CURSOR_POS_FEEDBACK).
 *     SET_PROCESS_AFFINITY is the only affinity op; there is NO
 *     SET_THREAD_AFFINITY / SET_THREAD_PRIORITY.
 *   - The Windows-side counterpart that consumes these packets ships as a
 *     prebuilt binary (winhandler.exe in the imagefs); its source is not in
 *     this repo. A new opcode cannot be invented because the guest handler
 *     would silently drop any unknown request code.
 *
 * Guest wine threads run as host Linux tasks under the app's own uid and are
 * visible under /proc/<pid>/task, so we control them directly on the Linux side:
 *   - affinity -> taskset(1) exec, the same mechanism ThreadPlacer already uses
 *                 (android.system.Os exposes no sched_setaffinity binding).
 *   - priority -> android.os.Process.setThreadPriority(tid, nice), a thin
 *                 wrapper over setpriority(2) for same-uid tids; renice(1) exec
 *                 as a fallback.
 *
 * Infrastructure only. Nothing here is wired into any Policy yet.
 */
object ThreadControl {

    private const val TAG = "ThreadControl"
    private const val RENICE = "/system/bin/renice"

    private const val NICE_MIN = -20
    private const val NICE_MAX = 19

    /** Pin a thread to a contiguous CPU range, e.g. placeThread(tid, 4..7). */
    fun placeThread(tid: Int, cpus: IntRange): Boolean {
        return placeThread(tid, ThreadPlacer.buildMask(cpus.toList()))
    }

    /** Pin a thread to an explicit CPU bitmask (bit i = cpu i). */
    fun placeThread(tid: Int, mask: Long): Boolean {
        return ThreadPlacer.setAffinity(tid, mask)
    }

    /**
     * Lower (or raise, with a negative delta) a thread's scheduling priority by
     * niceDelta nice points. Positive delta = nicer = deprioritized. Used to
     * demote non-hot guest threads so the render/JIT threads win the CPU.
     */
    fun demoteThread(tid: Int, niceDelta: Int): Boolean {
        val current = try {
            Process.getThreadPriority(tid)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getThreadPriority failed for tid $tid")
            return reniceExec(tid, niceDelta.coerceIn(NICE_MIN, NICE_MAX))
        }
        val target = (current + niceDelta).coerceIn(NICE_MIN, NICE_MAX)
        return try {
            Process.setThreadPriority(tid, target)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "setThreadPriority(tid=$tid, nice=$target) failed, trying renice")
            reniceExec(tid, target)
        }
    }

    /** Threads of pid whose comm matches commRegex. */
    fun findThreads(pid: Int, commRegex: Regex): List<ThreadInfo> {
        return ThreadPlacer.listTasks(pid).filter { commRegex.containsMatchIn(it.comm) }
    }

    /** Convenience overload compiling a pattern string. */
    fun findThreads(pid: Int, commRegex: String): List<ThreadInfo> {
        return findThreads(pid, Regex(commRegex))
    }

    /**
     * Rank threads by CPU busy delta between two ThreadPlacer.listTasks
     * snapshots (hottest first). Threads absent from the earlier snapshot are
     * ranked by their absolute jiffies.
     */
    fun rankByCpuDelta(before: List<ThreadInfo>, after: List<ThreadInfo>): List<Pair<ThreadInfo, Long>> {
        val beforeByTid = before.associateBy { it.tid }
        return after.map { t ->
            val prev = beforeByTid[t.tid]
            val delta = if (prev != null) t.cpuJiffies - prev.cpuJiffies else t.cpuJiffies
            t to delta
        }.sortedByDescending { it.second }
    }

    private fun reniceExec(tid: Int, targetNice: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf(RENICE, "-n", targetNice.toString(), "-p", tid.toString())
            )
            val exit = process.waitFor()
            if (exit != 0) {
                Timber.tag(TAG).w("renice failed for tid $tid nice=$targetNice exit=$exit")
            }
            exit == 0
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to run renice for tid $tid")
            false
        }
    }
}
