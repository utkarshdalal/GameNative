package app.gamenative.ui.screen.chat

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import app.gamenative.Constants
import app.gamenative.R
import app.gamenative.data.Emoticon
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import timber.log.Timber

/**
 * Heavily referenced from:
 * https://github.com/android/compose-samples/tree/main/Jetchat
 */

val KeyboardShownKey = SemanticsPropertyKey<Boolean>("KeyboardShownKey")
var SemanticsPropertyReceiver.keyboardShownProperty by KeyboardShownKey

enum class EmojiStickerSelector {
    NONE,
    EMOJI,
    STICKER,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatInput(
    modifier: Modifier = Modifier,
    onMessageSent: (String) -> Unit,
    onTyping: () -> Unit,
    onResetScroll: () -> Unit,
) {
    var isEmoticonsShowing by rememberSaveable { mutableStateOf(EmojiStickerSelector.NONE) }
    val dismissKeyboard = { isEmoticonsShowing = EmojiStickerSelector.NONE }

    // Intercept back navigation if there's a InputSelector visible
    if (isEmoticonsShowing != EmojiStickerSelector.NONE) {
        BackHandler(onBack = dismissKeyboard)
    }

    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    // Used to decide if the keyboard should be shown
    var textFieldFocusState by remember { mutableStateOf(false) }

    Surface(tonalElevation = 2.dp, contentColor = MaterialTheme.colorScheme.secondary) {
        Column(modifier = modifier) {
            UserInputText(
                textFieldValue = textState,
                onTextChanged = {
                    textState = it
                    onTyping()
                },
                // Only show the keyboard if there's no input selector and text field has focus
                keyboardShown = isEmoticonsShowing == EmojiStickerSelector.NONE && textFieldFocusState,
                // Close extended selector if text field receives focus
                onTextFieldFocused = { focused ->
                    if (focused) {
                        isEmoticonsShowing = EmojiStickerSelector.NONE
                        onResetScroll()
                    }
                    textFieldFocusState = focused
                },
                onMessageSent = {
                    onMessageSent(textState.text)
                    // Reset text field and close keyboard
                    textState = TextFieldValue()
                    // Move scroll to bottom
                    onResetScroll()
                },
                isEmoticonShowing = isEmoticonsShowing,
                onEmoticonClick = {
                    isEmoticonsShowing = if (isEmoticonsShowing == EmojiStickerSelector.NONE) {
                        EmojiStickerSelector.EMOJI
                    } else {
                        EmojiStickerSelector.NONE
                    }
                },
            )

            SelectorExpanded(
                isEmoticonsShowing = isEmoticonsShowing,
                onTextAdded = { textState = textState.addText(":$it: ") },
                onStickerAdded = {
                    onMessageSent("/sticker $it")
                    onResetScroll()
                },
            )
        }
    }
}

private fun TextFieldValue.addText(newString: String): TextFieldValue {
    val newText = this.text.replaceRange(
        this.selection.start,
        this.selection.end,
        newString,
    )
    val newSelection = TextRange(
        start = newText.length,
        end = newText.length,
    )

    return this.copy(text = newText, selection = newSelection)
}

@Composable
private fun SelectorExpanded(
    isEmoticonsShowing: EmojiStickerSelector,
    onTextAdded: (String) -> Unit,
    onStickerAdded: (String) -> Unit,
) {
    if (isEmoticonsShowing == EmojiStickerSelector.NONE) return

    // Request focus to force the TextField to lose it
    val focusRequester = FocusRequester()
    // If the selector is shown, always request focus to trigger a TextField.onFocusChange.
    SideEffect {
        if (isEmoticonsShowing == EmojiStickerSelector.EMOJI || isEmoticonsShowing == EmojiStickerSelector.STICKER) {
            focusRequester.requestFocus()
        }
    }

    var selected by remember { mutableStateOf(EmojiStickerSelector.EMOJI) }

    var emotes by rememberSaveable { mutableStateOf(listOf<Emoticon>()) }
    LaunchedEffect(isEmoticonsShowing) {
        emotes = SteamService.fetchEmoticons()
        Timber.d("Emote size: ${emotes.size}")
    }

    Surface(tonalElevation = 8.dp) {
        when (isEmoticonsShowing) {
            EmojiStickerSelector.EMOJI,
            EmojiStickerSelector.STICKER,
            -> EmojiSelector(
                emotes = emotes,
                focusRequester = focusRequester,
                emojiSelector = selected,
                onInnerSelection = { selected = it },
                onTextAdded = onTextAdded,
                onStickerAdded = onStickerAdded,
            )

            else -> throw NotImplementedError("Invalid Emoji selector $isEmoticonsShowing")
        }
    }
}

@ExperimentalFoundationApi
@Composable
private fun UserInputText(
    keyboardType: KeyboardType = KeyboardType.Text,
    onTextChanged: (TextFieldValue) -> Unit,
    textFieldValue: TextFieldValue,
    keyboardShown: Boolean,
    onTextFieldFocused: (Boolean) -> Unit,
    onMessageSent: () -> Unit,
    isEmoticonShowing: EmojiStickerSelector,
    onEmoticonClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth(),
    ) {
        UserInputTextField(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    keyboardShownProperty = keyboardShown
                },
            textFieldValue = textFieldValue,
            onTextChanged = onTextChanged,
            onTextFieldFocused = onTextFieldFocused,
            keyboardType = keyboardType,
            onMessageSent = onMessageSent,
            isEmoticonShowing = isEmoticonShowing,
            onEmoticonClick = onEmoticonClick,
        )
    }
}

@Composable
private fun UserInputTextField(
    modifier: Modifier = Modifier,
    textFieldValue: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onTextFieldFocused: (Boolean) -> Unit,
    isEmoticonShowing: EmojiStickerSelector,
    onEmoticonClick: () -> Unit,
    keyboardType: KeyboardType,
    onMessageSent: () -> Unit,
) {
    var lastFocusState by remember { mutableStateOf(false) }

    TextField(
        modifier = modifier
            .onFocusChanged { state ->
                if (lastFocusState != state.isFocused) {
                    onTextFieldFocused(state.isFocused)
                }
                lastFocusState = state.isFocused
            },
        value = textFieldValue,
        onValueChange = { onTextChanged(it) },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions {
            if (textFieldValue.text.isNotBlank()) onMessageSent()
        },
        maxLines = 3,
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
        placeholder = {
            Text(text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.chat_send_message))
        },
        leadingIcon = {
            val colors = if (isEmoticonShowing == EmojiStickerSelector.NONE) {
                IconButtonDefaults.iconButtonColors()
            } else {
                IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.onSecondary)
            }

            IconButton(
                colors = colors,
                onClick = onEmoticonClick,
                content = {
                    Icon(imageVector = Icons.Outlined.EmojiEmotions, null)
                },
            )
        },
        trailingIcon = {
            val buttonColors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            val border = if (textFieldValue.text.trim().isEmpty()) {
                BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            } else {
                null
            }
            Button(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .height(36.dp),
                enabled = textFieldValue.text.trim().isNotEmpty(),
                onClick = onMessageSent,
                colors = buttonColors,
                border = border,
                contentPadding = PaddingValues(0.dp),
                content = {
                    Text(
                        text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.chat_send),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                },
            )
        },
    )
}

@Composable
fun EmojiSelector(
    emotes: List<Emoticon>,
    focusRequester: FocusRequester,
    emojiSelector: EmojiStickerSelector,
    onInnerSelection: (EmojiStickerSelector) -> Unit,
    onTextAdded: (String) -> Unit,
    onStickerAdded: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .focusRequester(focusRequester) // Requests focus when the Emoji selector is displayed
            .focusTarget(), // Make the emoji selector focusable so it can steal focus from TextField
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            ExtendedSelectorInnerButton(
                text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.chat_emoticons),
                onClick = { onInnerSelection(EmojiStickerSelector.EMOJI) },
                selected = emojiSelector == EmojiStickerSelector.EMOJI,
                modifier = Modifier.weight(1f),
            )
            ExtendedSelectorInnerButton(
                text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.chat_stickers),
                onClick = { onInnerSelection(EmojiStickerSelector.STICKER) },
                selected = emojiSelector == EmojiStickerSelector.STICKER,
                modifier = Modifier.weight(1f),
            )
        }

        EmoteTable(
            modifier = Modifier.padding(8.dp),
            emoticons = emotes.filter {
                if (emojiSelector == EmojiStickerSelector.EMOJI) !it.isSticker else it.isSticker
            },
            onTextAdded = onTextAdded,
            onStickerAdded = onStickerAdded,
        )
    }
}

@Composable
fun ExtendedSelectorInnerButton(
    text: String,
    onClick: () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ButtonDefaults.buttonColors(
        containerColor = if (selected) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        } else {
            Color.Transparent
        },
        disabledContainerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
    )
    TextButton(
        modifier = modifier
            .padding(8.dp)
            .height(36.dp),
        onClick = onClick,
        colors = colors,
        contentPadding = PaddingValues(0.dp),
        content = { Text(text = text, style = MaterialTheme.typography.titleSmall) },
    )
}

@Composable
fun EmoteTable(
    modifier: Modifier = Modifier,
    emoticons: List<Emoticon>,
    onTextAdded: (String) -> Unit,
    onStickerAdded: (String) -> Unit,
) {
    LazyVerticalGrid(
        modifier = modifier.height(270.dp),
        columns = GridCells.Adaptive(64.dp),
        content = {
            items(emoticons) { emoticon ->
                CoilImage(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(64.dp)
                        .clickable {
                            if (emoticon.isSticker) {
                                onStickerAdded(emoticon.name)
                            } else {
                                onTextAdded(emoticon.name)
                            }
                        },
                    imageModel = {
                        val url = if (emoticon.isSticker) {
                            Constants.Chat.STICKER_URL
                        } else {
                            Constants.Chat.EMOTICON_URL
                        }
                        url + emoticon.name
                    },
                    imageOptions = ImageOptions(
                        contentDescription = "${if (emoticon.isSticker) "Sticker" else "Emoticon"} ${emoticon.name}",
                        contentScale = ContentScale.Inside,
                    ),
                    loading = {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(8.dp)
                                .size(32.dp),
                        )
                    },
                    failure = {
                        Icon(Icons.Filled.QuestionMark, null)
                    },
                    previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                )
            }
        },
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
fun Preview_ChatInput() {
    PluviaTheme {
        Column(
            modifier = Modifier
                .imePadding()
                .fillMaxSize(),
        ) {
            Box(modifier = Modifier.weight(1f))
            ChatInput(
                onMessageSent = {},
                onTyping = {},
                onResetScroll = {},
            )
        }
    }
}

internal class EmojiSelectorPreview : PreviewParameterProvider<EmojiStickerSelector> {
    override val values = sequenceOf(EmojiStickerSelector.EMOJI, EmojiStickerSelector.STICKER)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
fun Preview_EmojiSelector(
    @PreviewParameter(EmojiSelectorPreview::class) state: EmojiStickerSelector,
) {
    PluviaTheme {
        EmojiSelector(
            emotes = List(25) {
                Emoticon("emote$it", appID = it, isSticker = state == EmojiStickerSelector.STICKER)
            },
            focusRequester = FocusRequester(),
            emojiSelector = state,
            onInnerSelection = {},
            onTextAdded = {},
            onStickerAdded = {},
        )
    }
}
