package app.gamenative.display

import timber.log.Timber
import java.io.File

/**
 * Applies a Scaling Mode live by running the payload's `bin/gn-scaling-mode` guest client,
 * which sets the session's `GAMESCOPE_NEW_SCALING_SCALER` root property over gamescope's X
 * display.
 */
object GamescopeScalingCommand {
    /** Returned when the client binary is missing from the payload. */
    const val E_MISSING: Int = -2

    /** Returned when the process could not be started. */
    const val E_SPAWN: Int = -3

    /**
     * Set [mode] on the session whose X socket is [x11DisplayPath], using the client under
     * [gamescopeDir].
     */
    fun apply(gamescopeDir: File, x11DisplayPath: File, mode: GamescopeScalingMode): Int {
        val binary = File(gamescopeDir, "bin/gn-scaling-mode")
        if (!binary.isFile) {
            Timber.w("GamescopeScalingCommand: %s missing", binary.absolutePath)
            return E_MISSING
        }
        return runCatching {
            val process = ProcessBuilder(binary.absolutePath, mode.value.toString())
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = File(gamescopeDir, "lib").absolutePath
                    environment()["GAMESCOPE_X11_DISPLAY"] = x11DisplayPath.absolutePath
                    environment()["DISPLAY"] = ":0"
                }
                .start()
            // Drain before waiting: a bounded, short-lived client, but a full
            // pipe would otherwise stall it.
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val rc = process.waitFor()
            if (rc == 0) {
                Timber.i("GamescopeScalingCommand: applied '%s' (rc=0) %s", mode.id, output)
            } else {
                Timber.w("GamescopeScalingCommand: '%s' failed (rc=%d) %s", mode.id, rc, output)
            }
            rc
        }.onFailure { Timber.w(it, "GamescopeScalingCommand: spawn failed") }
            .getOrDefault(E_SPAWN)
    }
}
