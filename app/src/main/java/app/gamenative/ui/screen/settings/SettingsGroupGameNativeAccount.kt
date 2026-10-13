package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.api.DebugReportApi
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.component.dialog.DiscordLinkDialog
import app.gamenative.ui.component.dialog.openAccountUrl
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsGroupGameNativeAccount() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val signedIn by PrefManager.gameNativeSignedIn
    val account by AccountApi.account
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var portalBusy by remember { mutableStateOf(false) }
    var portalMessage by remember { mutableStateOf<Int?>(null) }
    val discordConnected by PrefManager.discordRelayTokenPresent
    var discordLinkedName by remember { mutableStateOf("") }
    var showDiscordLink by rememberSaveable { mutableStateOf(false) }

    val openDiscordLinkHere: suspend () -> Boolean = {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        PrefManager.discordOauthNonce = nonce
        val accountUrl = if (signedIn) {
            when (val result = SupportApi.startDiscordLink(nonce)) {
                is ApiResult.Success -> result.data
                else -> null
            }
        } else {
            null
        }
        openAccountUrl(context, accountUrl ?: "${DebugReportApi.OAUTH_START_URL}?app_state=$nonce")
    }

    LaunchedEffect(Unit) {
        AccountApi.loadSignedInState()
    }

    LaunchedEffect(discordConnected) {
        withContext(Dispatchers.IO) {
            PrefManager.discordRelayTokenPresent.value = PrefManager.discordRelayToken.isNotEmpty()
            discordLinkedName = PrefManager.discordLinkedName
        }
    }

    LaunchedEffect(signedIn, discordConnected) {
        if (signedIn && discordConnected && withContext(Dispatchers.IO) { PrefManager.discordMergePending }) {
            if (SupportApi.listConversations() is ApiResult.Success) AccountApi.fetchAccount()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadAttempt++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(signedIn, loadAttempt) {
        if (signedIn) {
            loadFailed = false
            loadFailed = AccountApi.fetchAccount() !is ApiResult.Success
        }
    }

    AccountSignInDialog(
        visible = showSignIn,
        onSignedIn = { showSignIn = false },
        onDismiss = { showSignIn = false },
    )

    DiscordLinkDialog(
        visible = showDiscordLink,
        signedIn = signedIn,
        onOpenHere = openDiscordLinkHere,
        onDismiss = { showDiscordLink = false },
    )

    SettingsGroup {
        if (!signedIn) {
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.gamenative_account_sign_in)) },
                subtitle = { Text(stringResource(R.string.gamenative_account_sign_in_subtitle)) },
                icon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                onClick = { showSignIn = true },
            )
        } else {
            val current = account
            val managedElsewhere = managedElsewhereMessage(current?.tierSource)
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(current?.email?.ifBlank { null } ?: stringResource(R.string.gamenative_account_title)) },
                subtitle = {
                    Text(
                        when {
                            current != null -> stringResource(R.string.gamenative_account_plan, tierLabel(current.tier))
                            loadFailed -> stringResource(R.string.gamenative_account_load_failed)
                            else -> stringResource(R.string.gamenative_account_loading)
                        },
                    )
                },
                icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                onClick = {
                    if (current == null && loadFailed) loadAttempt++
                },
            )

            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.gamenative_account_manage_subscription)) },
                subtitle = {
                    Text(
                        stringResource(
                            portalMessage ?: managedElsewhere ?: R.string.gamenative_account_manage_subscription_subtitle,
                        ),
                    )
                },
                icon = { Icon(Icons.Filled.CreditCard, contentDescription = null) },
                enabled = !portalBusy,
                onClick = {
                    portalBusy = true
                    portalMessage = R.string.gamenative_account_manage_subscription_opening
                    scope.launch {
                        portalMessage = when (val result = AccountApi.createPortal()) {
                            is ApiResult.Success -> {
                                if (openAccountUrl(context, result.data)) {
                                    null
                                } else {
                                    R.string.gamenative_account_manage_subscription_failed
                                }
                            }
                            is ApiResult.HttpError -> when {
                                result.code != 409 -> R.string.gamenative_account_manage_subscription_failed
                                managedElsewhere != null -> managedElsewhere
                                else -> R.string.gamenative_account_manage_subscription_none
                            }
                            is ApiResult.NetworkError -> R.string.gamenative_account_manage_subscription_failed
                        }
                        portalBusy = false
                    }
                },
            )

            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(stringResource(R.string.gamenative_account_sign_out)) },
                subtitle = { Text(stringResource(R.string.gamenative_account_sign_out_subtitle)) },
                icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = {
                    portalMessage = null
                    loadFailed = false
                    scope.launch { AccountApi.signOut() }
                },
            )
        }

        val discordName = account?.discordName ?: discordLinkedName.ifBlank { null }
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.gamenative_account_discord)) },
            subtitle = {
                Text(
                    when {
                        !discordConnected -> stringResource(R.string.gamenative_account_discord_not_connected)
                        discordName != null -> stringResource(R.string.gamenative_account_discord_connected_as, discordName)
                        else -> stringResource(R.string.gamenative_account_discord_connected)
                    },
                )
            },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            onClick = {
                if (discordConnected) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PrefManager.discordRelayToken = ""
                            PrefManager.discordLinkedName = ""
                            PrefManager.discordMergePending = false
                        }
                        discordLinkedName = ""
                    }
                } else {
                    showDiscordLink = true
                }
            },
        )
    }
}

private fun managedElsewhereMessage(tierSource: String?): Int? =
    when (tierSource) {
        "discord" -> R.string.gamenative_account_managed_discord
        "kofi" -> R.string.gamenative_account_managed_kofi
        else -> null
    }

@Composable
private fun tierLabel(tier: String): String =
    when (tier) {
        "none" -> stringResource(R.string.gamenative_account_plan_none)
        "basic" -> stringResource(R.string.gamenative_account_plan_basic)
        "pro" -> stringResource(R.string.gamenative_account_plan_pro)
        "patron" -> stringResource(R.string.gamenative_account_plan_patron)
        else -> tier.replaceFirstChar { it.uppercase() }
    }
