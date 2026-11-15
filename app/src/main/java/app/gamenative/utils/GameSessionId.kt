package app.gamenative.utils

import java.util.UUID

/**
 * One id per game session, shared by game_launched, game_exited and the rating payload.
 *
 * A launch creates a pending id; the session binds it when the game screen starts, and
 * exit reads the bound id. A launch that fires while an earlier session is still running
 * only replaces the pending id, so the running session's game_exited keeps its own id.
 */
object GameSessionId {
    private var pending: String? = null
    private var active: String? = null

    /** Called once per launch (game_launched). Returns the new id. */
    @Synchronized
    fun newLaunch(): String = UUID.randomUUID().toString().also { pending = it }

    /**
     * Called when the game screen starts. Re-entering the screen of a running session keeps its id;
     * launch paths that never sent game_launched get a fresh id.
     */
    @Synchronized
    fun bind(): String {
        val id = pending ?: active ?: UUID.randomUUID().toString()
        pending = null
        active = id
        return id
    }

    /** Called after game_exited is sent, so the next session never reuses this id. */
    @Synchronized
    fun end() {
        active = null
    }

    /** The id of the running session, or null if no session was bound. */
    @Synchronized
    fun active(): String? = active
}
