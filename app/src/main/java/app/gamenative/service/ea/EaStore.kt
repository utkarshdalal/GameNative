package app.gamenative.service.ea

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.StoreGame
import app.gamenative.enums.SaveLocation
import app.gamenative.service.store.StoreAdapter
import app.gamenative.service.store.StoreGames
import app.gamenative.service.store.StoreLaunch
import app.gamenative.service.store.StoreLaunchPrep
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * EA as a store of its own: the owned library, installs from EA's CDN, and launches through the
 * Wine-side stub and the LSX server with no Steam involved. Library, build and download queries
 * follow Maxima (ArmchairDevelopers/Maxima, GPL-3.0).
 */
object EaStore : StoreAdapter {
    override val source = GameSource.EA
    override val displayName = "EA"

    private const val PAGE_SIZE = 1000
    private const val MAX_PAGES = 20
    private const val OFFER_CHUNK = 200

    private const val OWNED_QUERY = "query getPreloadedOwnedGames(\$next: String, \$limit: Int, \$type: [GameProductType!]!, " +
        "\$storefronts: [UserGameProductStorefront!], \$platforms: [GamePlatform!]!) { me { " +
        "ownedGameProducts(storefronts: \$storefronts, paging: {limit: \$limit, next: \$next}, productFound: true, " +
        "orderBy: {field: NAME, direction: ASC}, type: \$type, downloadableOnly: false, platforms: \$platforms) { next totalCount " +
        "items { originOfferId status product { id name downloadable gameSlug baseItem(availabilities: [VISIBLE]) { title id baseGameSlug gameType } " +
        "gameProductUser(storefronts: \$storefronts) { gameProductUserTrial { trialTimeRemainingSeconds } status } } } } } }"

    private const val OFFERS_QUERY = "query getLegacyCatalogDefs(\$offerIds: [String!]!) { legacyOffers(offerIds: \$offerIds) { " +
        "offerId: id contentId displayName displayType executePathOverride installationDirectory isDownloadable releaseDate } }"

    private const val IMAGES_QUERY = "query GameImages(\$gameSlug: String!, \$locale: Locale!) { game(slug: \$gameSlug, locale: \$locale) { " +
        "keyArt { aspect9x16Image { path } aspect16x9Image { path } aspect1x1Image { path } largestImage { path } } " +
        "packArt { aspect2x1Image { path } aspect9x16Image { path } } } }"

    private const val BUILDS_QUERY = "query availableBuilds(\$offerId: String!) { availableBuilds(offerId: \$offerId) { " +
        "buildId downloadType gameVersion buildLiveDate } }"

    private const val DOWNLOAD_URL_QUERY = "query downloadUrl(\$offerId: String!, \$buildId: String) { downloadUrl(offerId: \$offerId, buildId: \$buildId) { url syncUrl } }"

    private data class Build(val buildId: String, val gameVersion: String) {
        val version: String get() = buildId.ifEmpty { gameVersion }
    }

    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

    private fun meta(game: StoreGame): JSONObject = runCatching { JSONObject(game.metadata) }.getOrElse { JSONObject() }

    override fun isSignedIn(context: Context): Boolean = EaAuthManager.isLoggedIn(context)

    override fun accountName(context: Context): String? = EaAuthManager.displayName(context)

    override suspend fun signIn(context: Context): Result<Unit> = runCatching {
        EaHelperArchive.download(context) {}
        EaLoginGate.ensureSignedIn(context).getOrThrow()
    }

    override fun signOut(context: Context) = EaAuthManager.logout(context)

    // ── Library ──────────────────────────────────────────────────────────────

    override suspend fun fetchLibrary(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        val token = EaAuthManager.accessToken(context)
        val products = ownedBaseGames(token)
        val offers = HashMap<String, JSONObject>()
        for (chunk in products.keys.toList().chunked(OFFER_CHUNK)) {
            val arr = EaGraphQl.post(token, "getLegacyCatalogDefs", OFFERS_QUERY, JSONObject().put("offerIds", JSONArray(chunk)))
                .optJSONArray("legacyOffers") ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { offers[it.text("offerId")] = it }
        }
        val known = StoreGames.all.value.filter { it.source == source.name }.associateBy { it.productId }
        val gate = Semaphore(6)
        coroutineScope {
            products.mapNotNull { (offerId, product) ->
                val offer = offers[offerId] ?: return@mapNotNull null
                if (offer.text("displayType") != "FullGame" || !offer.optBoolean("isDownloadable")) return@mapNotNull null
                async {
                    val slug = product.text("gameSlug")
                    val previous = known[offerId]
                    val art = if (previous != null && previous.coverUrl.isNotEmpty()) {
                        Triple(previous.iconUrl, previous.coverUrl, previous.heroUrl)
                    } else {
                        gate.withPermit { images(token, slug) }
                    }
                    toStoreGame(offerId, product, offer, art)
                }
            }.awaitAll()
        }
    }

    /** Owned base games keyed by offer id, without trials, add-ons or per-edition duplicates (Maxima's grouping). */
    private fun ownedBaseGames(token: String): LinkedHashMap<String, JSONObject> {
        val bySlug = LinkedHashMap<String, Pair<String, JSONObject>>()
        var next = "0"
        repeat(MAX_PAGES) {
            val variables = JSONObject()
                .put("next", next)
                .put("limit", PAGE_SIZE)
                .put("type", JSONArray(listOf("DIGITAL_FULL_GAME")))
                .put("storefronts", JSONArray(listOf("EA")))
                .put("platforms", JSONArray(listOf("PC")))
            val owned = EaGraphQl.post(token, "getPreloadedOwnedGames", OWNED_QUERY, variables)
                .optJSONObject("me")?.optJSONObject("ownedGameProducts") ?: error("EA getPreloadedOwnedGames: no ownedGameProducts")
            val items = owned.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val offerId = item.text("originOfferId")
                if (offerId.isEmpty()) continue
                val product = item.optJSONObject("product") ?: continue
                val base = product.optJSONObject("baseItem") ?: continue
                val trial = product.optJSONObject("gameProductUser")?.optJSONObject("gameProductUserTrial")
                if (base.text("gameType") != "BASE_GAME" || base.text("baseGameSlug").isNotEmpty() || trial != null) continue
                bySlug.putIfAbsent(product.text("gameSlug").ifEmpty { offerId }, offerId to product)
            }
            val following = owned.text("next")
            if (items.length() == 0 || following.isEmpty() || following == next) return LinkedHashMap(bySlug.values.toMap())
            next = following
        }
        return LinkedHashMap(bySlug.values.toMap())
    }

    /** Icon, portrait cover and wide hero for a game slug. */
    private fun images(token: String, slug: String): Triple<String, String, String> {
        if (slug.isEmpty()) return Triple("", "", "")
        val game = runCatching {
            EaGraphQl.post(token, "GameImages", IMAGES_QUERY, JSONObject().put("gameSlug", slug).put("locale", "en")).optJSONObject("game")
        }.onFailure { Timber.w("EA images for $slug: ${it.message?.take(160)}") }.getOrNull() ?: return Triple("", "", "")
        fun JSONObject?.path(name: String) = this?.optJSONObject(name)?.text("path").orEmpty()
        val key = game.optJSONObject("keyArt")
        val pack = game.optJSONObject("packArt")
        val hero = key.path("aspect16x9Image").ifEmpty { key.path("largestImage") }.ifEmpty { pack.path("aspect2x1Image") }
        val cover = pack.path("aspect9x16Image").ifEmpty { key.path("aspect9x16Image") }.ifEmpty { hero }
        val icon = key.path("aspect1x1Image").ifEmpty { pack.path("aspect2x1Image") }.ifEmpty { hero }
        return Triple(icon, cover, hero)
    }

    private fun toStoreGame(offerId: String, product: JSONObject, offer: JSONObject, art: Triple<String, String, String>): StoreGame {
        val title = product.optJSONObject("baseItem")?.text("title")?.replace("\n", "")?.ifEmpty { null }
            ?: offer.text("displayName").ifEmpty { product.text("name") }
        val metadata = JSONObject()
            .put("contentId", offer.text("contentId"))
            .put("slug", product.text("gameSlug"))
            .put("executePathOverride", offer.text("executePathOverride"))
            .put("installationDirectory", offer.text("installationDirectory"))
        return StoreGame(
            source = source.name,
            productId = offerId,
            title = title,
            iconUrl = art.first,
            coverUrl = art.second,
            heroUrl = art.third,
            releaseDate = offer.text("releaseDate"),
            metadata = metadata.toString(),
        )
    }

    // ── Builds and installs ──────────────────────────────────────────────────

    private fun liveBuild(token: String, offerId: String): Build? {
        val arr = EaGraphQl.post(token, "availableBuilds", BUILDS_QUERY, JSONObject().put("offerId", offerId)).optJSONArray("availableBuilds")
            ?: return null
        val builds = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val live = builds.firstOrNull { it.text("downloadType").equals("LIVE", ignoreCase = true) } ?: builds.firstOrNull() ?: return null
        return Build(live.text("buildId"), live.text("gameVersion"))
    }

    private fun downloadUrl(token: String, offerId: String, buildId: String): String {
        val variables = JSONObject().put("offerId", offerId).put("buildId", buildId)
        return EaGraphQl.post(token, "downloadUrl", DOWNLOAD_URL_QUERY, variables).optJSONObject("downloadUrl")?.text("url")
            ?.ifEmpty { null } ?: error("EA did not return a download URL for $offerId")
    }

    override suspend fun downloadSizes(context: Context, game: StoreGame): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val token = EaAuthManager.accessToken(context)
        val build = liveBuild(token, game.productId) ?: return@withContext null
        val url = downloadUrl(token, game.productId, build.buildId)
        val installDir = StoreGames.installDir(context, game)
        val plan = EaZipInstaller.plan(url, installDir)
        requireDip(context, game, plan, url, installDir)
        val files = plan.entries.filterNot { it.isDirectory }
        files.sumOf { it.compressedSize } to files.sumOf { it.uncompressedSize }
    }

    override suspend fun latestVersion(context: Context, game: StoreGame): String? = withContext(Dispatchers.IO) {
        liveBuild(EaAuthManager.accessToken(context), game.productId)?.version
    }

    override suspend fun install(context: Context, game: StoreGame, installDir: File, progress: DownloadInfo): String = withContext(Dispatchers.IO) {
        EaHelperArchive.download(context) {}
        val token = EaAuthManager.accessToken(context)
        val build = liveBuild(token, game.productId) ?: error("EA has no build of ${game.title} to download")
        val url = downloadUrl(token, game.productId, build.buildId)
        val plan = EaZipInstaller.plan(url, installDir)
        requireDip(context, game, plan, url, installDir)
        val total = plan.entries.filterNot { it.isDirectory }.sumOf { it.compressedSize }
        val done = java.util.concurrent.atomic.AtomicLong(total - plan.pendingBytes)
        progress.setTotalExpectedBytes(total)
        progress.initializeBytesDownloaded(done.get())
        progress.updateStatusMessage(null)
        progress.emitProgressChange()
        Timber.i("EA install ${game.title}: build ${build.version}, ${plan.pending.size}/${plan.entries.size} entries to fetch, ${plan.partialBytes} bytes already on disk")
        EaZipInstaller.install(
            plan = plan,
            installDir = installDir,
            url = url,
            refreshUrl = { downloadUrl(EaAuthManager.accessToken(context), game.productId, build.buildId) },
            onBytes = { delta ->
                val now = done.addAndGet(delta)
                if (delta > 0) progress.updateBytesDownloaded(delta) else progress.initializeBytesDownloaded(now)
                progress.emitProgressChange()
            },
        )
        build.version
    }

    /**
     * Fetches only the installer data first and refuses titles without a DiP manifest (pre-DiP
     * releases), before anything large is downloaded.
     */
    private suspend fun requireDip(context: Context, game: StoreGame, plan: EaZipInstaller.Plan, url: String, installDir: File) {
        val entry = plan.entries.firstOrNull { it.name.replace('\\', '/').equals(EaInstallerData.RELATIVE_PATH, ignoreCase = true) }
            ?: throw UnsupportedOperationException("no_installer_data")
        if (EaInstallerData.read(installDir) == null) {
            installDir.mkdirs()
            EaZipInstaller.install(
                plan = EaZipInstaller.Plan(plan.entries, listOf(entry)),
                installDir = installDir,
                url = url,
                refreshUrl = { downloadUrl(EaAuthManager.accessToken(context), game.productId, liveBuild(EaAuthManager.accessToken(context), game.productId)?.buildId.orEmpty()) },
                onBytes = {},
            )
        }
        if (EaInstallerData.read(installDir) == null) throw UnsupportedOperationException("pre_dip")
    }

    override fun executable(game: StoreGame, installDir: File): String? =
        (EaInstallerData.read(installDir)?.executable ?: meta(game).text("executePathOverride").ifEmpty { null })
            ?.let { EaInstallerData.relativePath(it) }

    // ── Launch ───────────────────────────────────────────────────────────────

    /** EA cloud save sessions are keyed by Int; store games use negative keys so they never meet a Steam app id. */
    private fun cloudKey(game: StoreGame) = -game.appId

    override suspend fun prepareLaunch(context: Context, container: Container, game: StoreGame, preferredSave: SaveLocation): StoreLaunchPrep {
        runCatching { EaHelperArchive.download(context) {} }.onFailure { return StoreLaunchPrep.Failed(it.message.orEmpty()) }
        EaLoginGate.ensureSignedIn(context).onFailure { return StoreLaunchPrep.Failed(it.message.orEmpty()) }
        if (container.isLocalSavesOnly) return StoreLaunchPrep.Ready
        val preference = when (preferredSave) {
            SaveLocation.Local -> EaCloudPreference.LOCAL
            SaveLocation.Remote -> EaCloudPreference.REMOTE
            SaveLocation.None -> EaCloudPreference.NONE
        }
        val pull = EaCloudSavesManager.syncBeforeLaunch(context, container, cloudKey(game), File(game.installPath), preference)
        Timber.i("EA cloud save pull for ${game.title}: $pull")
        return if (pull is EaCloudSavesManager.PullResult.Conflict) StoreLaunchPrep.SaveConflict(pull.localMillis, pull.remoteMillis) else StoreLaunchPrep.Ready
    }

    override fun launchCommand(context: Context, container: Container, game: StoreGame, gameDirWindows: String): StoreLaunch {
        val driveC = File(container.rootDir, ".wine/drive_c")
        EaHelperArchive.install(context, driveC)
        EaLaunchSupport.setupPrefix(container)
        val exe = container.executablePath.ifEmpty { executable(game, File(game.installPath)).orEmpty() }
        val session = EaLaunchSession(
            context = context.applicationContext,
            prefixDriveC = driveC,
            gameDir = File(game.installPath),
            gameDirWindows = gameDirWindows.trimEnd('\\'),
            exeRelativeWindows = exe.replace('/', '\\').trimStart('\\'),
            arguments = "",
            steamAppId = 0,
            language = container.language,
            entitlementSource = "EA",
            offerId = game.productId,
            offerContentId = meta(game).text("contentId"),
        )
        Timber.i("EA store launch ${game.title}: exe=${session.exeRelativeWindows} content=${session.contentId} locale=${session.locale}")
        EaLsxServer.start(session)
        return StoreLaunch("winhandler.exe \"${EaConstants.STUB_EXE}\" link2ea://launchgame/${game.productId}")
    }

    override fun firstRunCommand(container: Container, game: StoreGame, gameDir: File, gameDirWindows: String): String? {
        val data = EaInstallerData.read(gameDir) ?: return null
        val touchup = data.touchupPath?.ifEmpty { null } ?: return null
        val locale = ContainerUtils.mapLanguageToLocale(container.language).substringBefore('.')
        val root = gameDirWindows.trimEnd('\\')
        val args = EaInstallerData.touchupArguments(data.touchupParameters.orEmpty(), locale, root)
        return "\"$root\\${EaInstallerData.relativePath(touchup)}\" $args".trimEnd()
    }

    override suspend fun afterExit(context: Context, container: Container, game: StoreGame) {
        EaLaunchSupport.stop()
        if (!container.isLocalSavesOnly) EaCloudSavesManager.syncAfterExit(context, container, cloudKey(game))
    }
}
