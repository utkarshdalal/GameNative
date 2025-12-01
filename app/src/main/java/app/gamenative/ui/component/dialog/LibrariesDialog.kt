package app.gamenative.ui.component.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.R

@Composable
fun LibrariesDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    MessageDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        onConfirmClick = onDismissRequest,
        confirmBtnText = stringResource(R.string.close),
        icon = Icons.Default.Info,
        title = stringResource(R.string.libraries_title),
        message = stringResource(R.string.libraries_message),
    )
}
