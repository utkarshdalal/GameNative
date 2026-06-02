package app.gamenative

// build-time + runtime feature flags shared across subsystems.
object FeatureGate {
    // JS shim that logs every localStorage + indexedDB call. each wrapped call pays new Error()
    // + stack split + JSON.stringify + JS-interface bridge call, which is significant for save-
    // heavy titles (Cookie Clicker writes localStorage every tick). off by default; flip on
    // locally + rebuild when investigating save-related bugs.
    @JvmField
    val ENABLE_HTML5_DIAGNOSTIC_SHIM: Boolean = false
}
