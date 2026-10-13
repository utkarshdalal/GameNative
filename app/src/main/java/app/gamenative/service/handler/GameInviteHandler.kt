package app.gamenative.service.handler

import app.gamenative.service.callback.GameInviteCallback
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import timber.log.Timber

/**
 * Dispatches game-invite messages, which JavaSteam has protobufs for but no handler.
 */
class GameInviteHandler : ClientMsgHandler() {

    companion object {
        fun getCallback(packetMsg: IPacketMsg): CallbackMsg? = when (packetMsg.msgType) {
            EMsg.ClientInviteToGame -> GameInviteCallback(packetMsg)
            else -> null
        }
    }

    override fun handleMsg(packetMsg: IPacketMsg) {
        // A parse failure would otherwise propagate into JavaSteam's dispatch loop, so an
        // unexpected message would take down the whole Steam session for an optional feature.
        val callback = try {
            getCallback(packetMsg)
        } catch (e: Exception) {
            Timber.w(e, "GameInviteHandler: could not parse ${packetMsg.msgType}")
            null
        } ?: return

        client.postCallback(callback)
    }
}
