package app.gamenative.display

import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors

/**
 * The gamescope session's **desired state**, held app-side and re-applied idempotently
 * whenever the session becomes applicable — at session start, on resume, after a retarget, and
 * whenever a domain changes.
 */
object GamescopeCommandQueue {
    private val lock = Any()

    // Desired state, one variable per effect domain; null = never specified.
    private var filter: GamescopeScalingFilter? = null
    private var sharpness: Int? = null
    private var look: GamescopeLook? = null
    private var fpsLimit: Int? = null

    /** Desired Scaling Mode (the scaler); applied by spawning the guest client. */
    private var scaler: GamescopeScalingMode? = null

    /** The scaler value last written to the session's X property. */
    private var spawnedScaler: GamescopeScalingMode? = null

    /** Directory the bundled LUTs live under; set with the session. */
    private var gamescopeDir: File? = null

    /** The session's X socket the scaler client is pointed at; set with the session. */
    private var x11DisplayPath: File? = null

    /** Pending one-shot actions, in issue order (deduplicated by name). */
    private val actions = LinkedHashMap<String, GamescopeConVar>()

    /** Applicable = the injector is connected and the session is not frozen. */
    private var applicable = false

    /** Bumped by [beginSession]; applicability and scaler results from an older session are ignored. */
    private var session = 0

    private val scalerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gamescope-scaler").apply { isDaemon = true }
    }

    /**
     * Seed the desired state for a new session from [state], remember where the bundled LUTs live,
     * and record the session's X socket ([x11DisplayPath], the target of the live scaling-mode
     * client).
     */
    @JvmStatic
    fun beginSession(gamescopeDir: File, x11DisplayPath: File, state: GamescopeState): Int {
        val id: Int
        synchronized(lock) {
            this.gamescopeDir = gamescopeDir
            this.x11DisplayPath = x11DisplayPath
            filter = state.filter
            sharpness = state.sharpness
            look = state.look
            scaler = state.scaler
            // The session starts with `--scaler`, so the property already holds
            // this value; no spawn until it changes.
            spawnedScaler = state.scaler
            actions.clear()
            applicable = false
            id = ++session
        }
        return id
    }

    /**
     * Mark the session applicable (`true`: the compositor accepts commands) or not (`false`:
     * suspended or torn down).
     */
    @JvmStatic
    fun setApplicable(sessionId: Int, value: Boolean) {
        var became = false
        synchronized(lock) {
            if (sessionId != session) return
            if (value && !applicable) became = true
            applicable = value
        }
        if (became) reapply()
    }

    /** Drop the whole desired state and applicability (a session is ending). */
    @JvmStatic
    fun reset() {
        synchronized(lock) {
            filter = null
            sharpness = null
            look = null
            fpsLimit = null
            scaler = null
            spawnedScaler = null
            gamescopeDir = null
            x11DisplayPath = null
            actions.clear()
            applicable = false
        }
    }

    /** Set the scaling filter; re-applied with the rest of the state. */
    fun setFilter(value: GamescopeScalingFilter) = update { filter = value }

    /** Set the Scaling Mode; spawned live only when it changes (see [spawnedScaler]). */
    fun setScaler(value: GamescopeScalingMode) = update { scaler = value }

    /** Set the scaling sharpness (UI units 0..SHARPNESS_STEPS); re-applied with the rest of the state. */
    fun setSharpness(value: Int) =
        update { sharpness = value.coerceIn(0, GamescopeSettings.SHARPNESS_STEPS) }

    /** Set the look; re-applied with the rest of the state. */
    fun setLook(value: GamescopeLook) = update { look = value }

    /** Set the frame-rate cap (0 = uncapped); re-applied with the rest of the state. */
    fun setFpsLimit(fps: Int) = update { fpsLimit = fps }

    /**
     * Ask gamescope to re-read the connector and re-select its mode.
     *
     * The mode is read once when the session starts, so a rate the connector advertises later (the
     * compositor's grid for a cap, a multiplier or a new panel) only becomes the mode once the
     * backend is dirtied. A one-shot action, not state: it carries no value to re-apply.
     */
    fun nudgeDisplayMode() = submitAction(GamescopeConVar.BACKEND_SET_DIRTY)

    /** Queue a one-shot action; sent once on the next apply (immediately when applicable). */
    fun submitAction(action: GamescopeConVar) {
        val sendNow: Boolean
        synchronized(lock) {
            sendNow = applicable
            if (!sendNow) actions[action.id] = action
        }
        if (sendNow) exec(action)
    }

    /** Apply the whole desired state and flush pending actions. */
    fun reapply() {
        val f: GamescopeScalingFilter?
        val s: Int?
        val l: GamescopeLook?
        val fps: Int?
        val dir: File?
        val acts: List<GamescopeConVar>
        val scalerChanged: Boolean
        synchronized(lock) {
            if (!applicable) return
            f = filter
            s = sharpness
            l = look
            fps = fpsLimit
            dir = gamescopeDir
            acts = actions.values.toList()
            actions.clear()
            scalerChanged = scaler != null && scaler != spawnedScaler
        }
        f?.let { exec(GamescopeConVar.SCALING_FILTER, it.id) }
        // The stored sharpness is in UI units; gamescope's convar wants the raw
        // value (0 = maximum), so this is the only place it is converted.
        s?.let { exec(GamescopeConVar.SCALING_SHARPNESS, GamescopeSettings.sharpnessRawValue(it).toString()) }
        l?.let { look ->
            // A bare set_look clears the Gamma 2.2 look; otherwise name its LUT.
            val path = if (look == GamescopeLook.OFF || dir == null) "" else GamescopeSettings.lutPath(dir, look)
            exec(GamescopeConVar.SET_LOOK, path)
        }
        fps?.let { GameNativeDrm.nativeSetFpsLimit(it) }
        if (scalerChanged) scalerExecutor.execute(::applyScaler)
        acts.forEach { exec(it) }
    }

    private fun applyScaler() {
        val mode: GamescopeScalingMode
        val dir: File
        val xdp: File
        val id: Int
        synchronized(lock) {
            if (!applicable) return
            mode = scaler?.takeIf { it != spawnedScaler } ?: return
            dir = gamescopeDir ?: return
            xdp = x11DisplayPath ?: return
            id = session
        }
        if (GamescopeScalingCommand.apply(dir, xdp, mode) != 0) return
        synchronized(lock) {
            if (id == session) spawnedScaler = mode
        }
    }

    private inline fun update(change: () -> Unit) {
        val now: Boolean
        synchronized(lock) {
            change()
            now = applicable
        }
        if (now) reapply()
    }

    private fun exec(action: GamescopeConVar, value: String = "") {
        val command = if (value.isEmpty()) action.id else "${action.id} $value"
        runCatching {
            GameNativeDrm.nativeGamescopeCommand(command)
        }
            .onSuccess { rc ->
                if (rc == 0) {
                    Timber.i("GamescopeCommandQueue: applied '%s'", command)
                } else {
                    Timber.w("GamescopeCommandQueue: '%s' rejected (rc=%d)", command, rc)
                }
            }
            .onFailure { Timber.w(it, "GamescopeCommandQueue: '%s' failed", action.id) }
    }
}
