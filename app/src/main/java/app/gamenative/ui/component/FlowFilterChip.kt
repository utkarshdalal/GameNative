package app.gamenative.ui.component

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun FlowFilterChip(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable (() -> Unit),
    leadingIcon: @Composable (() -> Unit),
) {
    val chipModifier = Modifier
        .padding(end = 8.dp)
        .then(modifier)
    if (selected) {
        Button(
            modifier = chipModifier,
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(16.dp),
        ) {
            leadingIcon()
            Spacer(modifier = Modifier.width(8.dp))
            label()
        }
    } else {
        OutlinedButton(
            modifier = chipModifier,
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(16.dp),
        ) {
            leadingIcon()
            Spacer(modifier = Modifier.width(8.dp))
            label()
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_FlowFilterChip() {
    PluviaTheme {
        Surface {
            Column {
                FlowFilterChip(
                    selected = true,
                    onClick = { },
                    label = { Text(text = "Chip 1") },
                    leadingIcon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                )
                FlowFilterChip(
                    selected = false,
                    onClick = { },
                    label = { Text(text = "Chip 2") },
                    leadingIcon = { Icon(imageVector = Icons.Default.FilterList, contentDescription = null) },
                )
            }
        }
    }
}
