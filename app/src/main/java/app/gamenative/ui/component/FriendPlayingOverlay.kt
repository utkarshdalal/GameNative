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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamOverlayClient
import app.gamenative.ui.util.FriendPlayingNotification
import app.gamenative.ui.util.FriendPlayingNotificationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun BoxScope.FriendPlayingOverlay() {
    var current by remember { mutableStateOf<FriendPlayingNotification?>(null) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        FriendPlayingNotificationManager.notifications.collect { notification ->
            current = notification
            visible = true
            delay(if (notification.joinable) 8000 else 5000)
            visible = false
            delay(400)
            if (current == notification) current = null
        }
    }

    AnimatedVisibility(
        visible = visible && current != null,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(16.dp),
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
    ) {
        val notification = current ?: return@AnimatedVisibility
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
                    text = notification.friendName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.steam_friend_playing_same_game),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notification.joinable && notification.lobbyId != 0L) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val ok = SteamOverlayClient.acceptInvite(
                                        notification.lobbyId,
                                        notification.friendSteamId,
                                    )
                                    Timber.i(
                                        "FriendPlayingOverlay: join %s -> %s",
                                        notification.friendName,
                                        ok,
                                    )
                                    busy = false
                                    visible = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.steam_overlay_action_join))
                        }
                    }
                    TextButton(
                        onClick = {
                            SteamFriendsOverlayController.show(forGameRequest = false)
                            visible = false
                        },
                    ) {
                        Text(stringResource(R.string.steam_invite_tab_title))
                    }
                    TextButton(onClick = { visible = false }) {
                        Text(stringResource(R.string.steam_invite_dismiss))
                    }
                }
            }
        }
    }
}
