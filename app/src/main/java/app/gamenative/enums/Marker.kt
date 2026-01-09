package app.gamenative.enums

enum class Marker(val fileName: String ) {
    DOWNLOAD_COMPLETE_MARKER(".download_complete"),
    DOWNLOAD_IN_PROGRESS_MARKER(".download_in_progress"),
    STEAM_DLL_REPLACED(".steam_dll_replaced"),
    STEAM_DLL_RESTORED(".steam_dll_restored"),
    STEAM_COLDCLIENT_USED(".steam_coldclient_used"),
}
