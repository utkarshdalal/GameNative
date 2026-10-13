package app.gamenative

object FeatureGate {
    // logs every localStorage/indexedDB call. off by default: the per-call stack capture + bridge hop is
    // costly for titles that save every tick. flip on locally when debugging saves.
    @JvmField
    val ENABLE_HTML5_DIAGNOSTIC_SHIM: Boolean = false
}
