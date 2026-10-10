package app.gamenative.html5.savesync

// CLOUD_ENABLED: Steam UFS has Windows-rooted saveFilePatterns, so the Wine-side target follows the
// UFS config and Steam Auto-Cloud picks the saves up.
// LOCAL_ONLY: no usable UFS (or sideloaded) -- target is <installPath>/<pack-default-save-subdir> and
// GameNative is the only thing moving saves; no cloud in the loop.
enum class SyncMode { CLOUD_ENABLED, LOCAL_ONLY }
