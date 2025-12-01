package app.gamenative.ui.screen.chat

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.data.FriendMessage
import app.gamenative.data.SteamFriend
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.data.ChatState
import app.gamenative.ui.internal.fakeSteamFriends
import app.gamenative.ui.model.ChatViewModel
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.ListItemImage
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.getAvatarURL
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    friendId: Long,
    viewModel: ChatViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.chatState.collectAsStateWithLifecycle()

    LaunchedEffect(friendId) {
        viewModel.setFriend(friendId)
    }

    ChatScreenContent(
        state = state,
        scrollState = viewModel.listState,
        onBack = onBack,
        onTyping = viewModel::onTyping,
        onSendMessage = viewModel::onSendMessage,
    )
}

@Composable
private fun ChatScreenContent(
    state: ChatState,
    scrollState: LazyListState,
    onBack: () -> Unit,
    onTyping: () -> Unit,
    onSendMessage: (String) -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // NOTE: This should be removed once chat is considered stable.
    LaunchedEffect(Unit) {
        if (!PrefManager.ackChatPreview) {
            scope.launch {
                val result = snackbarHost.showSnackbar(
                    message = context.getString(app.gamenative.R.string.chat_preview_warning),
                    actionLabel = context.getString(app.gamenative.R.string.ok),
                )

                if (result == SnackbarResult.ActionPerformed) {
                    PrefManager.ackChatPreview = true
                }
            }
        }
    }

    Scaffold(
        // Exclude ime and navigation bar padding so this can be added by the ChatInput composable
        contentWindowInsets = ScaffoldDefaults
            .contentWindowInsets
            .exclude(WindowInsets.navigationBars)
            .exclude(WindowInsets.ime),
        topBar = {
            ChatTopBar(
                steamFriend = state.friend,
                onBack = onBack,
                // onProfile = {
                //     val msg = "View profile not implemented!\nTry long pressing a friend in the friends list?"
                //     scope.launch { snackbarHost.showSnackbar(msg) }
                // },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            ChatMessages(
                modifier = Modifier.weight(1f),
                snackbarHost = snackbarHost,
                state = state,
                scrollState = scrollState,
            )

            // let this element handle the padding so that the elevation is shown behind the
            // navigation bar
            ChatInput(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                onMessageSent = onSendMessage,
                onTyping = onTyping,
                onResetScroll = {
                    // Scroll to the bottom of the list regardless of the scroll state
                    scope.launch {
                        scrollState.animateScrollToItem(0)
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatMessages(
    modifier: Modifier = Modifier,
    snackbarHost: SnackbarHostState,
    state: ChatState,
    scrollState: LazyListState,
) {
    val scope = rememberCoroutineScope()

    Box(modifier = modifier) {
        AnimatedVisibility(state.messages.isEmpty()) {
            NoChatHistoryBox()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = scrollState,
            reverseLayout = true,
        ) {
            items(state.messages, key = { it.id }) { msg ->
                ChatBubble(
                    message = msg.message,
                    timestamp = SteamUtils.fromSteamTime(msg.timestamp),
                    fromLocal = msg.fromLocal,
                )
            }
        }

        // Show the button if the first visible item is not the first one or if the offset is
        // greater than the threshold.
        val jumpToBottomButtonEnabled by remember {
            derivedStateOf {
                // Arbitrary threshold to show the button
                scrollState.firstVisibleItemIndex > 3
            }
        }

        LaunchedEffect(state.messages) {
            if (!jumpToBottomButtonEnabled) {
                scrollState.animateScrollToItem(0)
            }
        }

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            visible = jumpToBottomButtonEnabled,
            enter = fadeIn() + scaleIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        scrollState.animateScrollToItem(0)
                    }
                },
                content = {
                    Icon(imageVector = Icons.Default.KeyboardDoubleArrowDown, contentDescription = null)
                },
            )
        }

        SnackbarHost(
            modifier = Modifier.align(Alignment.BottomCenter),
            hostState = snackbarHost,
        )
    }
}

@Composable
private fun NoChatHistoryBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
        ) {
            Text(
                modifier = Modifier.padding(24.dp),
                text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.chat_no_history),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    steamFriend: SteamFriend,
    onBack: () -> Unit,
    // onProfile: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ListItemImage(
                    image = { steamFriend.avatarHash.getAvatarURL() },
                    size = 40.dp,
                )

                Spacer(modifier = Modifier.size(12.dp))

                Column {
                    CompositionLocalProvider(
                        LocalContentColor provides steamFriend.statusColor,
                        LocalTextStyle provides TextStyle(
                            lineHeight = 1.em,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                    ) {
                        Text(
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 20.sp,
                            maxLines = 1,
                            text = buildAnnotatedString {
                                append(steamFriend.nameOrNickname)
                                if (steamFriend.statusIcon != null) {
                                    append(" ")
                                    appendInlineContent("icon", "[icon]")
                                }
                            },
                            inlineContent = mapOf(
                                "icon" to InlineTextContent(
                                    Placeholder(
                                        width = 16.sp,
                                        height = 16.sp,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                    ),
                                    children = {
                                        steamFriend.statusIcon?.let {
                                            Icon(imageVector = it, tint = Color.LightGray, contentDescription = it.name)
                                        }
                                    },
                                ),
                            ),
                        )

                        Text(
                            text = steamFriend.isPlayingGameName,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(alpha = .75f),
                        )
                    }
                }
            }
        },
        navigationIcon = {
            BackButton(onClick = onBack)
        },
        // actions = {
        //     IconButton(
        //         onClick = onProfile,
        //         content = { Icon(imageVector = Icons.Default.Person, contentDescription = null) },
        //     )
        // },
    )
}

internal class MessagesPreviewProvider : PreviewParameterProvider<List<FriendMessage>> {
    override val values = sequenceOf(
        emptyList(),
        List(20) {
            FriendMessage(
                id = it.plus(1).toLong(),
                steamIDFriend = 76561198003805806,
                fromLocal = it % 3 == 0,
                message = if (it > 18) {
                    "[sticker type=\"Delivery Cat in a Blanket\", value=0][/sticker]"
                } else {
                    """
                    Lorem ipsum dolor sit amet, consectetur adipiscing elit,
                    sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
                    """.trimIndent()
                },
                // lowPriority = false,
                timestamp = 1737438789 + it,
            )
        },
    )
}

/* NOTE: Launching this composable in preview will make the TopBar shift up.*/
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ChatScreenContent(
    @PreviewParameter(MessagesPreviewProvider::class) messages: List<FriendMessage>,
) {
    PluviaTheme {
        ChatScreenContent(
            state = ChatState(
                friend = fakeSteamFriends()[1],
                messages = messages,
            ),
            scrollState = rememberLazyListState(),
            onBack = { },
            onSendMessage = { },
            onTyping = { },
        )
    }
}
