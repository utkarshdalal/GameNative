package app.gamenative.service.ea

import android.content.Context
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

/** One launch of an EA title: where the prefix and game live and which exe to start. */
data class EaLaunchSession(
    val context: Context,
    val prefixDriveC: File,
    val gameDir: File,
    val gameDirWindows: String,
    val exeRelativeWindows: String,
    val arguments: String,
    val steamAppId: Int,
) {
    private val installerXml: String? = File(gameDir, "__Installer/installerdata.xml").takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull() }

    /** Every content id the installer metadata lists (base game first, then packs). */
    val contentIds: List<String> = installerXml?.let { xml ->
        Regex("<contentID>\\s*([^<\\s]+)\\s*</contentID>").findAll(xml).map { it.groupValues[1] }.distinct().toList()
    }.orEmpty()

    @Volatile var contentId: String = contentIds.firstOrNull() ?: ""
    @Volatile var title: String = installerXml?.let { Regex("<gameTitle[^>]*>([^<]+)</gameTitle>").find(it)?.groupValues?.get(1) } ?: gameDir.name
    val version: String = installerXml?.let { Regex("<gameVersion>\\s*([^<\\s]+)\\s*</gameVersion>").find(it)?.groupValues?.get(1) } ?: "1.0.0.0"
}

/**
 * Stand-in for the EA app's LSX endpoint on 127.0.0.1:3216. Answers the game's launcher
 * requests with data from the signed-in EA account, and serves the Wine-side stub's
 * pre-launch handshake (licence fetch and launch environment).
 */
object EaLsxServer {
    private const val START_KEY = "cacf897a20b6d612ad0c05e011df52bb"
    private const val CHALLENGE_VERSION = "10,5,30,15625"
    private const val LANGUAGES = "ar_SA,de_DE,en_US,es_ES,es_MX,fr_FR,it_IT,ja_JP,ko_KR,pl_PL,pt_BR,ru_RU,zh_CN,zh_TW"

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile private var session: EaLaunchSession? = null

    @Synchronized
    fun start(newSession: EaLaunchSession) {
        stop()
        session = newSession
        val ss = ServerSocket(EaConstants.LSX_PORT, 8, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        server = ss
        running.set(true)
        thread = Thread({
            Timber.i("EA LSX server listening on 127.0.0.1:${EaConstants.LSX_PORT}")
            while (running.get()) {
                val sock = try { ss.accept() } catch (e: Exception) { if (running.get()) Timber.w(e, "LSX accept failed"); break }
                Thread({ handle(sock) }, "ea-lsx-conn").start()
            }
        }, "ea-lsx-server").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        thread = null
        session = null
    }

    fun isRunning() = running.get()

    // ---- framing -------------------------------------------------------------------------

    private fun readFrame(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == 0) return buf.toString("UTF-8")
            buf.write(b)
        }
    }

    private fun writeFrame(out: OutputStream, data: String) {
        out.write(data.toByteArray(Charsets.UTF_8))
        out.write(0)
        out.flush()
    }

    private class Msg(val kind: String, val id: String, val recipient: String, val name: String, val attrs: Map<String, String>, val children: Map<String, String>)

    private fun parse(frame: String): Msg? {
        val parser = Xml.newPullParser()
        parser.setInput(frame.reader())
        var depth = 0
        var kind = ""; var id = ""; var recipient = ""; var name = ""
        val attrs = HashMap<String, String>(); val children = HashMap<String, String>()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                depth++
                when (depth) {
                    2 -> { kind = parser.name; for (i in 0 until parser.attributeCount) { when (parser.getAttributeName(i)) { "id" -> id = parser.getAttributeValue(i); "recipient" -> recipient = parser.getAttributeValue(i) } } }
                    3 -> { name = parser.name; for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i) }
                    4 -> { val n = parser.name; val text = runCatching { parser.nextText() }.getOrDefault(""); children[n] = text; depth-- }
                }
            } else if (ev == XmlPullParser.END_TAG) depth--
            ev = parser.next()
        }
        return if (name.isEmpty()) null else Msg(kind, id, recipient, name, attrs, children)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun response(id: String, sender: String, body: String) = "<LSX><Response id=\"${esc(id)}\" sender=\"${esc(sender)}\">$body</Response></LSX>"

    private fun systemTime(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    // ---- connection -----------------------------------------------------------------------

    private fun handle(sock: Socket) {
        sock.use {
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            var key: ByteArray? = null
            try {
                writeFrame(out, "<LSX><Event sender=\"EALS\"><Challenge build=\"release\" key=\"$START_KEY\" version=\"$CHALLENGE_VERSION\"/></Event></LSX>")
                while (running.get()) {
                    val raw = readFrame(input) ?: break
                    if (raw.isBlank()) continue
                    if (raw.startsWith("<GameNative")) { writeFrame(out, handleStub(raw)); continue }
                    val plain = if (key != null) EaCrypto.lsxDecrypt(key, raw) else raw
                    Timber.d("LSX <- ${plain.take(600)}")
                    val msg = parse(plain.replace("version=\"\" ", "")) ?: continue
                    if (msg.name == "ChallengeResponse") {
                        val ok = EaCrypto.checkChallengeResponse(msg.attrs["response"].orEmpty(), START_KEY)
                        if (!ok) { Timber.e("LSX challenge response invalid, closing"); break }
                        val accept = EaCrypto.makeChallengeResponse(msg.attrs["key"].orEmpty())
                        val seed = if (msg.attrs["version"] == "2") 0 else ((accept[0].code shl 8) or accept[1].code)
                        session?.let { s ->
                            msg.children["ContentId"]?.takeIf { it.isNotBlank() }?.let { s.contentId = it }
                            msg.children["Title"]?.takeIf { it.isNotBlank() }?.let { s.title = it }
                        }
                        Timber.i("LSX game connected: title=${msg.children["Title"]} contentId=${msg.children["ContentId"]} version=${msg.attrs["version"]}")
                        writeFrame(out, response(msg.id, "EALS", "<ChallengeAccepted response=\"$accept\"/>"))
                        key = EaCrypto.makeLsxKey(seed)
                        continue
                    }
                    val reply = dispatch(msg) ?: continue
                    Timber.d("LSX -> ${reply.take(600)}")
                    writeFrame(out, if (key != null) EaCrypto.lsxEncrypt(key, reply) else reply)
                }
            } catch (e: Exception) {
                if (running.get()) Timber.w(e, "LSX connection ended")
            }
        }
    }

    private fun dispatch(m: Msg): String? {
        val s = session ?: return null
        val ctx = s.context
        val creds = EaAuthManager.credentials(ctx)
        val body: String = when (m.name) {
            "GetConfig" -> "<GetConfigResponse>" + listOf(
                "EbisuSDK" to "SDK", "EbisuSDK" to "PROFILE", "XMPP" to "PRESENCE", "XMPP" to "FRIENDS", "Commerce" to "COMMERCE",
                "EbisuSDK" to "RECENTPLAYER", "EbisuSDK" to "IGO", "EbisuSDK" to "MISC", "EALS" to "LOGIN", "EbisuSDK" to "UTILITY",
                "XMPP" to "XMPP", "XMPP" to "CHAT", "EbisuSDK" to "IGO_EVENT", "EALS" to "EALS_EVENTS", "EbisuSDK" to "LOGIN_EVENT",
                "XMPP" to "INVITE_EVENT", "EbisuSDK" to "PROFILE_EVENT", "XMPP" to "PRESENCE_EVENT", "XMPP" to "FRIENDS_EVENT",
                "Commerce" to "COMMERCE_EVENT", "XMPP" to "CHAT_EVENT", "EbisuSDK" to "DOWNLOAD_EVENT", "EbisuSDK" to "PERMISSION",
                "EbisuSDK" to "RESOURCES", "EbisuSDK" to "BLOCKED_USERS", "EbisuSDK" to "BLOCKED_USER_EVENT", "EbisuSDK" to "GET_USERID",
                "EbisuSDK" to "ONLINE_STATUS_EVENT", "EbisuSDK" to "ACHIEVEMENT", "EbisuSDK" to "ACHIEVEMENT_EVENT", "EbisuSDK" to "BROADCAST_EVENT",
                "PI" to "PROGRESSIVE_INSTALLATION", "PI" to "PROGRESSIVE_INSTALLATION_EVENT", "EbisuSDK" to "CONTENT",
            ).joinToString("") { (n, f) -> "<Service Name=\"$n\" Facility=\"$f\"/>" } + "</GetConfigResponse>"

            "GetAuthCode" -> {
                val clientId = m.attrs["ClientId"].orEmpty()
                val code = runCatching { runBlocking { EaAuthManager.authCodeFor(ctx, clientId, m.attrs["Scope"]) } }
                    .onFailure { Timber.e(it, "LSX GetAuthCode for $clientId failed") }.getOrDefault("invalid")
                "<AuthCode value=\"${esc(code)}\"/>"
            }

            "GetProfile" -> "<GetProfileResponse Persona=\"${esc(creds?.displayName.orEmpty())}\" SubscriberLevel=\"0\" CommerceCurrency=\"USD\" " +
                "IsTrialSubscriber=\"false\" Country=\"US\" UserId=\"${creds?.userId ?: "0"}\" GeoCountry=\"US\" AvatarId=\"\" IsSubscriber=\"false\" " +
                "IsSteamSubscriber=\"false\" PersonaId=\"${creds?.personaId ?: "0"}\" IsUnderAge=\"false\" UserIndex=\"0\" CommerceCountry=\"US\"/>"

            "GetSetting" -> {
                val id = m.attrs["SettingId"].orEmpty()
                "<GetSettingResponse Setting=\"${if (id.contains("ENVIRONMENT", true)) "production" else "false"}\"/>"
            }

            "GetGameInfo" -> {
                val v = when (m.attrs["GameInfoId"].orEmpty().uppercase()) {
                    "FREETRIAL" -> "false"
                    "LANGUAGES" -> LANGUAGES
                    "INSTALLED_LANGUAGE", "INSTALLEDLANGUAGE" -> "en_US"
                    "UPTODATE" -> "true"
                    else -> ""
                }
                "<GetGameInfoResponse GameInfo=\"$v\"/>"
            }

            "GetAllGameInfo" -> "<GetAllGameInfoResponse FullGamePurchased=\"true\" FullGameReleased=\"true\" InstalledVersion=\"0\" MaxGroupSize=\"16\" " +
                "Languages=\"$LANGUAGES\" Expiration=\"0000-00-00T00:00:00\" UpToDate=\"true\" HasExpiration=\"false\" EntitlementSource=\"STEAM\" " +
                "AvailableVersion=\"0\" DisplayName=\"${esc(s.title)}\" FreeTrial=\"false\" InstalledLanguage=\"en_US\" " +
                "FullGameReleaseDate=\"2014-09-02T00:00:00\" SystemTime=\"${systemTime()}\"/>"

            "GetInternetConnectedState" -> "<InternetConnectedState connected=\"1\"/>"

            "GetSettings" -> "<GetSettingsResponse IsTelemetryEnabled=\"false\" IsAutomaticGameUpdatesEnabled=\"false\" Environment=\"production\" " +
                "IsManualOffline=\"false\" Language=\"en_US\" IsIGOAvailable=\"false\" IsIGOEnabled=\"false\"/>"

            "QueryContent" -> "<QueryContentResponse>" + s.contentIds.joinToString("") { id ->
                "<Game progressValue=\"1\" contentID=\"${esc(id)}\" availableVersion=\"${esc(s.version)}\" displayName=\"${esc(s.title)}\" state=\"INSTALLED\" installedVersion=\"${esc(s.version)}\"/>"
            } + "</QueryContentResponse>"

            "RequestLicense" -> {
                val contentId = s.contentId
                val token = if (contentId.isEmpty()) "" else runCatching {
                    runBlocking {
                        EaLicenseManager.request(ctx, contentId, s.machineHash.ifEmpty { "1" }, m.attrs["RequestTicket"], m.attrs["TicketEngine"]).gameToken.orEmpty()
                    }
                }.onFailure { Timber.e(it, "LSX RequestLicense failed") }.getOrDefault("")
                "<RequestLicenseResponse License=\"${esc(token)}\"/>"
            }

            "GetBlockList" -> "<GetBlockListResponse Return=\"Success\"/>"
            "GetPresence" -> "<GetPresenceResponse UserId=\"${creds?.userId ?: "0"}\" Presence=\"INGAME\" Title=\"\" TitleId=\"\" MultiplayerId=\"\" RichPresence=\"\" GamePresence=\"\" SessionId=\"\" Group=\"\" GroupId=\"\"/>"
            "SetPresence" -> "<ErrorSuccess Code=\"0\" Description=\"\"/>"
            "QueryFriends" -> "<QueryFriendsResponse/>"
            "QueryPresence" -> "<QueryPresenceResponse/>"
            "QueryEntitlements" -> "<QueryEntitlementsResponse/>"
            "QueryOffers" -> "<QueryOffersResponse/>"
            "QueryImage" -> "<QueryImageResponse Result=\"0\"/>"
            "IsProgressiveInstallationAvailable" -> "<IsProgressiveInstallationAvailableResponse ItemId=\"${esc(m.attrs["ItemId"].orEmpty())}\" Available=\"false\"/>"
            "AreChunksInstalled" -> "<AreChunksInstalledResponse ItemId=\"${esc(m.attrs["ItemId"].orEmpty())}\" Installed=\"true\"/>"
            "GetVoipStatus" -> "<GetVoipStatusResponse Available=\"false\" Active=\"false\"/>"
            "ShowIGOWindow", "SetDownloaderUtilization" -> return null
            else -> { Timber.w("LSX unhandled request ${m.name} ${m.attrs}"); return null }
        }
        return response(m.id, m.recipient.ifEmpty { "EbisuSDK" }, body)
    }

    // ---- Wine-side stub handshake ------------------------------------------------------------

    private val EaLaunchSession.machineHash: String get() = machineHashStore[this] ?: ""
    private val machineHashStore = HashMap<EaLaunchSession, String>()

    /**
     * `<GameNative><Hello/></GameNative>` answers with the game dir and content id so the stub can
     * compute the machine hash; `<GameNative><PrepareLaunch MachineHash=".." OoaState=".."/></GameNative>`
     * fetches the licence when needed and returns the exe and environment to start the game with.
     */
    private fun handleStub(raw: String): String {
        val s = session ?: return "<GameNative><Error message=\"no EA launch session\"/></GameNative>"
        val m = runCatching { parseStub(raw) }.getOrNull() ?: return "<GameNative><Error message=\"bad request\"/></GameNative>"
        return when (m.first) {
            "Hello" -> "<GameNative><HelloResponse GameDir=\"${esc(s.gameDirWindows)}\" ContentId=\"${esc(s.contentId)}\"/></GameNative>"
            "PrepareLaunch" -> runCatching {
                val hash = m.second["MachineHash"].orEmpty()
                val ooaState = m.second["OoaState"]?.toIntOrNull() ?: 0
                if (hash.isNotEmpty()) machineHashStore[s] = hash
                val creds = EaAuthManager.credentials(s.context) ?: error("not signed in to EA")
                if (ooaState != 0 && s.contentId.isNotEmpty() && hash.isNotEmpty() && EaLicenseManager.needsUpdate(s.prefixDriveC, s.contentId)) {
                    val lic = runBlocking {
                        EaLicenseManager.refreshExternalEntitlements(s.context, creds.userId)
                        runCatching { EaLicenseManager.request(s.context, s.contentId, hash) }.getOrElse { first ->
                            if (!EaLicenseManager.isNotEntitled(first)) throw first
                            Timber.w("EA licence not entitled for ${s.contentId}; refreshing storefront entitlements and retrying")
                            EaLicenseManager.refreshExternalEntitlements(s.context, creds.userId)
                            var granted: EaLicenseManager.License? = null
                            var lastError: Throwable = first
                            for (id in listOf(s.contentId) + s.contentIds.filter { it != s.contentId }) {
                                val r = runCatching { EaLicenseManager.request(s.context, id, hash) }
                                if (r.isSuccess) { granted = r.getOrThrow(); Timber.i("EA licence granted under content id $id"); break }
                                lastError = r.exceptionOrNull()!!
                                Timber.w("EA licence for content id $id: ${lastError.message?.take(160)}")
                                if (!EaLicenseManager.isNotEntitled(lastError)) throw lastError
                            }
                            granted ?: error(
                                "EA account ${creds.displayName} has no entitlement for content ${s.contentId} (tried ${s.contentIds.size} ids). " +
                                    "Link your Steam account to this EA account at ${EaConstants.ACCOUNT_CONNECTIONS_URL}, then launch again.",
                            )
                        }
                    }
                    EaLicenseManager.save(s.prefixDriveC, lic, signatureEncoded = ooaState == 1)
                    if (lic.contentId != s.contentId) EaLicenseManager.save(s.prefixDriveC, lic.copy(contentId = s.contentId), signatureEncoded = ooaState == 1)
                }
                val access = runBlocking { EaAuthManager.accessToken(s.context) }
                val opaque = runCatching { runBlocking { EaAuthManager.opaqueLaunchToken(s.context) } }.onFailure { Timber.w(it, "opaque launch token unavailable") }.getOrDefault("")
                val env = linkedMapOf(
                    "EAAuthCode" to "unavailable",
                    "EAEgsProxyIpcPort" to "0",
                    "EAEntitlementSource" to "EA",
                    "EAExternalSource" to "EA",
                    "EAFreeTrialGame" to "false",
                    "EAGameLocale" to "en_US",
                    "EAGenericAuthToken" to access,
                    "EALaunchCode" to "unavailable",
                    "EALaunchOwner" to "EA",
                    "EALaunchEAID" to creds.displayName,
                    "EALaunchEnv" to "production",
                    "EALaunchOfflineMode" to "false",
                    "EALsxPort" to EaConstants.LSX_PORT.toString(),
                    "EARtPLaunchCode" to EaCrypto.rtpHandshake().toString(),
                    "EASecureLaunchTokenTemp" to creds.userId,
                    "EASteamProxyIpcPort" to "0",
                    "OriginSessionKey" to UUID.randomUUID().toString(),
                    "ContentId" to s.contentId,
                    "EAOnErrorExitRetCode" to "1",
                    "EAConnectionId" to s.contentId,
                    "EALicenseToken" to s.contentId,
                    "EAAccessTokenJWS" to access,
                )
                if (opaque.isNotEmpty()) env["EALaunchUserAuthToken"] = opaque
                val vars = env.entries.joinToString("") { (k, v) -> "<Var name=\"${esc(k)}\" value=\"${esc(v)}\"/>" }
                "<GameNative><LaunchEnv GameDir=\"${esc(s.gameDirWindows)}\" Exe=\"${esc(s.exeRelativeWindows)}\" Args=\"${esc(s.arguments)}\">$vars</LaunchEnv></GameNative>"
            }.getOrElse { Timber.e(it, "EA PrepareLaunch failed"); "<GameNative><Error message=\"${esc(it.message.orEmpty())}\"/></GameNative>" }
            else -> "<GameNative><Error message=\"unknown\"/></GameNative>"
        }
    }

    private fun parseStub(raw: String): Pair<String, Map<String, String>> {
        val parser = Xml.newPullParser()
        parser.setInput(raw.reader())
        var depth = 0
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                depth++
                if (depth == 2) {
                    val attrs = HashMap<String, String>()
                    for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                    return parser.name to attrs
                }
            }
            ev = parser.next()
        }
        error("empty")
    }
}
