package app.gamenative.html5.profile

// persisted in EngineProfile.engine; pack JSON file name is the id minus PREFIX.
// pack id names the RUNTIME, not the packaging -- C2/C3 both map to C3, all NW.js variants to NWJS.
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
