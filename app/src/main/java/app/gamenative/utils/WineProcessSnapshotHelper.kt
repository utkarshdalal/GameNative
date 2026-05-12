package app.gamenative.utils

import com.winlator.core.WineUtils
import com.winlator.winhandler.ProcessInfo
import java.io.File
import java.util.Locale

object WineProcessSnapshotHelper {
    private val coreWineProcesses = setOf(
        "wineserver",
        "services",
        "start",
        "winhandler",
        "tabtip",
        "explorer",
        "winedevice",
        "svchost",
    )

    fun readFromProc(): List<ProcessInfo> {
        val procDir = File("/proc")
        val pidDirs = try {
            procDir.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } } ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val myUid = android.os.Process.myUid()
        val processes = mutableListOf<ProcessInfo>()

        for (pidDir in pidDirs) {
            try {
                val pid = pidDir.name.toIntOrNull() ?: continue
                if (pid == android.os.Process.myPid()) continue

                val statusLines = try {
                    File(pidDir, "status").readLines()
                } catch (_: Exception) {
                    emptyList()
                }
                val uid = statusLines
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (uid == null || uid != myUid) continue

                val comm = try { File(pidDir, "comm").readText().trim() } catch (_: Exception) { "" }
                val stat = try { File(pidDir, "stat").readText() } catch (_: Exception) { "" }
                val cmdlineBytes = try { File(pidDir, "cmdline").readBytes() } catch (_: Exception) { ByteArray(0) }
                val cmdlineArgs = cmdlineBytes.toNullSeparatedStrings()
                val searchableText = buildString {
                    append(comm)
                    append(' ')
                    append(stat)
                    append(' ')
                    append(cmdlineArgs.joinToString(" "))
                }

                if (!searchableText.contains("wine", ignoreCase = true) &&
                    !searchableText.contains(".exe", ignoreCase = true)) {
                    continue
                }

                val name = cmdlineArgs
                    .firstOrNull { it.endsWith(".exe", ignoreCase = true) }
                    ?: cmdlineArgs.firstOrNull { it.contains(".exe", ignoreCase = true) }
                    ?: cmdlineArgs.firstOrNull { it.contains("wine", ignoreCase = true) }
                    ?: comm.ifBlank { null }
                    ?: continue

                val rssBytes = statusLines
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.times(1024L)
                    ?: 0L

                processes.add(
                    ProcessInfo(
                        pid,
                        name.substringAfterLast('/').substringAfterLast('\\'),
                        rssBytes,
                        0,
                        false,
                    ),
                )
            } catch (_: Exception) {
                // Process exited or became unreadable while enumerating /proc.
            }
        }

        val allowlist = buildEssentialProcessAllowlist()
        return processes.sortedWith(
            compareByDescending<ProcessInfo> { normalizeProcessName(it.name) !in allowlist }
                .thenByDescending { it.memoryUsage },
        )
    }

    private fun buildEssentialProcessAllowlist(): Set<String> {
        val essentialServices = WineUtils.getEssentialServiceNames()
            .map { normalizeProcessName(it) }
        return (essentialServices + coreWineProcesses).toSet()
    }

    private fun normalizeProcessName(name: String): String {
        val trimmed = name.trim().trim('"')
        val base = trimmed.substringAfterLast('/').substringAfterLast('\\')
        val lower = base.lowercase(Locale.getDefault())
        return if (lower.endsWith(".exe")) lower.removeSuffix(".exe") else lower
    }

    private fun ByteArray.toNullSeparatedStrings(): List<String> {
        if (isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var start = 0
        for (i in indices) {
            if (this[i] == 0.toByte()) {
                if (i > start) {
                    val value = String(this, start, i - start).trim()
                    if (value.isNotBlank()) result.add(value)
                }
                start = i + 1
            }
        }
        if (start < size) {
            val value = String(this, start, size - start).trim()
            if (value.isNotBlank()) result.add(value)
        }
        return result
    }
}
