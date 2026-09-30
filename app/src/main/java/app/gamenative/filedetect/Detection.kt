package app.gamenative.filedetect

data class Detection(
    val engines: List<String> = emptyList(),
    val antiCheat: List<String> = emptyList(),
    val sdks: List<String> = emptyList(),
    val launchers: List<String> = emptyList(),
    val emulators: List<String> = emptyList(),
    val containers: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = engines.isEmpty() && antiCheat.isEmpty() && sdks.isEmpty() &&
            launchers.isEmpty() && emulators.isEmpty() && containers.isEmpty()
}
