package app.gamenative.service.store

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.StoreGame
import app.gamenative.enums.SaveLocation
import app.gamenative.service.ea.EaStore
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File

/**
 * What a store has to provide to appear in the library and be installed, updated, launched and
 * uninstalled through the shared [StoreGames] manager and the shared app screen. Everything
 * else (the table, downloads queue, install folders, events, library rows, container drives)
 * is shared.
 */
interface StoreAdapter {
    val source: GameSource

    /** Brand name, shown as is in every language. */
    val displayName: String

    fun isSignedIn(context: Context): Boolean

    /**
     * Whether the extracted helper archive can serve this store, read from the archive itself and
     * without any network: null while the pinned archive is not extracted. Stores without a helper
     * are always served.
     */
    fun helperServesStore(context: Context): Boolean? = true

    /** Downloads and extracts the pinned helper archive; only after the user asks to sign in. */
    suspend fun prepareHelper(context: Context) {}

    /** The signed-in account's display name, or null when signed out. */
    fun accountName(context: Context): String?

    /** Interactive sign-in; returns once the account can be used. */
    suspend fun signIn(context: Context): Result<Unit>

    fun signOut(context: Context)

    /** The owned games, called only on sign-in and on an explicit library refresh. */
    suspend fun fetchLibrary(context: Context): List<StoreGame>

    /** The title on a transparent background, for a store whose landscape art carries no title; else empty. */
    fun logoUrl(game: StoreGame): String = ""

    /** Download and installed sizes in bytes, asked for when the user opens the install dialog. */
    suspend fun downloadSizes(context: Context, game: StoreGame): Pair<Long, Long>? = null

    /** The version the store currently serves for [game], or null when it cannot tell. */
    suspend fun latestVersion(context: Context, game: StoreGame): String?

    /**
     * Installs or updates [game] into [installDir], reporting bytes on [progress]. Returns the
     * installed version. Must leave completed files in place when cancelled, so a later call resumes.
     */
    suspend fun install(context: Context, game: StoreGame, installDir: File, progress: DownloadInfo): String

    /** The executable the container starts by default, relative to the install folder. */
    fun executable(game: StoreGame, installDir: File): String?

    /** Runs before the container starts: account checks, licenses, cloud save pull. */
    suspend fun prepareLaunch(context: Context, container: Container, game: StoreGame, preferredSave: SaveLocation): StoreLaunchPrep

    /** What the session runs; [gameDirWindows] is the install folder as the game sees it. */
    fun launchCommand(context: Context, container: Container, game: StoreGame, gameDirWindows: String): StoreLaunch

    /** One-time setup run inside the prefix before the first launch (an installer's touchup step), or null. */
    fun firstRunCommand(container: Container, game: StoreGame, gameDir: File, gameDirWindows: String): String? = null

    /** Store-side cleanup before the shared uninstall deletes the install folder. */
    suspend fun onUninstall(context: Context, game: StoreGame) {}

    /** Called when the session ends: cloud save push, stopping helper servers. */
    suspend fun afterExit(context: Context, container: Container, game: StoreGame) {}
}

/**
 * The Wine command line of a store session, the folder it starts in (the install folder when null)
 * and variables added to the container environment.
 */
data class StoreLaunch(
    val command: String,
    val workingDir: File? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    companion object {
        private const val DLL_OVERRIDES = "WINEDLLOVERRIDES"

        /**
         * Adds [environment] to [envVars]. WINEDLLOVERRIDES entries join the existing list; a DLL named
         * by both keeps the store's mode.
         */
        fun applyEnvironment(envVars: EnvVars, environment: Map<String, String>) {
            for ((name, value) in environment) {
                if (name == DLL_OVERRIDES) {
                    envVars.put(name, mergeDllOverrides(envVars.get(name), value))
                } else {
                    envVars.put(name, value)
                }
            }
        }

        fun mergeDllOverrides(current: String, added: String): String {
            val addedEntries = added.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val addedNames = addedEntries.flatMap { dllNames(it) }.toSet()
            val kept = current.split(';').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { entry ->
                val mode = entry.substringAfter('=', "")
                val names = entry.substringBefore('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val remaining = names.filter { normalize(it) !in addedNames }
                when {
                    remaining.isEmpty() -> null
                    remaining.size == names.size -> entry
                    else -> remaining.joinToString(",") + "=" + mode
                }
            }
            return (kept + addedEntries).joinToString(";")
        }

        private fun dllNames(entry: String): List<String> =
            entry.substringBefore('=').split(',').map { normalize(it) }.filter { it.isNotEmpty() }

        private fun normalize(name: String): String = name.trim().lowercase().removeSuffix(".dll")
    }
}

sealed interface StoreLaunchPrep {
    data object Ready : StoreLaunchPrep
    data class Failed(val message: String) : StoreLaunchPrep
    data class SaveConflict(val localMillis: Long, val remoteMillis: Long?) : StoreLaunchPrep
}

/** The stores served through [StoreAdapter]; a store joins by adding its adapter here. */
object StoreAdapters {
    private val adapters: Map<GameSource, StoreAdapter> = listOf<StoreAdapter>(
        EaStore,
        UbisoftStoreAdapter,
    ).associateBy { it.source }

    fun of(source: GameSource): StoreAdapter? = adapters[source]

    val all: Collection<StoreAdapter> get() = adapters.values

    /** Brand name for labels; stores without an adapter yet fall back to their enum name. */
    fun displayName(source: GameSource): String = of(source)?.displayName ?: source.name
}
