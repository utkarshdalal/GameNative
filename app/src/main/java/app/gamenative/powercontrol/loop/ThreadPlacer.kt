package app.gamenative.powercontrol.loop

import timber.log.Timber
import java.io.File

data class ThreadInfo(val tid: Int, val comm: String)

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
                ThreadInfo(tid, comm)
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
