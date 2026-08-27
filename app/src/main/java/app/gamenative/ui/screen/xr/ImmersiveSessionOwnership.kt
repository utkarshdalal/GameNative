package app.gamenative.ui.screen.xr

import timber.log.Timber

/**
 * Process-local ownership of a flat-launcher -> immersive-session handoff.
 *
 * Android XR may stop, recreate, or destroy the Home Space activity while the Full Space
 * activity is starting. The flat activity must not interpret that transition as an app exit:
 * [ImmersiveXrActivity] owns Wine/environment teardown until its final destruction.
 */
internal object ImmersiveSessionOwnership {
    internal enum class Phase {
        IDLE,
        LAUNCHING,
        ACTIVE,
    }

    internal data class Snapshot(
        val appId: String? = null,
        val phase: Phase = Phase.IDLE,
    )

    private val lock = Any()

    @Volatile
    private var state = Snapshot()

    fun beginLaunch(appId: String) {
        synchronized(lock) {
            state = Snapshot(appId, Phase.LAUNCHING)
        }
        Timber.i("Immersive ownership: launch handoff started for %s", appId)
    }

    fun markActivityActive(appId: String) {
        synchronized(lock) {
            state = Snapshot(appId, Phase.ACTIVE)
        }
        Timber.i("Immersive ownership: activity owns session for %s", appId)
    }

    fun release(appId: String?) {
        var released = false
        synchronized(lock) {
            if (appId == null || state.appId == appId) {
                state = Snapshot()
                released = true
            }
        }
        if (released) {
            Timber.i("Immersive ownership: session released for %s", appId ?: "<unknown>")
        } else {
            Timber.w("Immersive ownership: ignored release for non-owner %s", appId ?: "<unknown>")
        }
    }

    fun isOwnedByImmersive(): Boolean = state.phase != Phase.IDLE

    fun shouldLauncherHandleDestruction(isChangingConfigurations: Boolean): Boolean =
        !isChangingConfigurations && !isOwnedByImmersive()

    fun snapshot(): Snapshot = state

    internal fun resetForTest() {
        synchronized(lock) {
            state = Snapshot()
        }
    }
}
