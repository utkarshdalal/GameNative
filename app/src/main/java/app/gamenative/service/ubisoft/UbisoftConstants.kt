package app.gamenative.service.ubisoft

object UbisoftConstants {
    const val CREDENTIALS_FILE = "ubisoft_credentials.enc"

    /** A launch needs at least this much ticket life left; less and the session is extended first. */
    const val MIN_TICKET_LIFE_MS = 30 * 60 * 1000L

    /** Inside the prefix's drive_c. The loaders read the launcher folder from the registry. */
    const val RUNTIME_DIR = "Program Files (x86)/Ubisoft/Ubisoft Game Launcher"
    const val RUNTIME_DIR_WINDOWS = "C:\\Program Files (x86)\\Ubisoft\\Ubisoft Game Launcher\\"

    const val LAUNCHER_EXE = "upc.exe"

    /** Session handoff to the stub, inside the prefix's drive_c. */
    const val SESSION_DIR = "ProgramData/GameNative/ubihost"
    const val SESSION_FILE = "session.json"
    const val STATUS_FILE = "ubihost-status.json"

    /** Stub exit codes. */
    const val EXIT_SETUP = 2
    const val EXIT_SESSION_REJECTED = 3
    const val EXIT_NOT_OWNED = 8
    const val EXIT_ACTIVATION_REFUSED = 10
}
