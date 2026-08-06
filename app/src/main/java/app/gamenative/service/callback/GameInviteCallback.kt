package app.gamenative.service.callback

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver.CMsgClientInviteToGame
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg

/**
 * A friend inviting us into their game session. Steam delivers this to every logged-in session
 * on the account, so it arrives here even though the running game talks to the separate bionic
 * Steam client.
 *
 * There is no app id on the wire; the connect string is the payload the game understands, and
 * for lobby-based games it carries the lobby id as "+connect_lobby <id>".
 */
class GameInviteCallback(packetMsg: IPacketMsg) : CallbackMsg() {

    val inviterSteamId: Long
    val connectString: String

    init {
        val resp = ClientMsgProtobuf<CMsgClientInviteToGame.Builder>(
            CMsgClientInviteToGame::class.java,
            packetMsg,
        )
        jobID = resp.targetJobID

        inviterSteamId = resp.body.steamIdSrc
        connectString = resp.body.connectString.orEmpty()
    }
}
