package app.gamenative.ui

/**
 * Bridge that lets the XR activity (com.winlator.xr) trigger a game launch through the
 * normal Compose launch pipeline without depending on it directly. PluviaMain assigns
 * [onClickPlay] once the launch flow is wired up.
 */
object PlayBridge {
    @JvmField
    var onClickPlay: ((String, Boolean) -> Unit)? = null
}
