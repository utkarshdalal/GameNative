package app.gamenative.html5.savesync

// failure classification consumed by Html5SaveSyncService -- each subclass maps to a
// string-resource key for localized snackbar copy. extends Throwable so failures propagate
// through coroutine boundaries cleanly; retry/tiered UX deferred.
sealed class SaveSyncFailure(
    val userFacingKey: String,
    cause: Throwable? = null,
) : Throwable(userFacingKey, cause) {

    // Chromium LevelDB LOCK file still held -- WebView lock-release hasn't completed.
    // retryable class in a future UX pass.
    class LockContention(cause: Throwable?) : SaveSyncFailure("save_sync_lock", cause)

    // LevelDB MANIFEST / .log corruption surfaced by iq80 reader. non-recoverable w/o user action.
    class Corruption(val path: String, cause: Throwable?) : SaveSyncFailure("save_sync_corruption", cause)

    // WebView-side path absent, or profile did not supply a resolvable pcPath.
    class PathMissing(val path: String) : SaveSyncFailure("save_sync_missing")

    // adb / SELinux / FS-mode rejected IO. usually indicates build-flavor mismatch.
    class PermissionDenied(val path: String, cause: Throwable?) : SaveSyncFailure("save_sync_permission", cause)

    // cross-chromium-version Blink envelope skew -- cloud blob bytes written by a chromium
    // version whose SSV envelope format this device's WebView cannot parse. silently returning
    // `undefined` for blob-wrapped IDB values would crash the game on load, so this is
    // surfaced loudly instead so the user knows cloud round-trip won't work.
    class IncompatibleEnvelope(val details: String) : SaveSyncFailure("save_sync_incompatible")

    // escape hatch keeps when-expressions exhaustive. log-only copy in v1.
    class Other(cause: Throwable?) : SaveSyncFailure("save_sync_other", cause) {
        constructor(message: String) : this(IllegalStateException(message))
    }
}
