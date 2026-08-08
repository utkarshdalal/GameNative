package app.gamenative.html5.savesync

// two-branch classification for save-sync target resolution.
// CLOUD_ENABLED: Steam UFS has Windows-rooted saveFilePatterns for this app -- Wine-side
// target path resolves via UFS config so Steam Auto-Cloud picks up saves after Wine launch.
// LOCAL_ONLY: UFS empty, null appInfo, or sideloaded container -- Wine-side target resolves
// to <container.installPath>/<pack-default-save-subdir>. GameNative IS the only save-propagation
// mechanism for these titles; no cloud in the loop.
enum class SyncMode { CLOUD_ENABLED, LOCAL_ONLY }
