package app.gamenative.powercontrol

import app.gamenative.PluviaApp
import app.gamenative.display.GameNativeDrm
import app.gamenative.events.AndroidEvent
import com.winlator.winhandler.OnGetProcessInfoListener
import com.winlator.winhandler.ProcessInfo
import com.winlator.winhandler.WinHandler
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Applies the container's CPU list ("Processor affinity" on the Advanced tab) in a session
 * without the in-app X server.
 *
 * On X11 the list reaches a process through [com.winlator.core.Win32AppWorkarounds] when the
 * process maps its first top-level window. Under gamescope the windows map on Xwayland, which
 * this app never sees; what it does see is the session's first game content
 * ([AndroidEvent.GamescopeGameContent], the gamescope stand-in for the mapped game window). On
 * that event this takes one process list from the winhandler and sends the mask to each process,
 * 64-bit or WoW64 as the winhandler reports it. Left out: steam.exe as on X11, the game when
 * power control holds its affinity, and the processes in [skippedProcesses].
 */
object ContainerAffinity {
    private const val TAG = "ContainerAffinity"
    private const val REPLY_TIMEOUT_MS = 2000L
    private const val REPLY_ATTEMPTS = 3

    /**
     * Names the mask is never sent to: steam.exe as on X11, the processes power control's
     * background pin manages, and Wine's own service processes, which map no window on X11 and so
     * never got the list there either. start.exe is the launcher process that pin found as
     * winhandler.exe by command line.
     */
    internal val skippedProcesses = setOf(
        "steam.exe", "winhandler.exe", "services.exe", "wineserver",
        "start.exe", "wineboot.exe", "winedevice.exe", "plugplay.exe", "svchost.exe", "rpcss.exe", "conhost.exe",
    )

    private var armed: ((AndroidEvent.GamescopeGameContent) -> Unit)? = null

    /**
     * Arms the session: the list goes out once, when the game first shows content. A zero [mask]
     * means no CPU list, nothing is armed. Call from the main thread, like the other event users.
     */
    fun arm(winHandler: WinHandler, mask: Int, maskWoW64: Int) {
        disarm()
        if (mask == 0) {
            Timber.tag(TAG).i("No container CPU list, nothing to apply")
            return
        }
        val listener: (AndroidEvent.GamescopeGameContent) -> Unit = {
            armed = null
            Thread({ apply(winHandler, mask, maskWoW64) }, "container-affinity").start()
        }
        armed = listener
        PluviaApp.events.once<AndroidEvent.GamescopeGameContent, Unit>(listener)
        Timber.tag(TAG).i(
            "Container CPU list armed for the first game content (mask 0x${Integer.toHexString(mask)}, wow64 0x${Integer.toHexString(maskWoW64)})"
        )
        if (GameNativeDrm.gameContentSeen) {
            // The event fires once per session and has already gone by.
            disarm()
            listener(AndroidEvent.GamescopeGameContent)
        }
    }

    fun disarm() {
        armed?.let { PluviaApp.events.off<AndroidEvent.GamescopeGameContent, Unit>(it) }
        armed = null
    }

    private fun apply(winHandler: WinHandler, mask: Int, maskWoW64: Int) {
        var processes: List<ProcessInfo>? = null
        for (attempt in 1..REPLY_ATTEMPTS) {
            if (WinHandler.getActiveInstance() !== winHandler) return
            processes = listProcesses(winHandler)
            if (processes != null) break
            Timber.tag(TAG).w("No process list from the winhandler within $REPLY_TIMEOUT_MS ms (attempt $attempt of $REPLY_ATTEMPTS)")
        }
        if (processes == null) {
            Timber.tag(TAG).w("Container CPU list not applied, the winhandler never answered")
            return
        }
        if (WinHandler.getActiveInstance() !== winHandler) return
        for (info in processes) {
            val target = targetMask(info, mask, maskWoW64) ?: continue
            if (sameMask(info.affinityMask, target)) {
                Timber.tag(TAG).d("${info.name} (PID ${info.pid}) already on mask 0x${Integer.toHexString(target)}")
                continue
            }
            winHandler.setProcessAffinity(info.pid, target)
            Timber.tag(TAG).i(
                "Applied mask 0x${Integer.toHexString(target)} to ${info.name} (PID ${info.pid}, wow64=${info.wow64Process}, was 0x${Integer.toHexString(info.affinityMask)})"
            )
        }
    }

    private val windowProcessExecutor = Executors.newSingleThreadExecutor { Thread(it, "container-affinity-window") }

    /**
     * X11 path for a Wine build that sets no _NET_WM_WOW64 on its windows (Proton 10 and later):
     * the window cannot say whether its process is WoW64, so the winhandler's process list
     * decides. The WoW64 list is only sent when it was set explicitly; with an untouched default
     * (from whatever core count the container was made on), or without an answer from the
     * winhandler, the process gets [mask], as before.
     */
    fun applyToWindowProcess(winHandler: WinHandler, pid: Int, name: String, mask: Int, maskWoW64: Int) {
        if (!isExplicitWoW64Mask(maskWoW64)) {
            if (mask != 0) winHandler.setProcessAffinity(pid, mask)
            return
        }
        windowProcessExecutor.execute {
            var processes: List<ProcessInfo>? = null
            for (attempt in 1..REPLY_ATTEMPTS) {
                if (WinHandler.getActiveInstance() !== winHandler) return@execute
                processes = listProcesses(winHandler)
                if (processes != null) break
            }
            val info = processes?.firstOrNull { it.pid == pid }
            val target = windowProcessMask(info, mask, maskWoW64) ?: return@execute
            if (WinHandler.getActiveInstance() !== winHandler) return@execute
            winHandler.setProcessAffinity(pid, target)
            Timber.tag(TAG).i(
                "Applied mask 0x${Integer.toHexString(target)} to $name (PID $pid, wow64=${info?.wow64Process ?: "unknown"})"
            )
        }
    }

    /**
     * True when [maskWoW64] is a list the user or a config chose. The default WoW64 list is the
     * upper half of the cores of the device the container was made on, which may not be this
     * one, so a mask of that shape for any core count reads as the default.
     */
    internal fun isExplicitWoW64Mask(maskWoW64: Int): Boolean {
        val bits = maskWoW64 and 0xFFFF
        if (bits == 0) return false
        return bits != defaultWoW64Mask(32 - Integer.numberOfLeadingZeros(bits))
    }

    /** Container.getFallbackCPUListWoW64() on a device with [cores] cores, as a mask. */
    internal fun defaultWoW64Mask(cores: Int): Int = ((1 shl cores) - 1) and ((1 shl (cores / 2)) - 1).inv()

    /**
     * The mask for a window's process: the WoW64 list when the winhandler reports it WoW64 and
     * the list differs from the default, else the main list.
     */
    internal fun windowProcessMask(info: ProcessInfo?, mask: Int, maskWoW64: Int): Int? {
        val wow64 = info?.wow64Process == true && isExplicitWoW64Mask(maskWoW64)
        return (if (wow64) maskWoW64 else mask).takeIf { it != 0 }
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
            if (!done.await(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            winHandler.removeProcessInfoObserver(observer)
        }
        return synchronized(snapshot) { snapshot.toList() }
    }
}
