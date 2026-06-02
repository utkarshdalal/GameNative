package app.gamenative.html5.fingerprint

// recognized-but-unsupported engines. fingerprinter falls through to these when no
// EngineSignature matches; caller surfaces a "we noticed this is HTML5 (engine X) but we
// don't pack it yet" snackbar and leaves the container on wine. zero auto-flip -- these
// have no pack JSON, no shims wired.
sealed interface CandidateSignature {
    val engineHint: String // user-facing display: "Godot", "Unity WebGL", "GameMaker HTML5"
    val reason: String // short marker description for logs
    fun matches(root: DirectoryRef): Boolean
}

// Godot, GameMaker HTML5, and Unity WebGL were all promoted out of this cohort to first-class
// EngineSignatures (`pack:godot`, `pack:gms`, `pack:unity`). no candidates remain; the interface +
// FingerprintResult.Candidate machinery stays for the next recognized-but-unpacked engine.
val candidateSignatures: List<CandidateSignature> = emptyList()
