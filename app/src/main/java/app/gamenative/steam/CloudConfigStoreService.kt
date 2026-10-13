package app.gamenative.steam

import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_Download_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_Download_Response
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.types.AsyncJobSingle

/**
 * Minimal JavaSteam unified-messages stub for the `CloudConfigStore` service.
 *
 * JavaSteam does not ship a generated stub for this service, and its generic
 * [SteamUnifiedMessages.sendMessage] cannot receive replies on its own: incoming
 * `ServiceMethodResponse` packets are routed by service name through the `handlers` map, which is
 * populated exclusively by [SteamUnifiedMessages.createService]. Without a registered service whose
 * [serviceName] is "CloudConfigStore", the download reply is dropped and the job times out.
 *
 * This stub registers that name and forwards the "Download" reply to the correct protobuf type,
 * exactly like the generated services (e.g. Cloud, FamilyGroups).
 */
class CloudConfigStoreService(
    unifiedMessages: SteamUnifiedMessages,
) : UnifiedService(unifiedMessages) {

    override val serviceName: String = "CloudConfigStore"

    fun download(
        request: CCloudConfigStore_Download_Request,
    ): AsyncJobSingle<ServiceMethodResponse<CCloudConfigStore_Download_Response.Builder>> =
        unifiedMessages!!.sendMessage(
            CCloudConfigStore_Download_Response.Builder::class.java,
            "CloudConfigStore.Download#1",
            request,
        )

    override fun handleResponseMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        when (methodName) {
            "Download" -> postResponseMsg<CCloudConfigStore_Download_Response.Builder>(
                CCloudConfigStore_Download_Response::class.java,
                packetMsg,
            )
        }
    }

    override fun handleNotificationMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        // CloudConfigStore has no notifications we consume.
    }
}
