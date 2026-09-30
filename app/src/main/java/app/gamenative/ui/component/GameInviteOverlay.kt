package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gamenative.service.SteamOverlayClient
import app.gamenative.ui.util.GameInviteNotificationManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Shows an incoming Steam game invite over the running game, with an accept action that hands
 * the join to the bionic Steam client.
 *
 * Unlike [AchievementOverlay] this does not auto-dismiss: it is a prompt, so it stays until the
 * user answers it.
 */
@Composable
fun BoxScope.GameInviteOverlay() {
    val invite by GameInviteNotificationManager.pending.collectAsState()

    var inviterName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The app's own Steam session doesn't keep friend personas, so resolve the display name
    // from the running client instead; falling back to the id is fine.
    LaunchedEffect(invite?.fromSteamId) {
        inviterName = null
        val id = invite?.fromSteamId ?: return@LaunchedEffect
        inviterName = runCatching {
            SteamOverlayClient.listFriends().firstOrNull { it.steamId == id }?.name
        }.getOrNull()
    }

    AnimatedVisibility(
        visible = invite != null,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp),
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        val current = invite ?: return@AnimatedVisibility

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = inviterName ?: current.fromSteamId.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "invited you to play",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val lobby = current.lobbyId
                                val ok = if (lobby != null) {
                                    SteamOverlayClient.acceptInvite(lobby, current.fromSteamId)
                                } else {
                                    SteamOverlayClient.acceptRichPresenceJoin(
                                        current.fromSteamId,
                                        current.connectString,
                                    )
                                }
                                Timber.i("GameInviteOverlay: accept -> $ok (lobby=$lobby)")
                                busy = false
                                GameInviteNotificationManager.clear()
                            }
                        },
                    ) {
                        Text(if (busy) "Joining…" else "Join")
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = { GameInviteNotificationManager.clear() },
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}
