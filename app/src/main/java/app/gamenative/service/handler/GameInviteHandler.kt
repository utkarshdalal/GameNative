package app.gamenative.service.handler

import app.gamenative.service.callback.GameInviteCallback
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg

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
        val callback = getCallback(packetMsg) ?: return

        client.postCallback(callback)
    }
}
