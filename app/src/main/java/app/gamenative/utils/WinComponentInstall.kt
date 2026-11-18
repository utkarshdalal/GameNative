package app.gamenative.utils

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs one wincomponent's download and extraction without letting a failure abort the launch.
 * Returns true only when [install] reported that files were placed; the caller then applies the
 * component's DLL overrides, otherwise Wine's builtins stay in use.
 */
object WinComponentInstall {
    suspend fun attempt(identifier: String, install: suspend () -> Boolean): Boolean =
        try {
            install().also { placed ->
                if (!placed) Timber.w("Wincomponent $identifier placed no files, keeping Wine builtins")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Wincomponent $identifier is unavailable (${e.message}), keeping Wine builtins")
            false
        }

    /**
     * The wincomponents string to record as applied: [requested] with every id in [failed] put back
     * to its value in [previous], or left out when [previous] had none or already held the requested
     * value, so the recorded value differs from the request and the next launch retries it.
     */
    fun appliedValue(requested: String, previous: String, failed: Set<String>): String {
        if (failed.isEmpty()) return requested
        val previousValues = previous.split(",")
            .filter { it.isNotBlank() }
            .associate { it.substringBefore("=") to it.substringAfter("=", "") }
        return requested.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val id = entry.substringBefore("=")
                val previousValue = previousValues[id]
                when {
                    id !in failed -> entry
                    previousValue != null && previousValue != entry.substringAfter("=", "") -> "$id=$previousValue"
                    else -> null
                }
            }
            .joinToString(",")
    }
}
