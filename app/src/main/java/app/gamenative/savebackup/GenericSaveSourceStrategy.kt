package app.gamenative.savebackup

import android.content.Context
import com.winlator.container.Container

/**
 * [SaveSourceStrategy] for non-Steam sources (GOG, Epic, Amazon).
 *
 * These sources expose no metadata equivalent to Steam's UFS `saveFilePatterns` +
 * `SteamUserData` root, so there is no way to discover a game's save location automatically.
 * Accordingly, [resolveAutomatic] always returns [AutoResolveResult.NoAutomaticResolution],
 * which causes the engine to defer to the in-app container browser whenever the save location
 * is unset (Requirement 6.6 — the same resolution/persistence rules apply across all sources,
 * they simply have no automatic step here).
 */
class GenericSaveSourceStrategy : SaveSourceStrategy {
    override fun resolveAutomatic(
        context: Context,
        container: Container,
        gameId: Int,
    ): AutoResolveResult = AutoResolveResult.NoAutomaticResolution
}
