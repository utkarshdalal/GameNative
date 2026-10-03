package app.gamenative.ui.component.dialog

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.screen.login.QrCodeImage
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.delay
import timber.log.Timber

private sealed class SignInState {
    data object Starting : SignInState()
    data class Waiting(val start: AccountApi.DeviceStart) : SignInState()
    data object Expired : SignInState()
    data object Failed : SignInState()
}

private data class PendingSignIn(
    val start: AccountApi.DeviceStart,
    val deadline: Long,
    val intervalMs: Long,
)

private val PendingSignInSaver = Saver<PendingSignIn?, ArrayList<Any>>(
    save = { pending ->
        pending?.let {
            arrayListOf<Any>(
                it.start.deviceCode,
                it.start.userCode,
                it.start.verificationUrl,
                it.start.expiresIn,
                it.start.interval,
                it.deadline,
                it.intervalMs,
            )
        }
    },
    restore = { saved ->
        PendingSignIn(
            start = AccountApi.DeviceStart(
                deviceCode = saved[0] as String,
                userCode = saved[1] as String,
                verificationUrl = saved[2] as String,
                expiresIn = saved[3] as Int,
                interval = saved[4] as Int,
            ),
            deadline = saved[5] as Long,
            intervalMs = saved[6] as Long,
        )
    },
)

private fun FocusRequester.tryRequestFocus(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

fun openAccountUrl(context: Context, url: String): Boolean =
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
        true
    } catch (_: ActivityNotFoundException) {
        Timber.w("No browser available to open an account page")
        false
    }

@Composable
fun AccountSignInDialog(
    visible: Boolean,
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val currentOnSignedIn by rememberUpdatedState(onSignedIn)
    var attempt by remember { mutableIntStateOf(0) }
    var pending by rememberSaveable(stateSaver = PendingSignInSaver) { mutableStateOf<PendingSignIn?>(null) }
    var state by remember {
        mutableStateOf<SignInState>(pending?.let { SignInState.Waiting(it.start) } ?: SignInState.Starting)
    }
    var noBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        noBrowser = false
        val current = pending?.takeIf { SystemClock.elapsedRealtime() < it.deadline } ?: run {
            pending = null
            state = SignInState.Starting
            val start = when (val result = AccountApi.startDeviceSignIn()) {
                is ApiResult.Success -> result.data
                else -> {
                    state = SignInState.Failed
                    return@LaunchedEffect
                }
            }
            PendingSignIn(
                start = start,
                deadline = SystemClock.elapsedRealtime() + start.expiresIn.coerceAtLeast(1) * 1000L,
                intervalMs = start.interval.coerceAtLeast(1) * 1000L,
            ).also { pending = it }
        }
        state = SignInState.Waiting(current.start)
        var intervalMs = current.intervalMs
        while (SystemClock.elapsedRealtime() < current.deadline) {
            delay(intervalMs)
            when (val poll = AccountApi.pollDeviceSignIn(current.start.deviceCode)) {
                is AccountApi.PollResult.SignedIn -> {
                    pending = null
                    SnackbarManager.show(context.getString(R.string.gamenative_sign_in_success))
                    currentOnSignedIn()
                    return@LaunchedEffect
                }
                AccountApi.PollResult.Pending -> Unit
                is AccountApi.PollResult.SlowDown -> {
                    intervalMs = poll.retryAfterSeconds
                        ?.coerceIn(1L, 60L)
                        ?.let { maxOf(intervalMs, it * 1000L) }
                        ?: (intervalMs + 2000L)
                    pending = current.copy(intervalMs = intervalMs)
                }
                AccountApi.PollResult.Expired -> {
                    pending = null
                    state = SignInState.Expired
                    return@LaunchedEffect
                }
                is AccountApi.PollResult.Failure -> {
                    val code = poll.code
                    if (code != null && code in 400..499) {
                        pending = null
                        state = SignInState.Failed
                        return@LaunchedEffect
                    }
                }
            }
        }
        pending = null
        state = SignInState.Expired
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val primaryFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val hasPrimary = state !is SignInState.Starting

    val onPrimary: () -> Unit = {
        when (val current = state) {
            is SignInState.Waiting -> noBrowser = !openAccountUrl(context, current.start.verificationUrl)
            SignInState.Expired, SignInState.Failed -> attempt++
            SignInState.Starting -> Unit
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    ) {
        LaunchedEffect(state::class) {
            val target = if (hasPrimary) primaryFocus else cancelFocus
            if (target.tryRequestFocus()) return@LaunchedEffect
            withFrameNanos { }
            if (!target.tryRequestFocus()) Timber.w("Sign-in dialog could not focus its first button")
        }

        Surface(
            modifier = Modifier
                .then(if (landscape) Modifier.widthIn(max = 640.dp).padding(16.dp) else Modifier.fillMaxWidth())
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
                    SignInQrPanel(state = state, size = 180.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SignInBody(
                            state = state,
                            noBrowser = noBrowser,
                            hasPrimary = hasPrimary,
                            primaryFocus = primaryFocus,
                            cancelFocus = cancelFocus,
                            onPrimary = onPrimary,
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
                    SignInQrPanel(state = state, size = 200.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    SignInBody(
                        state = state,
                        noBrowser = noBrowser,
                        hasPrimary = hasPrimary,
                        primaryFocus = primaryFocus,
                        cancelFocus = cancelFocus,
                        onPrimary = onPrimary,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignInQrPanel(state: SignInState, size: Dp) {
    when (state) {
        is SignInState.Waiting -> {
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
                    QrCodeImage(content = state.start.verificationUrl, size = size)
                }
            }
        }
        SignInState.Starting -> {
            Box(
                modifier = Modifier.size(size + 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        SignInState.Expired, SignInState.Failed -> Unit
    }
}

@Composable
private fun SignInBody(
    state: SignInState,
    noBrowser: Boolean,
    hasPrimary: Boolean,
    primaryFocus: FocusRequester,
    cancelFocus: FocusRequester,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gamenative_sign_in_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val message = when (state) {
            SignInState.Starting -> stringResource(R.string.gamenative_sign_in_starting)
            is SignInState.Waiting -> stringResource(R.string.gamenative_sign_in_scan)
            SignInState.Expired -> stringResource(R.string.gamenative_sign_in_expired)
            SignInState.Failed -> stringResource(R.string.gamenative_sign_in_failed)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state is SignInState.Waiting) {
            if (state.start.userCode.isNotBlank()) {
                Text(
                    text = stringResource(R.string.gamenative_sign_in_code, state.start.userCode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(
                text = stringResource(R.string.gamenative_sign_in_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (noBrowser) {
            Text(
                text = stringResource(R.string.gamenative_sign_in_no_browser),
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
                        if (state is SignInState.Waiting) {
                            R.string.gamenative_sign_in_this_device
                        } else {
                            R.string.gamenative_sign_in_try_again
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

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
