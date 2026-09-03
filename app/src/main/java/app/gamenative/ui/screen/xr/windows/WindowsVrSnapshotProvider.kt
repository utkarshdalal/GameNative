package app.gamenative.ui.screen.xr.windows

import app.gamenative.ui.screen.xr.XrNative

data class WindowsVrRuntimeSnapshot(
    val timing: LongArray,
    val views: FloatArray,
    val input: FloatArray,
    val flags: IntArray,
)

class WindowsVrSnapshotProvider {
    @Volatile
    private var handle = 0L
    @Volatile
    private var latest: WindowsVrRuntimeSnapshot? = null

    fun attach(handle: Long) {
        this.handle = handle
    }

    fun detach() {
        handle = 0L
        latest = null
    }

    fun waitFrame(afterSerial: Long, timeoutMs: Int): WindowsVrRuntimeSnapshot? {
        val activeHandle = handle
        if (activeHandle == 0L) return null
        val snapshot = WindowsVrRuntimeSnapshot(LongArray(11), FloatArray(22), FloatArray(36), IntArray(3))
        if (!XrNative.nativeWaitWindowsFrame(
                activeHandle,
                afterSerial,
                timeoutMs,
                snapshot.timing,
                snapshot.views,
                snapshot.input,
                snapshot.flags,
            )) return null
        latest = snapshot
        return snapshot
    }

    fun latest(): WindowsVrRuntimeSnapshot? = latest

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
}
