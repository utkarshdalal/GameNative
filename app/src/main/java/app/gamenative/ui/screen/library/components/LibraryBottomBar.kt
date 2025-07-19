package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.data.LibraryItem
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.theme.PluviaTheme

@Composable
internal fun LibraryBottomBar(
    state: LibraryState,
    onModalBottomSheet: (Boolean) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Colored top border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
        )
        FlowRow (
            horizontalArrangement = Arrangement.spacedBy((-1).dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(4.dp)
        ) {

            if (state.lastPaginationPage > 1) {
                // Prev page
                OutlinedButton(
                    content = { Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null) },
                    onClick = { onPageChange(-1) },
                    contentPadding = PaddingValues(6.dp),
                    shape = RoundedCornerShape(7.dp, 0.dp, 0.dp, 7.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(35.dp)
                        .width(40.dp)
                )

                // Number
                OutlinedButton(
                    content = { Text("${state.currentPaginationPage}/${state.lastPaginationPage}") },
                    onClick = { },
                    contentPadding = PaddingValues(4.dp),
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(35.dp)
                        .width(55.dp)
                )

                // Next page
                OutlinedButton(
                    content = { Icon(imageVector = Icons.AutoMirrored.Sharp.ArrowForward, contentDescription = null) },
                    onClick = { onPageChange(1) },
                    contentPadding = PaddingValues(6.dp),
                    shape = RoundedCornerShape(0.dp, 7.dp, 7.dp, 0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(35.dp)
                        .width(40.dp)
                )
            }

            // Fills space
            Box (
                modifier = Modifier.weight(1f)
            )

            ExtendedFloatingActionButton(
                text = { Text(text = "Filters") },
                icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                onClick = { onModalBottomSheet(true) },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier
                    .height(35.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp))
                    .clipToBounds()
            )
        }
    }

}

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LibrarySearchBar() {
    val context = LocalContext.current
    PrefManager.init(context)
    val state by remember {
        mutableStateOf(
            LibraryState(
                appInfoList = List(155) { idx ->
                    val item = fakeAppInfo(idx)
                    LibraryItem(
                        index = idx,
                        appId = item.id,
                        name = item.name,
                        iconHash = item.iconHash,
                    )
                }
            )
        )
    }
    PluviaTheme {
        Surface {
            LibraryBottomBar(
                state = state,
                onModalBottomSheet = {},
                onPageChange = {},
            )
        }
    }
}
