package app.gamenative.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CreditCard
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
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.component.dialog.openAccountUrl
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import kotlinx.coroutines.launch

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

    LaunchedEffect(Unit) {
        AccountApi.loadSignedInState()
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
