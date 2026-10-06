package app.gamenative.service.rockstar

import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.SteamAuthTicket
import `in`.dragonbra.javasteam.steam.handlers.steamauthticket.TicketInfo
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import timber.log.Timber

/**
 * The Steam identity the Rockstar sign-in page needs to link the Steam account to the Rockstar
 * account on a first launch. The launcher sends it inside OnStartAuth as
 * titleLaunchInfo.externalPlatformInfo.steam = {steamAppId, steamId, steamPersona, steamAuthTicket}.
 *
 * The ticket comes from the app's own Steam session, minted for the game's app id so its
 * ownership section names the game. Tickets are single use and short lived, so one is minted
 * per sign-in window and cancelled when that window closes; the stub mints its own later for
 * the entitlement mirror.
 */
class RockstarSteamTicket private constructor(
    private val info: TicketInfo,
    val steamId: Long,
    val persona: String,
    val appId: Int,
) : AutoCloseable {

    val hex: String = info.ticket.joinToString("") { "%02X".format(it) }

    fun externalPlatformInfo(): JSONObject = JSONObject()
        .put("steamAppId", appId)
        .put("steamId", steamId.toString())
        .put("steamPersona", persona)
        .put("steamAuthTicket", hex)

    override fun close() {
        runCatching { info.close() }
    }

    companion object {
        suspend fun mint(appId: Int): RockstarSteamTicket? = withContext(Dispatchers.IO) {
            val client = SteamService.instance?.steamClient ?: run {
                Timber.w("Rockstar sign-in: no Steam client, cannot mint a ticket for app %d", appId)
                return@withContext null
            }
            val steamId = client.steamID?.takeIf { it.isValid }?.convertToUInt64() ?: run {
                Timber.w("Rockstar sign-in: Steam client not logged on, cannot mint a ticket for app %d", appId)
                return@withContext null
            }
            runCatching {
                val ticket = withTimeout(20_000) {
                    client.getHandler(SteamAuthTicket::class.java)!!.getAuthSessionTicket(appId).await()
                }
                val persona = client.getHandler(SteamFriends::class.java)?.getPersonaName().orEmpty()
                RockstarSteamTicket(ticket, steamId, persona, appId)
            }.onFailure { Timber.w(it, "Rockstar sign-in: no Steam ticket for app %d", appId) }.getOrNull()
        }
    }
}
