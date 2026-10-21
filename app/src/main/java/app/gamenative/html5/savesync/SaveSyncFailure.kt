package app.gamenative.html5.savesync

// each subclass maps to a string-resource key for the snackbar. a Throwable so it crosses
// coroutine boundaries as-is.
sealed class SaveSyncFailure(
    val userFacingKey: String,
    cause: Throwable? = null,
) : Throwable(userFacingKey, cause) {

    // chromium still holds the leveldb LOCK -- WebView hasn't released it yet.
    class LockContention(cause: Throwable?) : SaveSyncFailure("save_sync_lock", cause)

    // MANIFEST / .log corruption surfaced by iq80. not recoverable without user action.
    class Corruption(val path: String, cause: Throwable?) : SaveSyncFailure("save_sync_corruption", cause)

    // WebView-side path absent, or profile did not supply a resolvable pcPath.
    class PathMissing(val path: String) : SaveSyncFailure("save_sync_missing")

    // SELinux / FS-mode rejected IO; usually a build-flavor mismatch.
    class PermissionDenied(val path: String, cause: Throwable?) : SaveSyncFailure("save_sync_permission", cause)

    // cloud IDB bytes use a Blink SSV envelope this device's WebView can't parse. surfaced loudly:
    // silently yielding `undefined` for blob-wrapped values would crash the game on load.
    class IncompatibleEnvelope(val details: String) : SaveSyncFailure("save_sync_incompatible")

    // escape hatch; keeps when-expressions exhaustive.
    class Other(cause: Throwable?) : SaveSyncFailure("save_sync_other", cause) {
        constructor(message: String) : this(IllegalStateException(message))
    }
}
