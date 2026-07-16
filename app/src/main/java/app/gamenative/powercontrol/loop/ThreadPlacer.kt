package app.gamenative.powercontrol.loop

import timber.log.Timber
import java.io.File

data class ThreadInfo(val tid: Int, val comm: String, val cpuJiffies: Long = 0L)

object ThreadPlacer {

    private const val TAG = "ThreadPlacer"
    private const val TASKSET = "/system/bin/taskset"

    fun listTasks(pid: Int): List<ThreadInfo> {
        return try {
            val taskDir = File("/proc/$pid/task")
            val tids = taskDir.list() ?: return emptyList()
            tids.mapNotNull { name ->
                val tid = name.toIntOrNull() ?: return@mapNotNull null
                val comm = readComm(pid, tid)
                ThreadInfo(tid, comm, readCpuJiffies(pid, tid))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to list tasks for pid $pid")
            emptyList()
        }
    }

    private fun readComm(pid: Int, tid: Int): String {
        return try {
            File("/proc/$pid/task/$tid/comm").readText().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Total CPU jiffies (utime + stime) for a task, read from
     * /proc/<pid>/task/<tid>/stat. Two snapshots subtracted give the busy delta
     * used for hot-thread ranking. comm (field 2) may contain spaces/parens, so
     * we parse from the last ')' to reach the numeric fields safely.
     */
    private fun readCpuJiffies(pid: Int, tid: Int): Long {
        return try {
            val stat = File("/proc/$pid/task/$tid/stat").readText()
            val rparen = stat.lastIndexOf(')')
            if (rparen < 0) return 0L
            // Fields after ')': [0]=state(f3) [1]=ppid(f4) ... utime=f14 stime=f15
            val fields = stat.substring(rparen + 1).trim().split(Regex("\\s+"))
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L
            utime + stime
        } catch (e: Exception) {
            0L
        }
    }

    fun clusterCpus(params: DeviceParams?, clusterName: String): List<Int>? {
        return params?.topology?.clusters?.find { it.name == clusterName }?.cpus
    }

    fun buildMask(cpus: List<Int>): Long {
        var mask = 0L
        for (cpu in cpus) {
            if (cpu in 0..63) mask = mask or (1L shl cpu)
        }
        return mask
    }

    fun setAffinity(tid: Int, mask: Long): Boolean {
        if (mask == 0L) {
            Timber.tag(TAG).w("Refusing empty affinity mask for tid $tid")
            return false
        }
        val hexMask = mask.toString(16)
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(TASKSET, "-p", hexMask, tid.toString()))
            val exit = process.waitFor()
            if (exit != 0) {
                Timber.tag(TAG).w("taskset failed for tid $tid mask=0x$hexMask exit=$exit")
            }
            exit == 0
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to run taskset for tid $tid")
            false
        }
    }

    fun applyPlacement(placements: Map<Int, String>, params: DeviceParams?): Map<Int, Boolean> {
        val result = mutableMapOf<Int, Boolean>()
        for ((tid, clusterName) in placements) {
            val cpus = clusterCpus(params, clusterName)
            if (cpus == null || cpus.isEmpty()) {
                Timber.tag(TAG).w("No cpus for cluster '$clusterName', skipping tid $tid")
                result[tid] = false
                continue
            }
            result[tid] = setAffinity(tid, buildMask(cpus))
        }
        return result
    }
}
