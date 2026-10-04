package app.gamenative.ui.screen.support

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.dialog.openAccountUrl
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.screen.login.QrCodeImage
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.launch

private const val PHASE_CHOOSE = "choose"
private const val PHASE_LOADING = "loading"
private const val PHASE_CHECKOUT = "checkout"
private const val PHASE_SUBSCRIBED = "subscribed"
private const val PHASE_FAILED = "failed"

internal fun FocusRequester.requestFocusSafely(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

internal suspend fun FocusRequester.requestFocusAfterLayout() {
    if (requestFocusSafely()) return
    withFrameNanos { }
    requestFocusSafely()
}

private fun hasPaidTier(tier: String?): Boolean = tier == "basic" || tier == "pro" || tier == "patron"

@Composable
fun SupportUpgradeDialog(
    visible: Boolean,
    reason: String?,
    onDismiss: () -> Unit,
    onPlanChanged: () -> Unit = {},
    onCheckoutReturn: () -> Unit = {},
) {
    if (!visible) return
    if (!upgradeOffered(reason)) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPlanChanged by rememberUpdatedState(onPlanChanged)
    val currentOnCheckoutReturn by rememberUpdatedState(onCheckoutReturn)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val account by AccountApi.account
    var phase by rememberSaveable { mutableStateOf(PHASE_CHOOSE) }
    var checkoutUrl by rememberSaveable { mutableStateOf("") }
    var startTier by rememberSaveable { mutableStateOf(account?.tier ?: "") }
    var noBrowser by remember { mutableStateOf(false) }
    var portalMessage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        if (startTier.isEmpty()) {
            val result = AccountApi.fetchAccount()
            if (result is ApiResult.Success) startTier = result.data.tier
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (phase == PHASE_CHECKOUT || phase == PHASE_SUBSCRIBED)) {
                scope.launch {
                    val result = AccountApi.fetchAccount()
                    currentOnCheckoutReturn()
                    if (result is ApiResult.Success && result.data.tier != startTier && hasPaidTier(result.data.tier)) {
                        SnackbarManager.show(context.getString(R.string.support_plan_active))
                        currentOnPlanChanged()
                        currentOnDismiss()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val choose: (String) -> Unit = { tier ->
        phase = PHASE_LOADING
        noBrowser = false
        scope.launch {
            when (val result = AccountApi.createCheckout(tier)) {
                is ApiResult.Success -> {
                    checkoutUrl = result.data
                    phase = PHASE_CHECKOUT
                }
                is ApiResult.HttpError -> phase = if (result.code == 409) PHASE_SUBSCRIBED else PHASE_FAILED
                is ApiResult.NetworkError -> phase = PHASE_FAILED
            }
        }
    }

    val manage: () -> Unit = {
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
                is ApiResult.HttpError -> if (result.code == 409) {
                    R.string.gamenative_account_manage_subscription_none
                } else {
                    R.string.gamenative_account_manage_subscription_failed
                }
                is ApiResult.NetworkError -> R.string.gamenative_account_manage_subscription_failed
            }
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val firstFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val showBasic = !SupportApi.isFairUse(reason) && account?.tier != "basic"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    ) {
        LaunchedEffect(phase) {
            if (phase == PHASE_LOADING) {
                cancelFocus.requestFocusAfterLayout()
            } else {
                firstFocus.requestFocusAfterLayout()
            }
        }

        Surface(
            modifier = Modifier
                .then(if (landscape) Modifier.widthIn(max = 680.dp).padding(16.dp) else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            if (landscape && phase == PHASE_CHECKOUT) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckoutQr(url = checkoutUrl, size = 180.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        UpgradeBody(
                            phase = phase,
                            reason = reason,
                            showBasic = showBasic,
                            noBrowser = noBrowser,
                            portalMessage = portalMessage,
                            firstFocus = firstFocus,
                            cancelFocus = cancelFocus,
                            onChoose = choose,
                            onOpenCheckout = { noBrowser = !openAccountUrl(context, checkoutUrl) },
                            onManage = manage,
                            onRetry = { phase = PHASE_CHOOSE },
                            onDismiss = onDismiss,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (phase == PHASE_CHECKOUT) {
                        CheckoutQr(url = checkoutUrl, size = 200.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    UpgradeBody(
                        phase = phase,
                        reason = reason,
                        showBasic = showBasic,
                        noBrowser = noBrowser,
                        portalMessage = portalMessage,
                        firstFocus = firstFocus,
                        cancelFocus = cancelFocus,
                        onChoose = choose,
                        onOpenCheckout = { noBrowser = !openAccountUrl(context, checkoutUrl) },
                        onManage = manage,
                        onRetry = { phase = PHASE_CHOOSE },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckoutQr(url: String, size: Dp) {
    Box(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                background = Color.White,
                onBackground = Color.Black,
            ),
        ) {
            QrCodeImage(content = url, size = size)
        }
    }
}

internal fun upgradeOffered(reason: String?): Boolean = !SupportApi.isFairUse(reason)

@Composable
internal fun fairUseReasonText(hours: Int?, fallback: String? = null): String {
    val account by AccountApi.account
    val text = when {
        hours != null -> pluralStringResource(R.plurals.support_fair_use_resets_in_hours, hours, hours)
        fallback != null -> fallback
        else -> stringResource(R.string.support_upgrade_reason_reply_cap)
    }
    return if (account?.tier == "basic") {
        text + " " + stringResource(R.string.support_upgrade_reply_cap_pro_hint)
    } else {
        text
    }
}

@Composable
internal fun upgradeReasonText(reason: String?, resetsAt: Long? = null): String {
    if (SupportApi.isFairUse(reason)) {
        return fairUseReasonText(SupportApi.FairUse(resetsAt, null).hoursLeft())
    }
    return stringResource(
        when (reason) {
            SupportApi.REASON_TRIAL_USED -> R.string.support_upgrade_reason_trial_used
            else -> R.string.support_upgrade_reason_required
        },
    )
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    val buttonModifier = modifier
        .fillMaxWidth()
        .focusRing(interaction, shape, width = 2.dp)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = shape,
            colors = supportButtonColors(),
            modifier = buttonModifier,
        ) {
            content()
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = interaction, shape = shape, modifier = buttonModifier) {
            content()
        }
    }
}

@Composable
private fun UpgradeBody(
    phase: String,
    reason: String?,
    showBasic: Boolean,
    noBrowser: Boolean,
    portalMessage: Int?,
    firstFocus: FocusRequester,
    cancelFocus: FocusRequester,
    onChoose: (String) -> Unit,
    onOpenCheckout: () -> Unit,
    onManage: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.debug_paywall_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        when (phase) {
            PHASE_CHOOSE, PHASE_LOADING -> {
                Text(text = upgradeReasonText(reason), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.debug_paywall_pitch),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                if (phase == PHASE_LOADING) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else {
                    if (showBasic) {
                        DialogButton(
                            text = stringResource(R.string.support_plan_basic_title),
                            subtitle = stringResource(R.string.support_plan_basic_body),
                            primary = false,
                            onClick = { onChoose("basic") },
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    DialogButton(
                        text = stringResource(R.string.debug_paywall_tier10_title),
                        subtitle = stringResource(R.string.debug_paywall_tier10_body),
                        onClick = { onChoose("pro") },
                        modifier = if (showBasic) Modifier else Modifier.focusRequester(firstFocus),
                    )
                    Text(
                        text = stringResource(R.string.debug_paywall_cancel_anytime) + " " +
                            stringResource(R.string.debug_paywall_costs_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            PHASE_CHECKOUT -> {
                Text(text = stringResource(R.string.support_checkout_scan), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.support_checkout_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                if (noBrowser) {
                    Text(
                        text = stringResource(R.string.support_checkout_no_browser),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.accentDanger,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                DialogButton(
                    text = stringResource(R.string.support_checkout_open),
                    onClick = onOpenCheckout,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            PHASE_SUBSCRIBED -> {
                Text(
                    text = stringResource(R.string.support_already_subscribed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                if (portalMessage != null) {
                    Text(
                        text = stringResource(portalMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                DialogButton(
                    text = stringResource(R.string.gamenative_account_manage_subscription),
                    onClick = onManage,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.support_checkout_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                DialogButton(
                    text = stringResource(R.string.gamenative_sign_in_try_again),
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        val cancelInteraction = remember { MutableInteractionSource() }
        TextButton(
            onClick = onDismiss,
            interactionSource = cancelInteraction,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(cancelFocus)
                .focusRing(cancelInteraction, RoundedCornerShape(12.dp), width = 2.dp),
        ) {
            Text(stringResource(if (phase == PHASE_CHECKOUT) R.string.support_checkout_done else R.string.cancel))
        }
    }
}
