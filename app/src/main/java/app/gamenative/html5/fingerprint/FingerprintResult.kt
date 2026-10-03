package app.gamenative.html5.fingerprint

// subEngine is metadata only (e.g. "impact" / "terra" for pack:nwjs).
// confidence 0..100: multi-anchor signatures report 100, single-anchor 80.
// alternates: other signatures that also matched -- diagnostics only; the first registered match wins.
// Candidate: recognized but unpacked -- snackbar, never auto-flip.
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
