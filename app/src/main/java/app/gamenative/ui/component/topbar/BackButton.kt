package app.gamenative.ui.component.topbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.ui.component.RingIconButton
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun BackButton(onClick: () -> Unit) {
    RingIconButton(
        onClick = onClick,
        content = {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate Back")
        },
    )
}

@Preview
@Composable
private fun Preview_BackButton() {
    PluviaTheme {
        Surface {
            BackButton(onClick = {})
        }
    }
}
