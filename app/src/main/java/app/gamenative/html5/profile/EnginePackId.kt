package app.gamenative.html5.profile

// canonical engine-pack identifiers. both the persisted EngineProfile.engine string and each
// EngineSignature.engineId use these values; Kotlin-side comparisons reference the consts so a
// typo is a compile error instead of a silently-missed branch. the pack JSON file name is the id
// with PREFIX stripped (see ProfileRegistry). pack identifier is RUNTIME, not packaging --
// C2/C3 both map to C3, all NW.js variants to NWJS.
object EnginePackId {
    const val PREFIX = "pack:"

    const val RMMV = "pack:rmmv"
    const val C3 = "pack:c3"
    const val NWJS = "pack:nwjs"
    const val GMS = "pack:gms"
    const val GODOT = "pack:godot"
    const val UNITY = "pack:unity"
    const val TYRANO = "pack:tyrano"
    const val ELECTRON = "pack:electron"
}
