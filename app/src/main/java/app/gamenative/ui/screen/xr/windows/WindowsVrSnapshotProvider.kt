package app.gamenative.ui.screen.xr.windows

import android.content.Context
import android.os.Build
import app.gamenative.ui.screen.xr.XrNative

data class WindowsVrRuntimeSnapshot(
    val timing: LongArray,
    val views: FloatArray,
    val input: FloatArray,
    val flags: IntArray,
) {
    val sessionState: Long get() = timing[3]
}

/**
 * Without a headset session (before the VR activity starts, or while it sleeps) frames are
 * synthesized: SYNCHRONIZED, not rendering, with the last known view size. Serials stay
 * monotonic across attach/detach since the runtime waits for a frame after the last one it saw.
 */
class WindowsVrSnapshotProvider(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var handle = 0L
    @Volatile
    private var latest: WindowsVrRuntimeSnapshot? = null
    @Volatile
    private var periodNs = 1_000_000_000L / 72

    private val lock = Any()
    private val waitLock = Any()
    private var serial = 0L
    private var nativeSerial = 0L
    private var headsetRecorded = false

    fun configure(refreshRateHz: Int) {
        periodNs = 1_000_000_000L / refreshRateHz.coerceIn(60, 120)
    }

    fun attach(handle: Long) {
        synchronized(lock) {
            this.handle = handle
            nativeSerial = 0L
            headsetRecorded = false
            latest = null
        }
    }

    fun detach() {
        synchronized(lock) {
            handle = 0L
            latest = null
        }
    }

    val isAttached: Boolean get() = handle != 0L

    fun waitFrame(timeoutMs: Int): WindowsVrRuntimeSnapshot = synchronized(waitLock) {
        val activeHandle = handle
        val snapshot = if (activeHandle != 0L) nativeFrame(activeHandle, timeoutMs.coerceAtMost(NATIVE_WAIT_MS)) else null
        snapshot ?: syntheticFrame()
    }

    // With a headset attached, waits for its first frame so the game gets the real view size.
    fun latest(): WindowsVrRuntimeSnapshot {
        latest?.let { return it }
        val activeHandle = handle
        if (activeHandle != 0L) {
            synchronized(waitLock) { latest ?: nativeFrame(activeHandle, FIRST_FRAME_WAIT_MS) }?.let { return it }
        }
        return synchronized(lock) { latest ?: buildSynthetic(nextSerial()) }
    }

    fun applyHaptic(hand: Int, amplitude: Float, duration: Long, frequency: Float): Boolean {
        val activeHandle = handle
        return activeHandle != 0L && XrNative.nativeApplyWindowsHaptic(
            activeHandle,
            hand,
            amplitude,
            duration,
            frequency,
        )
    }

    private fun nativeFrame(activeHandle: Long, timeoutMs: Int): WindowsVrRuntimeSnapshot? {
        val snapshot = WindowsVrRuntimeSnapshot(LongArray(12), FloatArray(22), FloatArray(36), IntArray(3))
        if (!XrNative.nativeWaitWindowsFrame(
                activeHandle,
                nativeSerial,
                timeoutMs,
                snapshot.timing,
                snapshot.views,
                snapshot.input,
                snapshot.flags,
            )) return null
        synchronized(lock) {
            if (handle != activeHandle) return null
            nativeSerial = snapshot.timing[0]
            snapshot.timing[0] = nextSerial()
            latest = snapshot
            if (!headsetRecorded && snapshot.timing[5] > 0 && snapshot.timing[6] > 0) {
                headsetRecorded = true
                rememberHeadset(snapshot)
            }
        }
        return snapshot
    }

    private fun syntheticFrame(): WindowsVrRuntimeSnapshot {
        val period = periodNs
        val now = System.nanoTime()
        val nextVsync = (now / period + 1) * period
        val sleepNs = nextVsync - now
        if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
        return synchronized(lock) {
            buildSynthetic(nextSerial(), nextVsync + period).also { latest = it }
        }
    }

    private fun nextSerial(): Long = ++serial

    private fun buildSynthetic(frameSerial: Long, displayTime: Long = System.nanoTime() + periodNs): WindowsVrRuntimeSnapshot {
        val previous = latest
        val timing = LongArray(12)
        timing[0] = frameSerial
        timing[1] = displayTime
        timing[2] = periodNs
        timing[3] = SESSION_STATE_SYNCHRONIZED
        timing[4] = 0
        timing[5] = prefs.getLong(key("width"), DEFAULT_WIDTH)
        timing[6] = prefs.getLong(key("height"), DEFAULT_HEIGHT)
        for (index in 7..10) timing[index] = prefs.getLong(key("timing$index"), 0L)
        timing[11] = previous?.timing?.get(11) ?: 0L
        val views = FloatArray(22)
        for (eye in 0 until 2) {
            val base = eye * 11
            views[base + 3] = 1f
            views[base + 4] = if (eye == 0) -EYE_OFFSET_M else EYE_OFFSET_M
            for (field in 0 until 4) {
                views[base + 7 + field] = prefs.getFloat(key("fov$eye$field"), DEFAULT_FOV[field])
            }
        }
        return WindowsVrRuntimeSnapshot(timing, views, FloatArray(36), IntArray(3))
    }

    private fun rememberHeadset(snapshot: WindowsVrRuntimeSnapshot) {
        val editor = prefs.edit()
            .putLong(key("width"), snapshot.timing[5])
            .putLong(key("height"), snapshot.timing[6])
        for (index in 7..10) editor.putLong(key("timing$index"), snapshot.timing[index])
        for (eye in 0 until 2) {
            for (field in 0 until 4) editor.putFloat(key("fov$eye$field"), snapshot.views[eye * 11 + 7 + field])
        }
        editor.apply()
    }

    private fun key(name: String) = "${Build.MODEL}.$name"

    private companion object {
        const val PREFS = "windows_vr_headset"
        const val NATIVE_WAIT_MS = 500
        const val FIRST_FRAME_WAIT_MS = 1000
        const val SESSION_STATE_SYNCHRONIZED = 3L
        const val EYE_OFFSET_M = 0.032f
        const val DEFAULT_WIDTH = 1440L
        const val DEFAULT_HEIGHT = 1584L
        val DEFAULT_FOV = floatArrayOf(-0.87f, 0.87f, 0.87f, -0.87f)
    }
}
