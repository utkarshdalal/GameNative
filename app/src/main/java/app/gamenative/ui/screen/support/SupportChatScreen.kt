package app.gamenative.ui.screen.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gamenative.R
import app.gamenative.api.SupportApi
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

private sealed class ChatItem(val key: String) {
    class Entry(val message: SupportApi.Message) : ChatItem("m${message.id}")
    class Analysing(val first: Boolean) : ChatItem("analysing")
    data object OutcomePrompt : ChatItem("outcome")
    data object RunCheck : ChatItem("run_check")
}

private fun chatItems(state: SupportViewModel.ChatState, runCheck: Boolean, hideOutcome: Boolean): List<ChatItem> {
    val known = setOf(SupportApi.KIND_USER, SupportApi.KIND_AGENT, SupportApi.KIND_STAFF, SupportApi.KIND_NOTICE)
    val messages = state.messages.filter { message ->
        message.kind in known && (message.kind != SupportApi.KIND_NOTICE || (message.notice != null && message.notice !is SupportApi.Notice.Other))
    }
    val items = mutableListOf<ChatItem>()
    messages.forEach { items.add(ChatItem.Entry(it)) }
    val conversation = state.conversation
    if (conversation != null) {
        val replied = messages.any { it.kind == SupportApi.KIND_AGENT || it.kind == SupportApi.KIND_STAFF }
        when (conversation.state) {
            SupportApi.STATE_WAITING -> items.add(ChatItem.Analysing(first = !replied))
            SupportApi.STATE_ANSWERED -> if (conversation.outcome == null && !hideOutcome) items.add(ChatItem.OutcomePrompt)
        }
    }
    if (runCheck) items.add(ChatItem.RunCheck)
    return items
}

private fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val type = when (file.extension.lowercase()) {
            "json" -> "application/json"
            "txt", "log" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.support_share_file)))
    } catch (_: ActivityNotFoundException) {
        Timber.w("No app available to open a support attachment")
    } catch (_: IllegalArgumentException) {
        Timber.w("Support attachment is outside the shared paths")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.SupportChat(
    viewModel: SupportViewModel,
    onBack: () -> Unit,
    onStartDebugRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val chat = viewModel.chat
    val conversation = chat.conversation
    val conversationId = chat.conversationId
    var upgradeReason by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val lastFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable(conversationId) { mutableStateOf(false) }
    val uploadProgress = viewModel.uploadProgress
    val runState by SupportRunFollowUp.state
    val run = runState?.takeIf { it.conversationId == conversationId }
    val runBusy = run?.busy == true
    val runFocus = remember { FocusRequester() }
    val items = chatItems(chat, runCheck = run != null, hideOutcome = run?.asking == true)
    val upgradeOpen by rememberUpdatedState(upgradeReason != null)
    val clock = rememberSupportClock(conversation?.progress?.active == true)
    val notifyOffer = rememberNotifyOffer(conversation)

    LaunchedEffect(conversationId, lifecycleOwner) {
        if (conversationId.isEmpty()) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SupportReplyWatcher.chatVisible(conversationId)
            try {
                viewModel.poll(conversationId)
                awaitCancellation()
            } finally {
                SupportReplyWatcher.chatHidden(conversationId)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) {
                paused = false
                if (!upgradeOpen) viewModel.refreshAfterResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(chat.loaded, chat.problem, items.size) {
        if (initialFocusDone) return@LaunchedEffect
        if (chat.loaded && items.isNotEmpty()) {
            initialFocusDone = true
            listState.scrollToItem(items.size + 1)
            lastFocus.requestFocusAfterLayout()
        } else if (chat.problem != null && !chat.loaded) {
            initialFocusDone = true
            backFocus.requestFocusAfterLayout()
        }
    }

    LaunchedEffect(viewModel) {
        SupportRunFollowUp.updates.collect { (id, posted) -> viewModel.ingest(id, posted) }
    }

    val runShown = run != null && chat.loaded && items.lastOrNull() == ChatItem.RunCheck
    val runMode = run?.phase?.let { if (it == SupportRunFollowUp.Phase.NO_LOG || it == SupportRunFollowUp.Phase.OFFER) it else null }
    LaunchedEffect(runShown, runMode) {
        if (!runShown) return@LaunchedEffect
        initialFocusDone = true
        listState.scrollToItem(items.size + 1)
        runFocus.requestFocusAfterLayout()
    }

    val lastUploadAt = chat.messages
        .filter { it.kind == SupportApi.KIND_USER && (it.isReport || it.attachments.isNotEmpty()) }
        .maxOfOrNull { it.createdAt }
        ?: conversation?.createdAt
    LaunchedEffect(conversationId, chat.loaded, conversation?.appId, lastUploadAt) {
        val targetAppId = conversation?.appId ?: return@LaunchedEffect
        if (!chat.loaded || lastUploadAt == null) return@LaunchedEffect
        SupportRunFollowUp.offerLastRun(context, targetAppId, conversationId, lastUploadAt)
    }

    val lastKey = items.lastOrNull()?.key
    LaunchedEffect(lastKey) {
        if (!initialFocusDone || items.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= info.totalItemsCount - 2) listState.animateScrollToItem(items.size + 1)
    }

    SupportUpgradeDialog(
        visible = upgradeReason != null,
        reason = upgradeReason,
        onCheckoutReturn = { viewModel.refreshConversation() },
        onDismiss = { upgradeReason = null },
    )

    SupportHeader(
        title = conversation?.game?.ifEmpty { null } ?: stringResource(R.string.support_title),
        subtitle = conversation?.let { conversationLabel(it, clock) },
        onBack = onBack,
        backFocus = backFocus,
    )

    val appId = conversation?.appId
    val composerAllowed = conversation?.composer?.allowed ?: false
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(
            text = stringResource(R.string.support_send_logs),
            icon = Icons.Filled.Upload,
            enabled = appId != null && composerAllowed && uploadProgress == null && !chat.sending && !runBusy,
            onClick = { viewModel.sendLogs(context) },
        )
        ActionButton(
            text = stringResource(R.string.support_new_debug_run),
            icon = Icons.Filled.BugReport,
            enabled = appId != null,
            onClick = {
                if (appId != null) {
                    SupportSession.startedRunFrom(appId, conversationId)
                    onStartDebugRun(appId)
                }
            },
        )
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
            !chat.loaded && chat.problem != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = problemText(chat.problem),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PluviaTheme.colors.textMuted,
                )
            }
            !chat.loaded -> CenteredProgress()
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val key = focusedKey ?: return@onPreviewKeyEvent false
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@onPreviewKeyEvent false
                        val step = (info.viewportEndOffset - info.viewportStartOffset) * 2 / 3
                        when (event.key) {
                            Key.DirectionDown -> {
                                val overflow = item.offset + item.size - info.viewportEndOffset
                                if (overflow > 0) {
                                    scope.launch { listState.animateScrollBy(minOf(step, overflow).toFloat()) }
                                    true
                                } else {
                                    false
                                }
                            }
                            Key.DirectionUp -> {
                                val overflow = info.viewportStartOffset - item.offset
                                if (overflow > 0) {
                                    scope.launch { listState.animateScrollBy(-minOf(step, overflow).toFloat()) }
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "top") { Spacer(modifier = Modifier.size(4.dp)) }
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (it.hasFocus) focusedKey = item.key }
                            .then(if (index == items.lastIndex) Modifier.focusRequester(lastFocus) else Modifier),
                    ) {
                        when (item) {
                            is ChatItem.Entry -> Column(modifier = Modifier.fillMaxWidth()) {
                                MessageItem(
                                    message = item.message,
                                    downloadingUrl = viewModel.downloadingUrl,
                                    onAttachment = { attachment ->
                                        viewModel.downloadAttachment(context, attachment) { file -> shareFile(context, file) }
                                    },
                                    onUpgrade = { upgradeReason = it },
                                )
                                item.message.suggestion?.let { suggestion ->
                                    SuggestionCard(
                                        message = item.message,
                                        suggestion = suggestion,
                                        appId = appId,
                                        conversationId = conversationId,
                                        onStartDebugRun = onStartDebugRun,
                                    )
                                }
                            }
                            is ChatItem.Analysing -> AnalysingCard(
                                first = item.first,
                                progress = conversation?.progress,
                                clock = clock,
                                retryEnabled = appId != null && uploadProgress == null && !chat.sending && !runBusy,
                                onRetry = { viewModel.sendLogs(context) },
                                onNotify = notifyOffer,
                            )
                            ChatItem.OutcomePrompt -> OutcomePromptCard(
                                busy = chat.outcomeBusy,
                                onOutcome = { solved -> viewModel.setOutcome(solved) },
                            )
                            ChatItem.RunCheck -> if (run != null) {
                                RunCheckCard(
                                    state = run,
                                    focusRequester = runFocus,
                                    onAnswer = { worked ->
                                        val verdict = context.getString(
                                            if (worked) R.string.support_outcome_worked else R.string.support_outcome_broken,
                                        )
                                        val details = viewModel.draft.trim()
                                        val text = if (details.isEmpty()) verdict else "$verdict\n$details"
                                        val recordOutcome = worked && conversation?.outcome == null &&
                                            chat.messages.any { it.kind == SupportApi.KIND_AGENT || it.kind == SupportApi.KIND_STAFF }
                                        SupportRunFollowUp.answer(context, text, worked, recordOutcome, details)
                                        viewModel.updateDraft("")
                                    },
                                    onRetry = { SupportRunFollowUp.retry(context) },
                                    onRetryAnswer = { SupportRunFollowUp.retryAnswer(context) },
                                    onSendOffered = { SupportRunFollowUp.sendOffered(context) },
                                    onDismissOffer = { SupportRunFollowUp.dismiss(conversationId) },
                                    onNewRun = {
                                        val targetAppId = appId ?: run.appId
                                        SupportRunFollowUp.clear(conversationId)
                                        SupportSession.startedRunFrom(targetAppId, conversationId)
                                        onStartDebugRun(targetAppId)
                                    },
                                    onUpgrade = { upgradeReason = it },
                                )
                            }
                        }
                    }
                }
                item(key = "bottom") { Spacer(modifier = Modifier.size(4.dp)) }
            }
        }
    }

    if (chat.loaded && chat.problem != null) {
        Text(
            text = problemText(chat.problem),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.accentDanger,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    chat.actionProblem?.let { problem ->
        Text(
            text = problemText(problem),
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.accentDanger,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (uploadProgress != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.support_uploading),
                style = MaterialTheme.typography.bodySmall,
                color = PluviaTheme.colors.textMuted,
            )
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    if (conversation != null && !conversation.composer.allowed) {
        LockedComposer(
            reason = conversation.composer.reason,
            resetsAt = conversation.composer.resetsAt,
            onUpgrade = { upgradeReason = conversation.composer.reason ?: SupportApi.REASON_UPGRADE_REQUIRED },
        )
    } else if (conversation != null) {
        val answering = run?.takesReply == true
        Composer(
            text = viewModel.draft,
            sending = chat.sending || run?.answer == SupportRunFollowUp.Answer.SENDING,
            busy = uploadProgress != null && !answering,
            onTextChange = { value ->
                viewModel.updateDraft(value)
                if (chat.actionProblem != null) viewModel.clearActionProblem()
            },
            onSend = {
                if (answering) {
                    SupportRunFollowUp.answer(context, viewModel.draft, worked = null, recordOutcome = false)
                    viewModel.updateDraft("")
                } else {
                    viewModel.send()
                }
            },
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        modifier = Modifier.focusRing(interaction, shape, width = 2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = text)
    }
}

@Composable
private fun FocusableCard(
    modifier: Modifier = Modifier,
    color: Color = PluviaTheme.colors.surfaceElevated,
    border: BorderStroke? = null,
    isFocusable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = border,
        modifier = modifier
            .focusRing(interaction, shape, width = 2.dp)
            .then(if (isFocusable) Modifier.focusable(interactionSource = interaction) else Modifier),
    ) {
        Box(modifier = Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun MessageLabel(text: String, time: Long, icon: ImageVector?, alignEnd: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PluviaTheme.colors.accentPurple,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (time > 0) {
            Text(
                text = " · " + DateUtils.formatDateTime(
                    context,
                    time,
                    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = PluviaTheme.colors.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageItem(
    message: SupportApi.Message,
    downloadingUrl: String?,
    onAttachment: (SupportApi.Attachment) -> Unit,
    onUpgrade: (String) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    when (message.kind) {
        SupportApi.KIND_NOTICE -> NoticeCard(notice = message.notice, onUpgrade = onUpgrade)
        else -> {
            val mine = message.kind == SupportApi.KIND_USER
            val staff = message.kind == SupportApi.KIND_STAFF
            val label = when {
                mine && message.isReport -> stringResource(R.string.support_label_report)
                mine -> stringResource(R.string.support_label_you)
                staff -> message.authorName?.let { stringResource(R.string.support_label_staff, it) }
                    ?: stringResource(R.string.support_label_team)
                else -> stringResource(R.string.support_label_ai)
            }
            val bubbleColor = when {
                mine -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                staff -> PluviaTheme.colors.accentCyan.copy(alpha = 0.2f)
                else -> PluviaTheme.colors.surfaceElevated
            }
            val textColor = MaterialTheme.colorScheme.onSurface
            val document = if (mine) null else rememberMarkdown(message.text)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                MessageLabel(
                    text = label,
                    time = message.createdAt,
                    icon = when {
                        mine -> null
                        staff -> Icons.Filled.SupportAgent
                        else -> Icons.Filled.SmartToy
                    },
                    alignEnd = mine,
                )
                FocusableCard(
                    modifier = Modifier.widthIn(max = 640.dp),
                    color = bubbleColor,
                    border = if (staff) BorderStroke(1.dp, PluviaTheme.colors.accentPurple) else null,
                ) {
                    if (document != null) {
                        MarkdownText(document = document, color = textColor)
                    } else if (message.text.isNotBlank()) {
                        Text(text = message.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val links = document?.links.orEmpty()
                if (message.attachments.isNotEmpty() || links.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        message.attachments.forEach { attachment ->
                            val chipLabel = attachment.size?.let {
                                "${attachment.filename} · ${Formatter.formatShortFileSize(context, it)}"
                            } ?: attachment.filename
                            ChipButton(
                                text = chipLabel,
                                icon = Icons.Filled.AttachFile,
                                busy = attachment.url != null && attachment.url == downloadingUrl,
                                enabled = attachment.url != null && downloadingUrl == null,
                                onClick = { onAttachment(attachment) },
                            )
                        }
                        links.forEach { url ->
                            ChipButton(
                                text = Uri.parse(url).host ?: url,
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                onClick = {
                                    try {
                                        uriHandler.openUri(url)
                                    } catch (_: Exception) {
                                        Timber.w("No app available to open a link from support")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text = text, maxLines = 1) },
        leadingIcon = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        },
        shape = shape,
        interactionSource = interaction,
        modifier = Modifier.focusRing(interaction, shape, width = 2.dp),
    )
}

@Composable
private fun NoticeCard(
    notice: SupportApi.Notice?,
    onUpgrade: (String) -> Unit,
) {
    when (notice) {
        is SupportApi.Notice.Upgrade -> FocusableCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, PluviaTheme.colors.accentPurple),
            isFocusable = false,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WorkspacePremium,
                        contentDescription = null,
                        tint = PluviaTheme.colors.accentPurple,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(
                            if (upgradeOffered(notice.reason)) R.string.support_upgrade_title else R.string.support_limit_title,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = upgradeReasonText(notice.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                if (upgradeOffered(notice.reason)) {
                    FocusableButton(
                        text = stringResource(R.string.support_see_plans),
                        onClick = { onUpgrade(notice.reason) },
                    )
                }
            }
        }
        is SupportApi.Notice.Limit -> FocusableCard(modifier = Modifier.fillMaxWidth()) {
            val hours = SupportApi.FairUse(notice.resetsAt, null).hoursLeft()
            Text(
                text = fairUseReasonText(hours, notice.message),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is SupportApi.Notice.Moved -> FocusableCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.support_notice_moved), style = MaterialTheme.typography.bodyMedium)
        }
        is SupportApi.Notice.Outcome -> FocusableCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (notice.solved) Icons.Filled.CheckCircle else Icons.Filled.ThumbDown,
                        contentDescription = null,
                        tint = if (notice.solved) PluviaTheme.colors.accentSuccess else PluviaTheme.colors.accentWarning,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(
                            if (notice.solved) R.string.support_outcome_recorded_worked else R.string.support_outcome_recorded_broken,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                notice.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun AnalysingCard(
    first: Boolean,
    progress: SupportApi.Progress?,
    clock: Pair<Long, Long>,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    onNotify: (() -> Unit)?,
) {
    val resources = LocalContext.current.resources
    if (progress?.stage == SupportApi.STAGE_FAILED) {
        FocusableCard(modifier = Modifier.fillMaxWidth(), isFocusable = false) {
            Column {
                Text(
                    text = stringResource(R.string.support_progress_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                FocusableButton(
                    text = stringResource(R.string.support_progress_retry),
                    onClick = onRetry,
                    enabled = retryEnabled,
                )
            }
        }
        return
    }
    val text = SupportProgressText.line(resources, progress?.takeIf { it.active }, clock.first, clock.second)
        ?: stringResource(if (first) R.string.support_analysing_first else R.string.support_analysing_followup)
    val detail = progress?.takeIf { it.stage == SupportApi.STAGE_ANALYSING }?.detail
    FocusableCard(modifier = Modifier.fillMaxWidth(), isFocusable = onNotify == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (onNotify != null) {
                    FocusableButton(
                        text = stringResource(R.string.support_notify_me),
                        onClick = onNotify,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomePromptCard(
    busy: Boolean,
    onOutcome: (Boolean) -> Unit,
) {
    FocusableCard(modifier = Modifier.fillMaxWidth(), isFocusable = false) {
        Column {
            Text(
                text = stringResource(R.string.support_outcome_question),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusableButton(
                    text = stringResource(R.string.support_outcome_worked),
                    onClick = { onOutcome(true) },
                    enabled = !busy,
                    leading = { Icon(Icons.Filled.ThumbUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FocusableButton(
                    text = stringResource(R.string.support_outcome_broken),
                    onClick = { onOutcome(false) },
                    enabled = !busy,
                    leading = { Icon(Icons.Filled.ThumbDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun runFailureText(failure: SupportReportSubmitter.Outcome?): String =
    when (failure) {
        is SupportReportSubmitter.Outcome.Forbidden -> upgradeReasonText(failure.reason)
        SupportReportSubmitter.Outcome.PlanPending -> stringResource(R.string.support_plan_pending)
        SupportReportSubmitter.Outcome.RateLimited -> stringResource(R.string.support_problem_rate_limited)
        SupportReportSubmitter.Outcome.LimitReached -> fairUseReasonText(SupportApi.lastFairUse?.hoursLeft())
        SupportReportSubmitter.Outcome.SignedOut -> stringResource(R.string.support_problem_unauthorized)
        SupportReportSubmitter.Outcome.Unavailable -> stringResource(R.string.support_problem_unavailable)
        else -> stringResource(R.string.support_run_check_failed)
    }

@Composable
private fun RunCheckCard(
    state: SupportRunFollowUp.State,
    focusRequester: FocusRequester,
    onAnswer: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onRetryAnswer: () -> Unit,
    onSendOffered: () -> Unit,
    onDismissOffer: () -> Unit,
    onNewRun: () -> Unit,
    onUpgrade: (String) -> Unit,
) {
    when (state.phase) {
        SupportRunFollowUp.Phase.NO_LOG -> FocusableCard(modifier = Modifier.fillMaxWidth(), isFocusable = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.debug_report_no_log),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                FocusableButton(
                    text = stringResource(R.string.support_new_debug_run),
                    onClick = onNewRun,
                    modifier = Modifier.focusRequester(focusRequester),
                    leading = { Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        SupportRunFollowUp.Phase.OFFER -> FocusableCard(modifier = Modifier.fillMaxWidth(), isFocusable = false) {
            Column {
                Text(
                    text = stringResource(R.string.support_run_check_offer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusableButton(
                        text = stringResource(R.string.support_run_check_send_last),
                        onClick = onSendOffered,
                        modifier = Modifier.focusRequester(focusRequester),
                        leading = { Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    FocusableButton(
                        text = stringResource(R.string.close),
                        onClick = onDismissOffer,
                    )
                }
            }
        }
        else -> FocusableCard(modifier = Modifier.fillMaxWidth(), isFocusable = false) {
            Column {
                Text(
                    text = stringResource(R.string.support_run_check_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                when (state.phase) {
                    SupportRunFollowUp.Phase.PREPARING -> {
                        Text(
                            text = stringResource(R.string.debug_report_preparing),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    SupportRunFollowUp.Phase.UPLOADING -> {
                        Text(
                            text = stringResource(R.string.support_uploading),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                        )
                        LinearProgressIndicator(
                            progress = { state.progress ?: 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    SupportRunFollowUp.Phase.SENT -> Text(
                        text = stringResource(R.string.support_run_check_sent),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                    else -> {
                        val failure = state.failure
                        Text(
                            text = runFailureText(failure),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.accentDanger,
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FocusableButton(
                                text = stringResource(R.string.support_progress_retry),
                                onClick = onRetry,
                            )
                            if (failure is SupportReportSubmitter.Outcome.Forbidden) {
                                FocusableButton(
                                    text = stringResource(R.string.support_see_plans),
                                    onClick = { onUpgrade(failure.reason) },
                                )
                            }
                        }
                    }
                }
                when (state.answer) {
                    SupportRunFollowUp.Answer.NONE -> Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FocusableButton(
                            text = stringResource(R.string.support_outcome_worked),
                            onClick = { onAnswer(true) },
                            modifier = Modifier.focusRequester(focusRequester),
                            leading = { Icon(Icons.Filled.ThumbUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        FocusableButton(
                            text = stringResource(R.string.support_outcome_broken),
                            onClick = { onAnswer(false) },
                            leading = { Icon(Icons.Filled.ThumbDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    SupportRunFollowUp.Answer.WAITING -> Text(
                        text = stringResource(R.string.support_run_check_answer_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SupportRunFollowUp.Answer.SENDING -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 8.dp).size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    SupportRunFollowUp.Answer.DELIVERED -> Unit
                    SupportRunFollowUp.Answer.FAILED -> Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = stringResource(R.string.support_run_check_answer_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.accentDanger,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        FocusableButton(
                            text = stringResource(R.string.support_progress_retry),
                            onClick = onRetryAnswer,
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedComposer(reason: String?, resetsAt: Long?, onUpgrade: () -> Unit) {
    if (reason == SupportApi.REASON_ANALYSING) {
        Text(
            text = stringResource(R.string.support_composer_analysing),
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = upgradeReasonText(reason, resetsAt),
            style = MaterialTheme.typography.bodyMedium,
            color = PluviaTheme.colors.textMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (upgradeOffered(reason)) {
            FocusableButton(
                text = stringResource(R.string.support_see_plans),
                onClick = onUpgrade,
                leading = { Icon(Icons.Filled.WorkspacePremium, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    text: String,
    sending: Boolean,
    busy: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val counter: (@Composable () -> Unit)? = if (text.length > SupportApi.TEXT_MAX - 200) {
        { Text("${text.length}/${SupportApi.TEXT_MAX}") }
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoExtractOutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = !sending,
            placeholder = { Text(stringResource(R.string.support_composer_hint)) },
            supportingText = counter,
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || imeVisible) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                        Key.DirectionCenter, Key.ButtonA -> {
                            keyboard?.show()
                            true
                        }
                        else -> false
                    }
                },
        )
        val interaction = remember { MutableInteractionSource() }
        val shape = RoundedCornerShape(12.dp)
        Button(
            onClick = onSend,
            enabled = text.isNotBlank() && !sending && !busy,
            interactionSource = interaction,
            shape = shape,
            colors = supportButtonColors(),
            modifier = Modifier.focusRing(interaction, shape, width = 2.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.debug_report_send))
        }
    }
}
