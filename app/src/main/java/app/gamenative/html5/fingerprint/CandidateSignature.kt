package app.gamenative.html5.fingerprint

// recognized-but-unpacked engines. checked only when no EngineSignature matches; the container
// stays on wine and the user just gets a snackbar -- never auto-flipped.
sealed interface CandidateSignature {
    val engineHint: String // user-facing
    val reason: String // for logs
    fun matches(root: DirectoryRef): Boolean
}

// empty for now; kept for the next recognized-but-unpacked engine.
val candidateSignatures: List<CandidateSignature> = emptyList()
