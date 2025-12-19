package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gamenative.data.LibraryItem

@Composable
internal fun LibraryList(
    modifier: Modifier = Modifier,
    contentPaddingValues: PaddingValues,
    listState: LazyListState,
    list: List<LibraryItem>,
    onItemClick: (String) -> Unit,
    imageRefreshCounter: Long = 0L,
) {
    if (list.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
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
                    text = androidx.compose.ui.res.stringResource(app.gamenative.R.string.library_no_items),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            state = listState,
            contentPadding = contentPaddingValues,
        ) {
            items(items = list, key = { it.index }) { item ->
                AppItem(
                    modifier = Modifier.animateItem(),
                    appInfo = item,
                    onClick = { onItemClick(item.appId) },
                    imageRefreshCounter = imageRefreshCounter,
                )

                if (item.index < list.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

/***********
 * PREVIEW *
 ***********/
