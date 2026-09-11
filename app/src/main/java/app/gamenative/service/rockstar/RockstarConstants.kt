package app.gamenative.service.rockstar

object RockstarConstants {
    /**
     * The sign-in flow that actually yields an ScAuthToken, as used successfully on 2026-09-08.
     *
     *   1. open  signin.rockstargames.com/signin/user-form?cid=launcher   in Launcher mode
     *   2. the page talks to the launcher over window.rgscQuery / rgscAddSubscription, so a shim
     *      must answer those: OnStartAuth {launchPlatform:0, titleLaunchInfo:{activeTitleName}}
     *      and the fingerprint MUST carry device_name
     *   3. the page returns CallAuthResult{authCode} -- it expires in about 60 seconds
     *   4. exchange it on the rgl origin:
     *        GET rgl.rockstargames.com/api/connect/gateway?code=<authCode>&fingerprint=<fp>
     *        X-Requested-With: XMLHttpRequest
     *      which returns LauncherTicket / LoginGuid / ScAuthToken plus Bearer and Refresh cookies
     *
     * NOT /sdk?cid=launcher. That is the route the launcher itself opens, but it runs invisible
     * reCAPTCHA Enterprise and adds a Castle token inside its own fetchJson, so it cannot be
     * driven from outside the page -- and on the host guessed for it, it simply 404s.
     */
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
