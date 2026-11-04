package app.gamenative.powercontrol

import com.winlator.winhandler.OnGetProcessInfoListener
import com.winlator.winhandler.ProcessInfo
import com.winlator.winhandler.WinHandler
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Applies the container's CPU list ("Processor affinity" on the Advanced tab) in a session
 * without the in-app X server.
 *
 * On X11 the list reaches a process through [com.winlator.core.Win32AppWorkarounds] when the
 * process maps its first top-level window. Under gamescope the windows map on Xwayland, so this
 * poller asks the winhandler for its process list instead and sends the mask once to each new
 * process, 64-bit or WoW64 as the winhandler reports it. The same processes stay out as on X11:
 * steam.exe, and the game when power control holds its affinity. The processes power control's
 * background pin manages stay out as well.
 */
object ContainerAffinity {
    private const val TAG = "ContainerAffinity"
    private const val POLL_MS = 5000L
    private const val REPLY_TIMEOUT_MS = 2000L

    /** Names the mask is never sent to. */
    internal val skippedProcesses = setOf("steam.exe", "winhandler.exe", "services.exe")

    @Volatile
    private var poller: Thread? = null

    /**
     * Starts polling [winHandler] for the session; stops on [stop] or when the handler is no
     * longer the live instance. A zero [mask] means no CPU list, nothing runs.
     */
    fun start(winHandler: WinHandler, mask: Int, maskWoW64: Int) {
        stop()
        if (mask == 0) {
            Timber.tag(TAG).i("No container CPU list, nothing to apply")
            return
        }
        val applied = mutableSetOf<Int>()
        val thread = Thread({
            Timber.tag(TAG).i(
                "Applying the container CPU list over the winhandler (mask 0x${Integer.toHexString(mask)}, wow64 0x${Integer.toHexString(maskWoW64)})"
            )
            while (!Thread.currentThread().isInterrupted && WinHandler.getActiveInstance() === winHandler) {
                val processes = listProcesses(winHandler) ?: emptyList()
                for (info in processes) {
                    if (info.pid in applied) continue
                    val target = targetMask(info, mask, maskWoW64) ?: continue
                    applied.add(info.pid)
                    if (sameMask(info.affinityMask, target)) {
                        Timber.tag(TAG).d("${info.name} (PID ${info.pid}) already on mask 0x${Integer.toHexString(target)}")
                        continue
                    }
                    winHandler.setProcessAffinity(info.pid, target)
                    Timber.tag(TAG).i(
                        "Applied mask 0x${Integer.toHexString(target)} to ${info.name} (PID ${info.pid}, wow64=${info.wow64Process}, was 0x${Integer.toHexString(info.affinityMask)})"
                    )
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Timber.tag(TAG).i("Container CPU list poller ended")
        }, "container-affinity")
        poller = thread
        thread.start()
    }

    fun stop() {
        poller?.interrupt()
        poller = null
    }

    /**
     * The mask [info] should get, or null when the process is left alone.
     */
    internal fun targetMask(
        info: ProcessInfo,
        mask: Int,
        maskWoW64: Int,
        pinnedGame: String? = PowerManager.pinnedGameProcessName,
        powerControlOwnsGame: Boolean = PowerManager.ownsGameAffinity,
    ): Int? {
        val name = info.name.trim()
        if (name.isEmpty() || skippedProcesses.any { it.equals(name, ignoreCase = true) }) return null
        if (powerControlOwnsGame && pinnedGame != null && pinnedGame.equals(name, ignoreCase = true)) return null
        val target = if (info.wow64Process) maskWoW64 else mask
        return target.takeIf { it != 0 }
    }

    /** The winhandler reports a 32-bit mask; the container masks are 16-bit. */
    internal fun sameMask(reported: Int, target: Int): Boolean = (reported and 0xFFFF) == (target and 0xFFFF)

    /**
     * One LIST_PROCESSES round trip, or null when the reply did not arrive in time.
     */
    private fun listProcesses(winHandler: WinHandler): List<ProcessInfo>? {
        val snapshot = ArrayList<ProcessInfo>()
        val done = CountDownLatch(1)
        val observer = OnGetProcessInfoListener { index, count, info ->
            synchronized(snapshot) {
                if (index == 0) snapshot.clear()
                if (info != null) snapshot.add(info)
                if (count == 0 || snapshot.size >= count) done.countDown()
            }
        }
        winHandler.addProcessInfoObserver(observer)
        try {
            winHandler.listProcesses()
            if (!done.await(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Timber.tag(TAG).d("No process list from the winhandler within $REPLY_TIMEOUT_MS ms")
                return null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            winHandler.removeProcessInfoObserver(observer)
        }
        return synchronized(snapshot) { snapshot.toList() }
    }
}
