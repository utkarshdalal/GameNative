package app.gamenative.enums

enum class SteamRealm(val keyValue: String?) {
    SteamGlobal(keyValue = "steamglobal"),
    SteamChina(keyValue = "steamchina"),
    Unknown(keyValue = "unknown"),
    ;

    companion object {
        fun from(keyValue: String?): SteamRealm {
            return when (keyValue) {
                SteamGlobal.keyValue -> SteamGlobal
                SteamChina.keyValue -> SteamChina
                else -> Unknown
            }
        }
    }
}
