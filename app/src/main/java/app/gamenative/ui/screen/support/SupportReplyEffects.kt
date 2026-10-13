package app.gamenative.ui.screen.support

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gamenative.PluviaApp
import app.gamenative.R
import kotlinx.coroutines.delay

private const val GAME_EXIT_CHECK_MS = 1_000L

@Composable
fun SupportReplyEffects(
    gameRunning: Boolean,
    canOpenSupport: Boolean,
    hostState: SnackbarHostState,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ready by SupportReplyWatcher.ready
    val pending by SupportSession.pendingConversationId
    val open by rememberUpdatedState(onOpenConversation)

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            SupportReplyWatcher.run()
        }
    }

    LaunchedEffect(pending, canOpenSupport) {
        val conversationId = pending ?: return@LaunchedEffect
        if (canOpenSupport) open(conversationId)
    }

    LaunchedEffect(ready, gameRunning) {
        val notice = ready ?: return@LaunchedEffect
        if (gameRunning) return@LaunchedEffect
        while (PluviaApp.xEnvironment != null) delay(GAME_EXIT_CHECK_MS)
        if (SupportReplyWatcher.isChatVisible(notice.conversationId)) {
            SupportReplyWatcher.ready.value = null
            return@LaunchedEffect
        }
        val result = hostState.showSnackbar(
            message = context.getString(R.string.support_reply_ready),
            actionLabel = context.getString(R.string.support_reply_open),
            duration = SnackbarDuration.Long,
        )
        if (SupportReplyWatcher.ready.value == notice) SupportReplyWatcher.ready.value = null
        if (result == SnackbarResult.ActionPerformed) open(notice.conversationId)
    }
}

@Composable
fun SnackbarActionContent(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        TextButton(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}
