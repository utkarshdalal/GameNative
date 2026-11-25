package app.gamenative.service.rockstar

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import app.gamenative.R
import app.gamenative.service.epic.EpicAuthManager
import app.gamenative.service.epic.EpicService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/** A store identity minted for the sign-in page: the account and the proof it owns the title. */
interface RockstarStoreTicket : AutoCloseable {
    val accountName: String

    /** The block the page sends as externalPlatformInfo.<platform>. */
    fun externalPlatformInfo(): JSONObject

    override fun close() {}
}

/**
 * The store a Rockstar title was bought on. The Rockstar account is linked to that store's
 * account, and the sign-in page validates the title against it; Social Club carries one block
 * per store in externalPlatformInfo.
 */
sealed class RockstarStore(val platform: String) {
    abstract fun currentAccountName(context: Context): String?

    abstract suspend fun mint(context: Context): RockstarStoreTicket?

    abstract val texts: Texts

    open fun isLinkedService(onlineService: String) = onlineService.equals(platform, ignoreCase = true)

    fun linkScript(loginGuid: String, ticket: RockstarStoreTicket) =
        script("gnLinkExternal", "gnLinkSteam", "onLinkResult", loginGuid, ticket)

    fun validateScript(loginGuid: String, ticket: RockstarStoreTicket) =
        script("gnValidateExternal", "gnValidateSteam", "onValidateResult", loginGuid, ticket)

    /* Helper archives before the store-neutral calls only know the Steam ones. */
    private fun script(call: String, steamCall: String, report: String, loginGuid: String, ticket: RockstarStoreTicket): String {
        val guid = JSONObject.quote(loginGuid)
        val json = JSONObject.quote(ticket.externalPlatformInfo().toString())
        if (this is Steam) return "window.$steamCall($guid, $json)"
        val bridge = RockstarSignInShim.BRIDGE
        return "window.$call ? window.$call($guid, ${JSONObject.quote(platform)}, $json) : $bridge.$report(0, 'helper too old')"
    }

    open fun putInto(intent: Intent): Intent = intent

    class Texts(
        @StringRes val refused: Int,
        @StringRes val title: Int,
        @StringRes val text: Int,
        @StringRes val done: Int,
        @StringRes val failed: Int,
        @StringRes val unchecked: Int,
    )

    class Steam(val appId: Int) : RockstarStore("steam") {
        override fun currentAccountName(context: Context) = RockstarSteamTicket.persona()

        override suspend fun mint(context: Context) = RockstarSteamTicket.mint(appId)

        override val texts = Texts(
            R.string.rockstar_link_refused, R.string.rockstar_link_title, R.string.rockstar_link_text,
            R.string.rockstar_link_done, R.string.rockstar_link_failed, R.string.rockstar_link_unchecked,
        )

        override fun putInto(intent: Intent): Intent = intent.putExtra(RockstarConstants.STEAM_APP_ID_EXTRA, appId)
    }

    /**
     * An Epic copy. The page's block is {epicProductId, epicSandboxId, epicAccountId,
     * epicPlayerName, epicPlayerJwt}; the JWT is the account's token for the title's own Epic
     * client, which the title's metadata names.
     */
    class Epic(val gameId: Int) : RockstarStore("epic") {
        override fun currentAccountName(context: Context) = EpicAuthManager.loadCredentials(context)?.displayName

        override suspend fun mint(context: Context): RockstarStoreTicket? = withContext(Dispatchers.IO) {
            val game = EpicService.getInstance()?.epicManager?.getGameById(gameId)?.takeIf { it.installPath.isNotEmpty() }
                ?: return@withContext null
            val client = RockstarTitleMetadata.find(File(game.installPath))?.let { RockstarTitleMetadata.parse(it) }?.epic ?: run {
                Timber.w("Rockstar sign-in: the title's metadata names no Epic client")
                return@withContext null
            }
            val deploymentId = client.deploymentId.ifEmpty {
                EpicService.getInstance()?.epicManager?.fetchDeploymentId(context, game.namespace, game.catalogId, game.appName).orEmpty()
            }
            EpicAuthManager.gameClientToken(context, client.clientId, client.clientSecret, deploymentId)
                .onFailure { Timber.w("Rockstar sign-in: no Epic token for the title (%s)", it.message) }
                .getOrNull()
                ?.let { token ->
                    object : RockstarStoreTicket {
                        override val accountName = token.displayName

                        override fun externalPlatformInfo(): JSONObject = JSONObject()
                            .put("epicProductId", client.productId ?: game.catalogId)
                            .put("epicSandboxId", game.namespace)
                            .put("epicAccountId", token.accountId)
                            .put("epicPlayerName", token.displayName)
                            .put("epicPlayerJwt", token.accessToken)
                    }
                }
        }

        override val texts = Texts(
            R.string.rockstar_epic_link_refused, R.string.rockstar_epic_link_title, R.string.rockstar_epic_link_text,
            R.string.rockstar_epic_link_done, R.string.rockstar_epic_link_failed, R.string.rockstar_epic_link_unchecked,
        )

        override fun isLinkedService(onlineService: String) =
            onlineService.replace(" ", "").lowercase() in setOf("epic", "epicgames", "egs")

        override fun putInto(intent: Intent): Intent = intent.putExtra(RockstarConstants.EPIC_GAME_ID_EXTRA, gameId)
    }

    companion object {
        fun from(intent: Intent): RockstarStore? {
            intent.getIntExtra(RockstarConstants.STEAM_APP_ID_EXTRA, 0).takeIf { it > 0 }?.let { return Steam(it) }
            intent.getIntExtra(RockstarConstants.EPIC_GAME_ID_EXTRA, 0).takeIf { it > 0 }?.let { return Epic(it) }
            return null
        }

        /** Install directories of the Epic copies in the library. */
        suspend fun epicInstallDirs(): List<File> =
            EpicService.getInstance()?.epicManager?.getInstalledGames().orEmpty()
                .mapNotNull { game -> game.installPath.takeIf { it.isNotEmpty() }?.let(::File) }
    }
}
