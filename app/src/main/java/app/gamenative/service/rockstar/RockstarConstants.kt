package app.gamenative.service.rockstar

object RockstarConstants {
    /** The launcher-mode sign-in page; the helper archive carries the bridge script it needs. */
    const val SIGNIN_HOST = "signin.rockstargames.com"
    const val LAUNCHER_HOST = "rgl.rockstargames.com"
    const val SIGNIN_CLIENT_ID = "launcher"

    fun signInUrl() = "https://$SIGNIN_HOST/signin/user-form?cid=$SIGNIN_CLIENT_ID"

    fun gatewayUrl(authCode: String, fingerprint: String) =
        "https://$LAUNCHER_HOST/api/connect/gateway?code=$authCode&fingerprint=$fingerprint"

    /** The cookie the shim sets on .rockstargames.com so the exchange can pick the code up. */
    const val HANDOFF_COOKIE = "gn_code"

    /** Encrypted store for the session, alongside ea_credentials.enc. */
    const val CREDENTIALS_FILE = "rockstar_credentials.enc"

    /**
     * What the Windows stub reads. rgscstub.ini's `tokenfile` points at this name inside the
     * game directory; the stub exits with code 2 when it is missing, whatever the mint mode.
     */
    const val TOKEN_FILE = "socialclub_scauth.txt"

    /** The observed ScAuthToken: opaque base64, no JWT structure. Used to recognise one. */
    const val TOKEN_LENGTH = 164
    val TOKEN_SHAPE = Regex("^[A-Za-z0-9+/]{120,400}={0,2}$")

    const val ACTIVE_TITLE_EXTRA = "rockstar.activeTitle"
}
