package app.gamenative.savebackup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the **persistable-permission** side of the SAF external-location flow (Requirement 10):
 * requesting a durable grant for a selected tree URI, remembering it per game, and validating a
 * previously remembered grant so it can be reused without re-prompting.
 *
 * ## Why the picker launch is *not* on this interface
 *
 * The actual SAF picker must be launched from a composable/activity through
 * `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` — it cannot be a
 * plain synchronous `pickTree(): Uri?` because the result is delivered asynchronously to an
 * `ActivityResultCallback`. The design's `pickTree()` abstraction is therefore satisfied by the
 * Compose helper [rememberSafPicker] (in `SafPicker.kt`), which the orchestrator/UI (task 11) drives
 * to obtain a selected `Uri?`. This manager owns everything that does **not** need the Activity
 * result API — `tryPersist` and `rememberedLocation` — so those parts are unit-drivable with a
 * mocked `Context`/`ContentResolver`.
 *
 * The split is:
 * - **UI (Compose):** launches the picker, gets a `Uri?` back (see [rememberSafPicker]).
 * - **This manager:** on a non-null selection, calls [tryPersist]; and before opening the picker,
 *   consults [rememberedLocation] to decide whether a prior grant can be reused (Req 10.2/10.4).
 *
 * Unlike `CustomGameFolderPicker`, which converts the tree URI to a raw filesystem path and never
 * takes a persistable permission, this manager does the persistable-permission work fresh:
 * Requirement 10 is net-new (see the design's Research Notes).
 */
interface SafLocationManager {
    /**
     * Request a durable (persistable) permission for the just-selected [treeUri] and, on success,
     * remember it for [appId] so a later operation can reuse it (Requirements 10.1, 10.2).
     *
     * @return `true` when the persistable grant was obtained and the URI was remembered; `false`
     *   when the grant could not be obtained (the caller should warn "folder could not be
     *   remembered" and continue using [treeUri] for this operation only — Requirement 10.3). A
     *   `false` result never remembers the URI.
     */
    suspend fun tryPersist(appId: String, treeUri: Uri): Boolean

    /**
     * The remembered external location for [appId] as a still-valid persisted grant, or `null`.
     *
     * Returns the remembered tree URI only when (a) a matching entry exists in
     * `contentResolver.persistedUriPermissions` with read access still granted, and (b) the tree is
     * still resolvable and readable via `DocumentFile.fromTreeUri(...)`. If the remembered grant was
     * revoked, expired, or can no longer be resolved, the stale entry is forgotten and `null` is
     * returned so the caller reopens the picker (Requirement 10.4). When a valid grant exists the
     * caller reuses it without re-prompting (Requirement 10.2).
     */
    suspend fun rememberedLocation(context: Context, appId: String): Uri?

    /**
     * Forget the remembered external location for [appId]: release the app's persistable URI
     * permission for that tree (so it no longer counts against the per-app grant limit) and clear
     * the stored entry. A no-op when nothing is remembered. Failing to release the OS-level grant
     * (e.g. it was already revoked) is not fatal — the stored entry is cleared regardless.
     */
    suspend fun forget(appId: String)
}

/**
 * Default [SafLocationManager] backed by [RememberedSafLocationStore] for per-game URI persistence
 * and the app's `ContentResolver` for the actual persistable-permission calls.
 *
 * `context` is passed per call (rather than held) so the manager can be a simple singleton while
 * tests supply a mocked `Context`/`ContentResolver`. [tryPersist] takes its own [Context] via the
 * secondary entry point; production callers use [tryPersist] with the app context threaded through.
 */
class DefaultSafLocationManager(
    private val context: Context,
    private val store: RememberedSafLocationStore,
    /** Resolves a tree URI to a [DocumentFile] for readability checks; injectable for tests. */
    private val treeResolver: (Context, Uri) -> DocumentFile? = { ctx, uri ->
        DocumentFile.fromTreeUri(ctx, uri)
    },
) : SafLocationManager {

    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    // All three methods touch ContentResolver / DocumentFile (provider IPC that can block), so they
    // run on Dispatchers.IO: BaseAppScreen invokes them from a UI-scoped coroutine.

    override suspend fun tryPersist(appId: String, treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            // Confirm the grant was actually retained rather than trusting the call alone: a
            // provider may silently drop it. Only remember the URI when a matching persisted grant
            // is present.
            if (!hasPersistedGrant(treeUri)) {
                Timber.w("Persistable grant for %s was not retained; not remembering", treeUri)
                return@withContext false
            }
            store.put(appId, treeUri.toString())
            true
        } catch (e: SecurityException) {
            // Grant not obtained (Requirement 10.3): do not remember; caller warns and continues
            // for this operation only.
            Timber.w(e, "Could not obtain persistable permission for %s", treeUri)
            false
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist SAF permission for %s", treeUri)
            false
        }
    }

    override suspend fun rememberedLocation(context: Context, appId: String): Uri? =
        withContext(Dispatchers.IO) {
            val remembered = store.get(appId) ?: return@withContext null
            val uri = try {
                Uri.parse(remembered)
            } catch (e: Exception) {
                Timber.w(e, "Remembered SAF URI for %s is unparseable; forgetting", appId)
                store.remove(appId)
                return@withContext null
            }

            // (a) The persistable grant must still be present in the resolver's persisted
            // permissions (revoked/expired grants disappear from this list) — Requirement 10.4.
            if (!hasPersistedGrant(uri)) {
                Timber.i("Remembered SAF grant for %s is no longer persisted; forgetting", appId)
                store.remove(appId)
                return@withContext null
            }

            // (b) The tree must still be resolvable and readable — a provider may be uninstalled or
            // the folder removed even while a stale grant lingers (Requirement 10.4).
            val doc = try {
                treeResolver(context, uri)
            } catch (e: Exception) {
                Timber.w(e, "Remembered SAF tree for %s could not be resolved; forgetting", appId)
                null
            }
            if (doc == null || !doc.exists() || !doc.canRead()) {
                Timber.i("Remembered SAF tree for %s is unresolvable/unreadable; forgetting", appId)
                // Release the still-held OS grant before forgetting so it does not leak against the
                // per-app grant limit; releasing a grant referenced only by this stale entry is safe.
                releaseGrantQuietly(uri)
                store.remove(appId)
                return@withContext null
            }

            uri
        }

    /**
     * True when [treeUri] appears in `contentResolver.persistedUriPermissions` with read access
     * still granted. Revoked or expired grants are absent from this list, which is how
     * Requirement 10.4's "no longer valid" condition is detected.
     */
    private fun hasPersistedGrant(treeUri: Uri): Boolean {
        val target = treeUri.toString()
        return context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri.toString() == target && perm.isReadPermission
        }
    }

    override suspend fun forget(appId: String) = withContext(Dispatchers.IO) {
        val remembered = store.get(appId) ?: return@withContext
        val uri = try {
            Uri.parse(remembered)
        } catch (_: Exception) {
            // Unparseable — just clear the store entry.
            store.remove(appId)
            return@withContext
        }

        // Release the OS-level persistable grant so it no longer counts against the per-app limit,
        // then clear the store entry regardless of whether the release succeeded.
        releaseGrantQuietly(uri)
        store.remove(appId)
    }

    /** Release the persistable grant for [uri], logging (not throwing) if it cannot be released. */
    private fun releaseGrantQuietly(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Timber.w(e, "Could not release persistable permission for %s", uri)
        }
    }

    companion object {
        /** A manager backed by [PrefManager]'s store. Must only be called after `PrefManager.init`. */
        fun default(context: Context): DefaultSafLocationManager =
            DefaultSafLocationManager(
                context = context.applicationContext,
                store = DataStoreRememberedSafLocationStore.default(),
            )
    }
}
