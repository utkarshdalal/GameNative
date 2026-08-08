package app.gamenative.html5.fingerprint

// sealed result. Matched.engine is the pack id consumed by ProfileRegistry.
// subEngine is metadata only (today) -- e.g. "impact" / "terra" / "generic" for pack:nwjs.
// confidence: 0..100. multi-anchor signatures report 100; single-anchor 80.
// alternates: engineIds of OTHER signatures that ALSO matched the same tree. logged + surfaced
// for diagnostics; the first registered match still wins (deterministic ordering).
// Candidate is for shapes we recognize but don't pack -- caller surfaces a snackbar instead of
// auto-flipping (e.g. Godot, Unity WebGL, GameMaker HTML5).
sealed class FingerprintResult {
    data class Matched(
        val engine: String,
        val webRoot: String = "",
        val subEngine: String? = null,
        val confidence: Int = 100,
        val alternates: List<String> = emptyList(),
    ) : FingerprintResult()

    data class Candidate(val engineHint: String, val reason: String = "") : FingerprintResult()

    data object Unknown : FingerprintResult()
}
