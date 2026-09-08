package app.gamenative.service.ea

object EaConstants {
    const val CLIENT_ID = "JUNO_PC_CLIENT"
    const val CLIENT_SECRET = "4mRLtYMb6vq9qglomWEaT4ChxsXWcyqbQpuBNfMPOYOiDmYYQmjuaBsF2Zp0RyVeWkfqhE9TuGgAw7te"
    const val REDIRECT_URI = "qrc:///html/login_successful.html"

    const val AUTH_ENDPOINT = "https://accounts.ea.com/connect/auth"
    const val TOKEN_ENDPOINT = "https://accounts.ea.com/connect/token"
    const val IDENTITY_ENDPOINT = "https://gateway.ea.com/proxy/identity/pids/me/personas"
    const val SERVICE_AGGREGATION_ENDPOINT = "https://service-aggregation-layer.juno.ea.com/graphql"
    const val LICENSE_ENDPOINT = "https://proxy.novafusion.ea.com/licenses"
    const val ENTITLEMENT_REFRESH_ENDPOINT = "https://gateway.ea.com/proxy/commerce/pids/%s/refreshexternalentitlements?status=ACTIVE"
    const val ACCOUNT_CONNECTIONS_URL = "https://myaccount.ea.com/cp-ui/connections/index"

    const val LSX_PORT = 3216

    const val CREDENTIALS_FILE = "ea_credentials.enc"

    /** Path of the licence store inside the Wine prefix (relative to drive_c). */
    const val LICENSE_DIR = "ProgramData/Electronic Arts/EA Services/License"

    /** Windows-side helper shipped with the Steam client package; handles link2ea:// and starts the game. */
    const val STUB_EXE = "C:\\Program Files (x86)\\Steam\\eastub.exe"
}
