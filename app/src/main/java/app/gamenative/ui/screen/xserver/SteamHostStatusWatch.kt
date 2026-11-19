package app.gamenative.ui.screen.xserver

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Mirrors steamhost's one-word stage file onto the boot splash while it says "Starting Steam…".
 * steamhost writes starting / logon / connecting / retry / waiting / launching / launched, or
 * "failed <reason>" (setup / logon / sync / launch status=<n> / start; older builds: logon / launch).
 */
object SteamHostStatusWatch {
    enum class FailureReason { CONNECTION, REFUSED, OTHER }

    fun failureReason(status: String): FailureReason {
        val words = status.trim().split(' ')
        return when (words.getOrNull(1)) {
            "logon", "sync" -> FailureReason.CONNECTION
            "launch" -> if (launchStatusCode(status) != null) FailureReason.REFUSED else FailureReason.OTHER
            else -> FailureReason.OTHER
        }
    }

    fun launchStatusCode(status: String): Int? =
        status.trim().split(' ').firstOrNull { it.startsWith("status=") }?.substringAfter('=')?.toIntOrNull()

    private fun failureMessage(context: Context, status: String): String {
        val offlineMode = context.getString(R.string.steam_offline_mode)
        return when (failureReason(status)) {
            FailureReason.CONNECTION -> context.getString(R.string.steam_launch_failed_connection, offlineMode)
            FailureReason.REFUSED -> context.getString(R.string.steam_launch_failed_refused, launchStatusCode(status) ?: 0)
            FailureReason.OTHER -> context.getString(R.string.steam_launch_failed, offlineMode)
        }
    }

    private val active = AtomicBoolean(false)

    @Volatile
    private var failure: String? = null

    /** The player-facing message of the last launch steamhost gave up on, once. */
    fun takeFailure(): String? = failure.also { failure = null }

    private fun statusFile(imageFs: ImageFs) =
        File(imageFs.wineprefix, "drive_c/Program Files (x86)/Steam/steamhost_status")

    fun start(context: Context, imageFs: ImageFs, onFailed: ((String) -> Unit)? = null) {
        val file = statusFile(imageFs)
        file.delete()
        failure = null
        if (!active.compareAndSet(false, true)) return
        thread(name = "steamhost-status") {
            val startedAt = System.currentTimeMillis()
            var last = ""
            try {
                while (active.get() && System.currentTimeMillis() - startedAt < 10 * 60_000L) {
                    val status = runCatching { file.readText().trim() }.getOrDefault("")
                    if (status != last) {
                        last = status
                        val text = when (status.substringBefore(' ')) {
                            "logon" -> context.getString(R.string.steam_logging_in)
                            "connecting" -> context.getString(R.string.steam_connecting)
                            "retry" -> context.getString(R.string.steam_retrying_login)
                            "waiting" -> context.getString(R.string.steam_waiting)
                            "launching" -> "Launching game..."
                            else -> null
                        }
                        if (text != null) PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(text))
                        if (status.startsWith("failed")) {
                            val message = failureMessage(context, status)
                            failure = message
                            onFailed?.invoke("$message (steamhost: $status)")
                            PluviaApp.events.emit(AndroidEvent.SteamHostFailed(message))
                            break
                        }
                        if (status.startsWith("launched")) break
                    }
                    Thread.sleep(500)
                }
            } finally {
                active.set(false)
            }
        }
    }

    fun stop() {
        active.set(false)
    }
}
