package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.data.LibraryItem
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import timber.log.Timber
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
internal fun LibrarySearchBar(
    state: LibraryState,
    listState: LazyListState,
    onIsSearching: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onItemClick: (Int) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    // Debouncer: Scroll to the top after a short amount of time after typing quickly
    val internalSearchText = remember { MutableStateFlow(state.searchQuery) }
    LaunchedEffect(Unit) {
        internalSearchText.debounce(500).collect {
            Timber.d("Debounced: Scrolling to top")
            listState.scrollToItem(0)
        }
    }

    // Lambda function to provide new test to both onSearchQuery and internalSearchText
    val onSearchText: (String) -> Unit = {
        onSearchQuery(it)
        internalSearchText.value = it
    }

    // Modern search field with rounded corners
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        TextField(
            value = state.searchQuery,
            onValueChange = onSearchText,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp)),
            placeholder = {
                Text(
                    text = "Search your games...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchText("") },
                        content = {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
        )
    }

    // The dropdown search results are handled elsewhere in the LibraryList component
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_LibrarySearchBar() {
    PluviaTheme {
        Surface {
            LibrarySearchBar(
                state = LibraryState(
                    isSearching = false,
                    appInfoList = List(5) { idx ->
                        val item = fakeAppInfo(idx)
                        LibraryItem(
                            index = idx,
                            appId = item.id,
                            name = item.name,
                            iconHash = item.iconHash,
                        )
                    },
                ),
                listState = rememberLazyListState(),
                onIsSearching = { },
                onSearchQuery = { },
                onSettings = { },
                onLogout = { },
                onItemClick = { },
            )
        }
    }
}
