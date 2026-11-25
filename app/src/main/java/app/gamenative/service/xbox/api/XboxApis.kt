package app.gamenative.service.xbox.api

import android.content.Context
import app.gamenative.service.xbox.XboxHelperConfig
import app.gamenative.service.xbox.XboxTokenStore
import app.gamenative.service.xbox.live.LiveConfig
import app.gamenative.service.xbox.live.MsaTokens
import app.gamenative.service.xbox.live.load
import okhttp3.OkHttpClient

/** Wires the JVM-only API classes to the helper archive and the Windows Live token exchange. */
class XboxApis(context: Context, store: XboxTokenStore, client: OkHttpClient) {
    private val filesDir = context.applicationContext.filesDir

    val config = XboxConfig { XboxHelperConfig.value(filesDir, it) }

    val xsts = XboxXsts(
        client = client,
        config = config,
        userTicket = { MsaTokens.xboxUserRpsTicket(store, LiveConfig.load(filesDir)).token },
    )

    val catalog = XboxCatalog(client, config)
    val gamePass = GamePassCatalog(client, config)
    val packages = XboxPackages(client, config, xsts)
    val library = XboxLibrary(client, config, catalog, gamePass)
}
