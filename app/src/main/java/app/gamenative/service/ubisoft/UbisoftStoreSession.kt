package app.gamenative.service.ubisoft

import java.io.Closeable

/**
 * The store side of the demux: owned products, ownership tokens, and signed download URLs. One
 * request at a time. A failed call drops the connection, reconnects and is tried once more; the
 * download service is initialized again with a fresh token whenever the old one is close to expiry.
 */
class UbisoftStoreSession(
    private val creds: UbisoftCredentials,
    private val ownershipService: String,
    private val downloadService: String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val connect: () -> UbisoftDemuxConnection,
) : Closeable {
    private var demux: UbisoftDemuxConnection? = null
    private val connections = HashMap<String, Long>()
    private val requestIds = HashMap<Long, Long>()
    private val tokens = HashMap<Long, UbisoftDemuxProto.OwnershipToken>()
    private var downloadProduct = 0L
    private var owned: List<UbisoftOwnedGame>? = null

    /** The ownership service is initialized once per connection; a new connection has to do it again. */
    private var ownershipReady = false

    @Synchronized
    fun ownedGames(): List<UbisoftOwnedGame> = owned?.takeIf { ownershipReady } ?: retrying {
        val rsp = call(ownershipService) { id -> UbisoftDemuxProto.ownershipInitialize(id, creds.ticket, creds.sessionId) }
        (UbisoftDemuxProto.ownedGames(rsp) ?: throw UbisoftCloudException("owned games: initialize failed"))
            .also { owned = it; ownershipReady = true }
    }

    /** The configuration of a product the owned list sent without one. */
    @Synchronized
    fun productConfig(productId: Long): String? = retrying {
        ownedGames()
        val rsp = call(ownershipService) { id -> UbisoftDemuxProto.productConfigRequest(id, productId, creds.ticket, creds.sessionId) }
        UbisoftDemuxProto.productConfigResult(rsp)
    }

    /** Candidate URLs for each relative path of [productId], in order. */
    @Synchronized
    fun urls(productId: Long, paths: List<String>): List<List<String>> = retrying {
        ensureDownload(productId)
        val rsp = call(downloadService) { id -> UbisoftDemuxProto.downloadUrls(id, productId, paths) }
        val answer = UbisoftDemuxProto.downloadUrlsResult(rsp) ?: throw UbisoftCloudException("download: no URL answer")
        if (!answer.owned) throw UbisoftCloudException("download: product $productId is not owned", 403)
        if (answer.urls.size != paths.size) throw UbisoftCloudException("download: ${answer.urls.size} URL sets for ${paths.size} paths")
        answer.urls
    }

    private fun ensureDownload(productId: Long) {
        val token = tokens[productId]
        if (downloadProduct == productId && token != null && !expiring(token)) return
        // The download service is initialized once per connection, so another product or a new token starts a new one.
        if (downloadProduct != 0L) reset()
        val fresh = token?.takeUnless { expiring(it) } ?: ownershipToken(productId)
        val rsp = call(downloadService) { id -> UbisoftDemuxProto.downloadInitialize(id, fresh.token) }
        if (!UbisoftDemuxProto.downloadInitializeResult(rsp)) throw UbisoftCloudException("download: the service refused the ownership token", 403)
        downloadProduct = productId
    }

    private fun ownershipToken(productId: Long): UbisoftDemuxProto.OwnershipToken {
        ownedGames()
        val rsp = call(ownershipService) { id -> UbisoftDemuxProto.ownershipToken(id, productId, creds.ticket, creds.sessionId) }
        val token = UbisoftDemuxProto.ownershipTokenWithExpiry(rsp)
            ?: throw UbisoftCloudException("ownership token refused for product $productId", 403)
        tokens[productId] = token
        return token
    }

    private fun expiring(token: UbisoftDemuxProto.OwnershipToken) =
        token.expiresAtSeconds != 0L && token.expiresAtSeconds - nowSeconds() < TOKEN_MARGIN_S

    private fun <T> retrying(block: () -> T): T = try {
        block()
    } catch (e: UbisoftCloudException) {
        if (e.httpStatus in 400..499) throw e
        reset()
        block()
    } catch (e: java.io.IOException) {
        reset()
        block()
    }

    private fun call(service: String, build: (Long) -> ByteArray): UbisoftDemuxProto.Message {
        try {
            val link = demux ?: connect().also { demux = it }
            val connection = connections[service] ?: link.openService(service).also { connections[service] = it }
            val id = requestIds[connection] ?: 1L
            requestIds[connection] = id + 1
            return UbisoftDemuxProto.serviceResponse(link.exchange(connection, build(id)), id)
                ?: throw UbisoftCloudException("demux: $service answered another request")
        } catch (e: Exception) {
            reset()
            throw e
        }
    }

    private fun reset() {
        demux?.let { runCatching { it.close() } }
        demux = null
        connections.clear()
        requestIds.clear()
        downloadProduct = 0L
        ownershipReady = false
    }

    @Synchronized
    override fun close() = reset()

    private companion object {
        const val TOKEN_MARGIN_S = 120L
    }
}
