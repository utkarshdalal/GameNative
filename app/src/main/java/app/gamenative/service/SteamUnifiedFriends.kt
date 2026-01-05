package app.gamenative.service

import app.gamenative.data.OwnedGames
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import timber.log.Timber

// TODO this class has a single method, it could be merged into SteamService.kt
class SteamUnifiedFriends(service: SteamService) : AutoCloseable {

    private var unifiedMessages: SteamUnifiedMessages? = null

    private var player: Player? = null

    init {
        unifiedMessages = service.steamClient!!.getHandler<SteamUnifiedMessages>()

        player = unifiedMessages!!.createService(Player::class.java)
    }

    override fun close() {
        unifiedMessages = null
        player = null
    }

    /**
     * Gets a list of games that the user owns. If the library is private, it will be empty.
     */
    suspend fun getOwnedGames(steamID: Long): List<OwnedGames> {
        val request = SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request.newBuilder().apply {
            steamid = steamID
            includePlayedFreeGames = true
            includeFreeSub = true
            includeAppinfo = true
            includeExtendedAppinfo = true
        }.build()

        val result = player?.getOwnedGames(request)?.await()

        if (result == null || result.result != EResult.OK) {
            Timber.w("Unable to get owned games!")
            return emptyList()
        }

        val list = result.body.gamesList.map { game ->
            OwnedGames(
                appId = game.appid,
                name = game.name,
                playtimeTwoWeeks = game.playtime2Weeks,
                playtimeForever = game.playtimeForever,
                imgIconUrl = game.imgIconUrl,
                sortAs = game.sortAs,
                rtimeLastPlayed = game.rtimeLastPlayed,
            )
        }

        if (list.size != result.body.gamesCount) {
            Timber.w("List was not the same as given")
        }

        return list
    }
}
