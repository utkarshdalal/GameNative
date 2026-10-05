package app.gamenative.launch

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.SteamAgreementDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

object SteamAgreementGate {

    private const val SSA_URL = "https://store.steampowered.com/subscriber_agreement/"
    private const val SSA_CHECK_TIMEOUT_MS = 5_000L

    private class Request(
        val title: String,
        val url: String,
        val result: CompletableDeferred<Boolean>,
    )

    @Volatile
    private var ssaHandled = false

    private var request by mutableStateOf<Request?>(null)

    suspend fun confirm(
        context: Context,
        appId: Int,
        isOffline: Boolean,
        setLoadingDialogVisible: (Boolean) -> Unit,
    ): Boolean {
        if (!ssaHandled && !isOffline && SteamService.isConnected && SteamService.isLoggedIn) {
            val state = withTimeoutOrNull(SSA_CHECK_TIMEOUT_MS) { SteamService.getSteamAgreementState() }
            if (state != null && !state.needsAcceptance) {
                ssaHandled = true
            } else if (state != null) {
                setLoadingDialogVisible(false)
                if (!ask(context.getString(R.string.steam_agreement_ssa_title), SSA_URL)) return false
                setLoadingDialogVisible(true)
                if (!SteamService.acceptSteamAgreement()) {
                    Timber.w("Steam Subscriber Agreement acceptance failed, continuing launch")
                }
                ssaHandled = true
            }
        }

        val app = SteamService.getAppInfoOf(appId) ?: return true
        val pending = PrefManager.getPendingSteamEulas(app)
        if (pending.isEmpty()) return true
        setLoadingDialogVisible(false)
        for (eula in pending) {
            val title = eula.name.ifBlank { context.getString(R.string.steam_agreement_eula_title) }
            if (!ask(title, eula.url)) return false
            PrefManager.markSteamEulasAccepted(listOf(eula))
        }
        setLoadingDialogVisible(true)
        return true
    }

    private suspend fun ask(title: String, url: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        request = Request(title, url, result)
        return try {
            result.await()
        } finally {
            request = null
        }
    }

    @Composable
    fun Prompt() {
        val current = request ?: return
        key(current) {
            SteamAgreementDialog(
                title = current.title,
                url = current.url,
                onAccept = { current.result.complete(true) },
                onDecline = { current.result.complete(false) },
            )
        }
    }
}
