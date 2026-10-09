package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.ApiResult
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.screen.login.QrCodeImage
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.trackAiDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private sealed class DiscordLinkState {
    data object Starting : DiscordLinkState()
    data class Waiting(val link: SupportApi.DiscordLinkCode) : DiscordLinkState()
    data class Connected(val name: String?) : DiscordLinkState()
    data object Expired : DiscordLinkState()
    data object Failed : DiscordLinkState()
}

private fun deviceLabel(): String {
    val maker = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return if (maker.isEmpty() || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
}

private fun FocusRequester.tryFocus(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

@Composable
fun DiscordLinkDialog(
    visible: Boolean,
    signedIn: Boolean,
    onOpenHere: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    val tokenPresent by PrefManager.discordRelayTokenPresent
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DiscordLinkState>(DiscordLinkState.Starting) }
    var noBrowser by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(tokenPresent) {
        if (tokenPresent && state !is DiscordLinkState.Connected) {
            state = DiscordLinkState.Connected(withContext(Dispatchers.IO) { PrefManager.discordLinkedName }.ifBlank { null })
        }
    }

    LaunchedEffect(attempt) {
        if (state is DiscordLinkState.Connected) return@LaunchedEffect
        noBrowser = false
        state = DiscordLinkState.Starting
        val link = when (val result = SupportApi.createDiscordLinkCode(deviceLabel(), signedIn)) {
            is ApiResult.Success -> result.data
            else -> {
                state = DiscordLinkState.Failed
                return@LaunchedEffect
            }
        }
        state = DiscordLinkState.Waiting(link)
        val deadline = SystemClock.elapsedRealtime() + link.expiresIn.coerceAtLeast(1) * 1000L
        val intervalMs = link.pollIntervalS.coerceIn(1, 60) * 1000L
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(intervalMs)
            if (state is DiscordLinkState.Connected) return@LaunchedEffect
            when (val poll = SupportApi.pollDiscordLinkCode(link.code, link.nonce, signedIn)) {
                is ApiResult.Success -> when (poll.data.status) {
                    "done" -> {
                        val token = poll.data.token
                        if (token.isNullOrEmpty()) {
                            state = DiscordLinkState.Failed
                            return@LaunchedEffect
                        }
                        withContext(Dispatchers.IO) {
                            PrefManager.discordOauthNonce = ""
                            PrefManager.discordRelayToken = token
                            PrefManager.discordLinkedName = poll.data.name.orEmpty()
                            PrefManager.discordMergePending = true
                        }
                        trackAiDebug("ai_debug_discord_linked", mapOf("via" to "qr"))
                        state = DiscordLinkState.Connected(poll.data.name)
                        return@LaunchedEffect
                    }
                    "expired" -> {
                        state = DiscordLinkState.Expired
                        return@LaunchedEffect
                    }
                    else -> Unit
                }
                is ApiResult.HttpError -> if (poll.code in 400..499 && poll.code != 429) {
                    state = DiscordLinkState.Failed
                    return@LaunchedEffect
                }
                is ApiResult.NetworkError -> Unit
            }
        }
        state = DiscordLinkState.Expired
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val primaryFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val hasPrimary = state !is DiscordLinkState.Starting

    val onPrimary: () -> Unit = {
        when (state) {
            is DiscordLinkState.Waiting -> if (!opening) {
                opening = true
                scope.launch {
                    try {
                        noBrowser = !onOpenHere()
                    } finally {
                        opening = false
                    }
                }
            }
            DiscordLinkState.Expired, DiscordLinkState.Failed -> attempt++
            is DiscordLinkState.Connected -> onDismiss()
            DiscordLinkState.Starting -> Unit
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    ) {
        LaunchedEffect(state::class) {
            val target = if (hasPrimary) primaryFocus else cancelFocus
            if (target.tryFocus()) return@LaunchedEffect
            withFrameNanos { }
            if (!target.tryFocus()) Timber.w("Discord link dialog could not focus its first button")
        }

        Surface(
            modifier = Modifier
                .then(if (landscape) Modifier.widthIn(max = 680.dp).padding(16.dp) else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = PluviaTheme.colors.surfaceElevated,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            if (landscape) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DiscordLinkQrPanel(state = state, size = 180.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        DiscordLinkBody(state, noBrowser, hasPrimary, primaryFocus, cancelFocus, onPrimary, onDismiss)
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
                    DiscordLinkQrPanel(state = state, size = 200.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    DiscordLinkBody(state, noBrowser, hasPrimary, primaryFocus, cancelFocus, onPrimary, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun DiscordLinkQrPanel(state: DiscordLinkState, size: Dp) {
    when (state) {
        is DiscordLinkState.Waiting -> {
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
                    QrCodeImage(content = state.link.qrUrl, size = size)
                }
            }
        }
        DiscordLinkState.Starting -> {
            Box(
                modifier = Modifier.size(size + 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        else -> Unit
    }
}

@Composable
private fun DiscordLinkBody(
    state: DiscordLinkState,
    noBrowser: Boolean,
    hasPrimary: Boolean,
    primaryFocus: FocusRequester,
    cancelFocus: FocusRequester,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.debug_report_connect_discord),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val message = when (state) {
            DiscordLinkState.Starting -> stringResource(R.string.gamenative_account_discord_link_starting)
            is DiscordLinkState.Waiting -> stringResource(R.string.gamenative_account_discord_link_scan)
            is DiscordLinkState.Connected -> state.name?.let { stringResource(R.string.gamenative_account_discord_link_connected_as, it) }
                ?: stringResource(R.string.debug_report_discord_linked)
            DiscordLinkState.Expired -> stringResource(R.string.gamenative_account_discord_link_expired)
            DiscordLinkState.Failed -> stringResource(R.string.gamenative_account_discord_link_failed)
        }
        Text(
            text = message,
            style = if (state is DiscordLinkState.Connected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )

        if (state is DiscordLinkState.Waiting) {
            Text(
                text = state.link.url.removePrefix("https://").removePrefix("http://"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.gamenative_account_discord_link_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (noBrowser) {
            Text(
                text = stringResource(R.string.gamenative_account_discord_link_no_browser),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.accentDanger,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (hasPrimary) {
            val primaryInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = onPrimary,
                interactionSource = primaryInteraction,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryFocus)
                    .focusRing(primaryInteraction, RoundedCornerShape(12.dp), width = 2.dp),
            ) {
                Text(
                    text = stringResource(
                        when (state) {
                            is DiscordLinkState.Waiting -> R.string.gamenative_account_discord_link_this_device
                            is DiscordLinkState.Connected -> R.string.close
                            else -> R.string.gamenative_sign_in_try_again
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state !is DiscordLinkState.Connected) {
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
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
