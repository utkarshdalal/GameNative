package app.gamenative.mods

internal object ModImportSafetyLimits {
    const val MAX_ENTRIES = 50_000
    const val MAX_CONTENT_BYTES = 20L * 1024L * 1024L * 1024L
    const val MAX_RELATIVE_PATH_LENGTH = 1_024
    const val MAX_DIRECTORY_DEPTH = 64
}
