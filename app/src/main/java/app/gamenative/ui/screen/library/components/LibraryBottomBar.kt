package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.ui.theme.PluviaTheme

@Composable
internal fun LibraryBottomBar() {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Colored top border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
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
//            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(4.dp)
        ) {

            ExtendedFloatingActionButton(
                text = { Text("Prev") },
                icon = { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = null) },
                onClick = { },
                expanded = false,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .height(35.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp, 0.dp, 0.dp, 7.dp))
                    .clipToBounds()
            )

            ExtendedFloatingActionButton(
                text = { Text("Next") },
                icon = { Icon(imageVector = Icons.AutoMirrored.Default.ArrowForwardIos, contentDescription = null) },
                onClick = { },
                expanded = false,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .height(35.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(0.dp, 7.dp, 7.dp, 0.dp))
                    .clipToBounds()
            )

            // Fills space
            Box (
                modifier = Modifier.weight(1f)
            )

            ExtendedFloatingActionButton(
                text = { Text(text = "Filters") },
                icon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                onClick = { },
//                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier
                    .height(35.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp))
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
    PluviaTheme {
        Surface {
            LibraryBottomBar(
            )
        }
    }
}
