package app.gamenative.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamOverlayClient
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-game Steam friends overlay: invite online friends or join someone already in this game.
 * Opens from Quick Menu or when the game requests its invite dialog.
 */
@Composable
fun BoxScope.SteamFriendsOverlay() {
    val visible = SteamFriendsOverlayController.visible
    val state = SteamFriendsOverlayController.inviteState
    val scope = rememberCoroutineScope()

    BackHandler(enabled = visible) {
        SteamFriendsOverlayController.hide()
    }

    AnimatedVisibility(
        visible = visible && state != null,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val inviteState = state ?: return@AnimatedVisibility

        LaunchedEffect(visible) {
            if (visible) {
                inviteState.refresh()
                while (SteamFriendsOverlayController.visible) {
                    delay(3000)
                    inviteState.refreshQuietly()
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Dimmed backdrop — tap to dismiss.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { SteamFriendsOverlayController.hide() },
            )

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(0.92f),
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable(enabled = false) {},
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.steam_overlay_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = when {
                                        inviteState.isLoading -> stringResource(R.string.main_loading)
                                        inviteState.hostUnavailable ->
                                            stringResource(R.string.steam_invite_unavailable)
                                        else -> stringResource(
                                            R.string.steam_invite_header,
                                            inviteState.friends.size,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { inviteState.refresh() } },
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.steam_overlay_refresh),
                                )
                            }
                            IconButton(onClick = { SteamFriendsOverlayController.hide() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.steam_overlay_close),
                                )
                            }
                        }

                        inviteState.lastError?.let { err ->
                            Text(
                                text = stringResource(err),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        Spacer(modifier = Modifier.height(6.dp))

                        if (!inviteState.isLoading && inviteState.friends.isEmpty() && !inviteState.hostUnavailable) {
                            Text(
                                text = stringResource(R.string.steam_overlay_no_friends),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            inviteState.friends.forEach { friend ->
                                FriendRow(
                                    friend = friend,
                                    inviteSent = friend.steamId in inviteState.inviteSent,
                                    onAction = {
                                        scope.launch {
                                            if (friend.isJoinable) inviteState.join(friend)
                                            else inviteState.invite(friend.steamId)
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
}

@Composable
private fun FriendRow(
    friend: SteamOverlayClient.Friend,
    inviteSent: Boolean,
    onAction: () -> Unit,
) {
    val accent = PluviaTheme.colors.accentPurple
    val statusRes = when {
        friend.inOurLobby -> R.string.steam_invite_joined
        friend.isJoinable -> R.string.steam_invite_joinable
        inviteSent -> R.string.steam_invite_sent
        friend.isOnline -> R.string.steam_invite_online
        else -> R.string.steam_invite_offline
    }
    val actionRes = when {
        friend.inOurLobby -> null
        friend.isJoinable -> R.string.steam_overlay_action_join
        friend.isOnline -> R.string.steam_overlay_action_invite
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.bodySmall,
                color = if (friend.isOnline || friend.isJoinable || friend.inOurLobby) {
                    accent
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
            )
        }
        if (actionRes != null) {
            TextButton(onClick = onAction) {
                Text(stringResource(actionRes))
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
