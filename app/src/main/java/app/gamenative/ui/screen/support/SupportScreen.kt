package app.gamenative.ui.screen.support

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.AccountApi
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun SupportScreen(
    onBack: () -> Unit,
    onStartDebugRun: (String) -> Unit,
    viewModel: SupportViewModel = hiltViewModel(),
) {
    val signedIn by PrefManager.gameNativeSignedIn
    val pending by SupportSession.pendingConversationId
    var signInChecked by remember { mutableStateOf(signedIn) }

    LaunchedEffect(Unit) {
        AccountApi.loadSignedInState()
        signInChecked = true
    }

    LaunchedEffect(pending) {
        pending?.let {
            viewModel.open(it)
            SupportSession.pendingConversationId.value = null
        }
    }

    val openId = viewModel.openConversationId
    val closeChat = {
        openId?.let { SupportRunFollowUp.dismiss(it) }
        viewModel.close()
    }
    BackHandler(enabled = openId != null) { closeChat() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        PluviaTheme.colors.surfacePanel,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                when {
                    !signInChecked -> {
                        SupportHeader(title = stringResource(R.string.support_title), subtitle = null, onBack = onBack)
                        CenteredProgress()
                    }
                    !signedIn -> SupportSignedOut(onBack = onBack)
                    openId != null -> SupportChat(
                        viewModel = viewModel,
                        onBack = closeChat,
                        onStartDebugRun = onStartDebugRun,
                    )
                    else -> SupportList(viewModel = viewModel, onBack = onBack)
                }
            }
        }
    }
}

@Composable
internal fun SupportHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    backFocus: FocusRequester? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val interaction = remember { MutableInteractionSource() }
        IconButton(
            onClick = onBack,
            interactionSource = interaction,
            modifier = Modifier
                .then(if (backFocus != null) Modifier.focusRequester(backFocus) else Modifier)
                .focusRing(interaction, CircleShape, width = 2.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
internal fun CenteredProgress(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun supportButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
    )

@Composable
internal fun FocusableButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        colors = supportButtonColors(),
        modifier = modifier.focusRing(interaction, shape, width = 2.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun problemText(problem: SupportViewModel.Problem): String =
    stringResource(
        when (problem) {
            SupportViewModel.Problem.NETWORK -> R.string.support_problem_network
            SupportViewModel.Problem.UNAVAILABLE -> R.string.support_problem_unavailable
            SupportViewModel.Problem.UNAUTHORIZED -> R.string.support_problem_unauthorized
            SupportViewModel.Problem.RATE_LIMITED -> R.string.support_problem_rate_limited
            SupportViewModel.Problem.NOT_FOUND -> R.string.support_problem_not_found
            SupportViewModel.Problem.SERVER -> R.string.support_problem_server
            SupportViewModel.Problem.TOO_LONG -> R.string.support_problem_too_long
            SupportViewModel.Problem.TOO_LARGE -> R.string.support_problem_too_large
            SupportViewModel.Problem.NO_LOGS -> R.string.support_problem_no_logs
            SupportViewModel.Problem.LOCKED -> R.string.support_problem_locked
            SupportViewModel.Problem.ALREADY_ANSWERED -> R.string.support_problem_already_answered
        },
    )

@Composable
internal fun stateLabel(state: String, outcome: Boolean? = null): String =
    stringResource(
        when (state) {
            SupportApi.STATE_SOLVED -> R.string.support_state_solved
            SupportApi.STATE_ANSWERED -> if (outcome == false) R.string.support_state_not_solved else R.string.support_state_answered
            else -> R.string.support_state_waiting
        },
    )

@Composable
internal fun rememberSupportClock(active: Boolean): Pair<Long, Long> {
    var clock by remember { mutableStateOf(SystemClock.elapsedRealtime() to System.currentTimeMillis()) }
    LaunchedEffect(active) {
        clock = SystemClock.elapsedRealtime() to System.currentTimeMillis()
        while (active) {
            delay(CLOCK_TICK_MS)
            clock = SystemClock.elapsedRealtime() to System.currentTimeMillis()
        }
    }
    return clock
}

private const val CLOCK_TICK_MS = 10_000L

@Composable
internal fun conversationLabel(conversation: SupportApi.Conversation, clock: Pair<Long, Long>): String =
    SupportProgressText.shortLabel(LocalContext.current.resources, conversation, clock.first, clock.second)
        ?: stateLabel(conversation.state, conversation.outcome)

@Composable
internal fun stateColor(state: String, outcome: Boolean? = null): Color =
    when (state) {
        SupportApi.STATE_SOLVED -> PluviaTheme.colors.accentSuccess
        SupportApi.STATE_ANSWERED -> if (outcome == false) PluviaTheme.colors.accentDanger else PluviaTheme.colors.accentCyan
        else -> PluviaTheme.colors.accentWarning
    }

@Composable
private fun CenteredMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PluviaTheme.colors.accentPurple,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun SupportSignedOut(onBack: () -> Unit) {
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val signInFocus = remember { FocusRequester() }

    AccountSignInDialog(
        visible = showSignIn,
        onSignedIn = { showSignIn = false },
        onDismiss = { showSignIn = false },
    )

    LaunchedEffect(Unit) { signInFocus.requestFocusAfterLayout() }

    SupportHeader(title = stringResource(R.string.support_title), subtitle = null, onBack = onBack)
    CenteredMessage(
        icon = Icons.Filled.SupportAgent,
        title = stringResource(R.string.support_signed_out_title),
        body = stringResource(R.string.support_signed_out_body),
    ) {
        FocusableButton(
            text = stringResource(R.string.gamenative_account_sign_in),
            onClick = { showSignIn = true },
            modifier = Modifier.focusRequester(signInFocus),
        )
        Spacer(modifier = Modifier.height(12.dp))
        val discordInteraction = remember { MutableInteractionSource() }
        TextButton(
            onClick = { uriHandler.openUri("https://discord.gg/2hKv4VfZfE") },
            interactionSource = discordInteraction,
            modifier = Modifier.focusRing(discordInteraction, RoundedCornerShape(12.dp), width = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.support_signed_out_discord),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SupportList(
    viewModel: SupportViewModel,
    onBack: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = viewModel.list
    val firstFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshList() }

    DisposableEffect(lifecycleOwner) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) {
                paused = false
                viewModel.refreshList()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasItems = state.conversations.isNotEmpty()
    LaunchedEffect(state.loaded, state.problem, hasItems) {
        if (focused) return@LaunchedEffect
        if (state.loaded || state.problem != null) {
            focused = true
            if (hasItems || state.problem != null) firstFocus.requestFocusAfterLayout() else backFocus.requestFocusAfterLayout()
        }
    }

    SupportHeader(
        title = stringResource(R.string.support_title),
        subtitle = stringResource(R.string.support_subtitle),
        onBack = onBack,
        backFocus = backFocus,
    ) {
        val interaction = remember { MutableInteractionSource() }
        IconButton(
            onClick = { viewModel.refreshList() },
            enabled = !state.loading,
            interactionSource = interaction,
            modifier = Modifier.focusRing(interaction, CircleShape, width = 2.dp),
        ) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = stringResource(R.string.support_refresh))
        }
    }

    when {
        !state.loaded && state.problem != null -> CenteredMessage(
            icon = Icons.Filled.SupportAgent,
            title = stringResource(R.string.support_title),
            body = problemText(state.problem),
        ) {
            if (state.problem != SupportViewModel.Problem.UNAVAILABLE) {
                FocusableButton(
                    text = stringResource(R.string.debug_report_retry),
                    onClick = { viewModel.refreshList() },
                    enabled = !state.loading,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            } else {
                FocusableButton(
                    text = stringResource(R.string.back),
                    onClick = onBack,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
        }
        !state.loaded -> CenteredProgress()
        !hasItems -> CenteredMessage(
            icon = Icons.Filled.Forum,
            title = stringResource(R.string.support_empty_title),
            body = stringResource(R.string.support_empty_body),
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.problem != null) {
                item(key = "problem") {
                    Text(
                        text = problemText(state.problem),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.accentDanger,
                    )
                }
            }
            itemsIndexed(state.conversations, key = { _, item -> item.id }) { index, conversation ->
                ConversationRow(
                    conversation = conversation,
                    onClick = { viewModel.open(conversation.id) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: SupportApi.Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val now = System.currentTimeMillis()
    val clock = rememberSupportClock(conversation.progress?.active == true)
    Surface(
        onClick = onClick,
        shape = shape,
        color = PluviaTheme.colors.surfaceElevated,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .focusRing(interaction, shape, width = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.game.ifEmpty { stringResource(R.string.support_title) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.createdAt > 0) {
                    Text(
                        text = stringResource(
                            R.string.support_started,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(conversation.createdAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }
                if (conversation.lastMessageAt > 0) {
                    Text(
                        text = stringResource(
                            R.string.support_last_activity,
                            DateUtils.getRelativeTimeSpanString(
                                conversation.lastMessageAt,
                                now,
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }
            }
            StateChip(
                state = conversation.state,
                outcome = conversation.outcome,
                label = conversationLabel(conversation, clock),
            )
        }
    }
}

@Composable
internal fun StateChip(state: String, outcome: Boolean? = null, label: String? = null) {
    val color = stateColor(state, outcome)
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label ?: stateLabel(state, outcome),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
