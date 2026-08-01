package app.gamenative.steam

import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_AddToWishlist_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_AddToWishlist_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_RemoveFromWishlist_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesWishlistSteamclient.CWishlist_RemoveFromWishlist_Response
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.types.AsyncJobSingle

/**
 * Minimal JavaSteam unified-messages stub for the `Wishlist` service, following
 * [CloudConfigStoreService]: JavaSteam ships no generated stub, and replies are routed by service
 * name through a map that only [SteamUnifiedMessages.createService] populates, so the service must
 * be registered or the reply is dropped and the job times out.
 *
 * Upstream defines Wishlist as a WebUI service rather than a `.steamclient` one, so whether the CM
 * routes these methods at all is answered by the [ServiceMethodResponse.getResult] of the first call.
 */
class WishlistService(
    unifiedMessages: SteamUnifiedMessages,
) : UnifiedService(unifiedMessages) {

    override val serviceName: String = "Wishlist"

    fun addToWishlist(
        request: CWishlist_AddToWishlist_Request,
    ): AsyncJobSingle<ServiceMethodResponse<CWishlist_AddToWishlist_Response.Builder>> =
        unifiedMessages!!.sendMessage(
            CWishlist_AddToWishlist_Response.Builder::class.java,
            "Wishlist.AddToWishlist#1",
            request,
        )

    fun removeFromWishlist(
        request: CWishlist_RemoveFromWishlist_Request,
    ): AsyncJobSingle<ServiceMethodResponse<CWishlist_RemoveFromWishlist_Response.Builder>> =
        unifiedMessages!!.sendMessage(
            CWishlist_RemoveFromWishlist_Response.Builder::class.java,
            "Wishlist.RemoveFromWishlist#1",
            request,
        )

    override fun handleResponseMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        when (methodName) {
            "AddToWishlist" -> postResponseMsg<CWishlist_AddToWishlist_Response.Builder>(
                CWishlist_AddToWishlist_Response::class.java,
                packetMsg,
            )
            "RemoveFromWishlist" -> postResponseMsg<CWishlist_RemoveFromWishlist_Response.Builder>(
                CWishlist_RemoveFromWishlist_Response::class.java,
                packetMsg,
            )
        }
    }

    override fun handleNotificationMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        // Wishlist has no notifications we consume.
    }
}
